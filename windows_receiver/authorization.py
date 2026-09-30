"""Shared durable authorization and paste gate for both transports (protocol v1)."""
from __future__ import annotations

import base64
import ctypes
from ctypes import wintypes
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import sqlite3
import threading
import time

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

MAX_FRAME_BYTES = 32768


class Rejected(Exception):
    def __init__(self, code='unauthorized'):
        self.code = code
        super().__init__(code)


def b64(data):
    return base64.urlsafe_b64encode(data).decode('ascii').rstrip('=')


def unb64(value, size=None):
    if not isinstance(value, str) or not value or not value.isascii():
        raise Rejected('invalid_message')
    try:
        data = base64.b64decode(value + '=' * (-len(value) % 4), altchars=b'-_', validate=True)
    except ValueError:
        raise Rejected('invalid_message') from None
    if b64(data) != value or (size is not None and len(data) != size):
        raise Rejected('invalid_message')
    return data


def pack(*values):
    result = bytearray()
    for value in values:
        data = value.encode('utf-8', 'strict')
        result.extend(len(data).to_bytes(4, 'big'))
        result.extend(data)
    return bytes(result)


def public_key(value):
    data = unb64(value)
    try:
        key = serialization.load_der_public_key(data)
    except ValueError:
        raise Rejected('invalid_message') from None
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        raise Rejected('invalid_message')
    if key.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo) != data:
        raise Rejected('invalid_message')
    return key


def parse_message(frame):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('duplicate key')
            result[key] = value
        return result
    try:
        if not isinstance(frame, str) or len(frame.encode('utf-8', 'strict')) > MAX_FRAME_BYTES:
            raise ValueError()
        result = json.loads(frame, object_pairs_hook=pairs,
                            parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
        if not isinstance(result, dict):
            raise ValueError()
        return result
    except (ValueError, UnicodeError):
        raise Rejected('invalid_message') from None


def fields(message, expected, kind):
    if (not isinstance(message, dict) or set(message) != set(expected.split()) or
            type(message.get('v')) is not int or message['v'] != 1 or message.get('type') != kind):
        raise Rejected('invalid_message')


def dpapi(data, decrypt=False):
    """User-scoped DPAPI; never silently fall back to plaintext."""
    class Blob(ctypes.Structure):
        _fields_ = [('size', wintypes.DWORD), ('data', ctypes.POINTER(ctypes.c_ubyte))]
    buffer = (ctypes.c_ubyte * len(data)).from_buffer_copy(data)
    source, destination = Blob(len(data), buffer), Blob()
    crypt = ctypes.WinDLL('crypt32', use_last_error=True)
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.LocalFree.argtypes = [ctypes.c_void_p]
    kernel.LocalFree.restype = ctypes.c_void_p
    function = crypt.CryptUnprotectData if decrypt else crypt.CryptProtectData
    function.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p,
                         ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
    function.restype = wintypes.BOOL
    if not function(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(destination)):
        raise OSError('DPAPI failed')
    try:
        return ctypes.string_at(destination.data, destination.size)
    finally:
        kernel.LocalFree(destination.data)


class Authorization:
    def __init__(self, directory: Path, clock=time.monotonic):
        import msvcrt
        self.lock = threading.RLock()
        self.clock = clock
        self.failed = False
        self.invitation = None
        self.targets = {}
        self.connections = {}
        directory = Path(directory)
        directory.mkdir(parents=True, exist_ok=True)
        self.process_lock = open(directory / 'receiver.lock', 'a+b')
        self.process_lock.seek(0)
        try:
            # Windows permits locking beyond EOF. Do not read a byte another
            # process has locked: that read fails before cleanup on Windows.
            msvcrt.locking(self.process_lock.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError:
            self.process_lock.close()
            raise OSError('Receiver already running') from None
        self.db = None
        try:
            if os.fstat(self.process_lock.fileno()).st_size == 0:
                self.process_lock.write(b'\0')
                self.process_lock.flush()
            path = directory / 'authorization.sqlite3'
            exists = path.exists()
            self.db = sqlite3.connect(path, check_same_thread=False)
            self.db.execute('PRAGMA synchronous=FULL')
            if not exists:
                key = ec.generate_private_key(ec.SECP256R1())
                protected = dpapi(key.private_bytes(serialization.Encoding.DER,
                    serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
                with self.db:
                    self.db.execute('CREATE TABLE identity (id INTEGER PRIMARY KEY CHECK(id=1), private BLOB NOT NULL, epoch INTEGER NOT NULL, phone TEXT)')
                    self.db.execute('CREATE TABLE channels (channel TEXT PRIMARY KEY, target TEXT NOT NULL)')
                    self.db.execute('INSERT INTO identity VALUES (1, ?, 0, NULL)', (protected,))
            rows = self.db.execute('SELECT private, epoch, phone FROM identity').fetchall()
            if len(rows) != 1:
                raise ValueError('invalid identity')
            protected, self.epoch, self.phone = rows[0]
            self.key = serialization.load_der_private_key(dpapi(protected, True), password=None)
            if not isinstance(self.key, ec.EllipticCurvePrivateKey) or not isinstance(self.key.curve, ec.SECP256R1):
                raise ValueError('invalid private key')
            self.channels = dict(self.db.execute('SELECT channel, target FROM channels'))
            if type(self.epoch) is not int or not 0 <= self.epoch <= 2147483647:
                raise ValueError('invalid epoch')
            if self.phone is not None:
                public_key(self.phone)
            if (self.phone is None and self.channels) or any(c not in ('wifi', 'bt') for c in self.channels):
                raise ValueError('invalid authorization')
            from invitation import validate_target
            for c, target in self.channels.items():
                validate_target(c, target)
            self.receiver_key = b64(self.key.public_key().public_bytes(serialization.Encoding.DER,
                serialization.PublicFormat.SubjectPublicKeyInfo))
        except Exception:
            self.close()
            raise

    def close(self):
        with self.lock:
            self.failed = True
            for closer in list(self.connections.values()):
                try:
                    closer()
                except Exception:
                    pass
            self.connections.clear()
            if self.db is not None:
                self.db.close()
                self.db = None
            self.process_lock.close()

    def set_target(self, channel, target):
        from invitation import validate_target
        validate_target(channel, target)
        with self.lock:
            self.targets[channel] = target

    def new_invitation(self, channel):
        from invitation import invitation_fields
        with self.lock:
            self._healthy()
            target = self.targets[channel]
            values = invitation_fields(target, b64(secrets.token_bytes(16)),
                b64(secrets.token_bytes(32)), b64(hashlib.sha256(unb64(self.receiver_key)).digest()))
            self.invitation = (values, target, self.clock() + 600)
            return dict(values)

    def show_invitation(self):
        with self.lock:
            self._healthy()
            if self.invitation is None or self.clock() >= self.invitation[2]:
                raise Rejected('invitation_expired')
            return dict(self.invitation[0])

    def _healthy(self):
        if self.failed:
            raise Rejected('storage_failed')

    def _check(self, hello):
        self._healthy()
        if self.targets.get(hello['channel']) != hello['target']:
            raise Rejected()
        if self.phone is not None and self.phone != hello['phoneKey']:
            raise Rejected()
        if hello['mode'] == 'resume':
            if self.phone != hello['phoneKey'] or self.channels.get(hello['channel']) != hello['target']:
                raise Rejected()
        else:
            if (self.invitation is None or self.clock() >= self.invitation[2] or
                    self.invitation[0]['i'] != hello['invite'] or self.invitation[1] != hello['target']):
                raise Rejected('invitation_invalid')

    def challenge(self, hello, channel):
        fields(hello, 'v type mode channel target invite phoneKey clientNonce', 'hello')
        if (any(not isinstance(hello[k], str) for k in ('mode', 'channel', 'target', 'invite', 'phoneKey', 'clientNonce')) or
                hello['channel'] != channel or hello['mode'] not in ('invite', 'resume')):
            raise Rejected('invalid_message')
        public_key(hello['phoneKey'])
        unb64(hello['clientNonce'], 32)
        if hello['mode'] == 'invite':
            unb64(hello['invite'], 16)
        elif hello['invite'] != '':
            raise Rejected('invalid_message')
        with self.lock:
            self._check(hello)
            nonce = b64(secrets.token_bytes(32))
            transcript = ['1', hello['mode'], channel, hello['target'], hello['invite'],
                          hello['phoneKey'], hello['clientNonce'], self.receiver_key, nonce, str(self.epoch)]
            pending = Pending(dict(hello), transcript, self.epoch, self.clock() + 10)
            return pending, dict(v=1, type='challenge', receiverKey=self.receiver_key,
                serverNonce=nonce, epoch=self.epoch, signature=self.sign('receiver-challenge', *transcript))

    def sign(self, *values):
        return b64(self.key.sign(pack(*values), ec.ECDSA(hashes.SHA256())))

    def authorize(self, pending, proof):
        fields(proof, 'v type signature inviteProof', 'proof')
        with self.lock:
            if pending.used or self.clock() >= pending.deadline or pending.epoch != self.epoch:
                raise Rejected()
            pending.used = True
            self._check(pending.hello)
            try:
                public_key(pending.hello['phoneKey']).verify(unb64(proof['signature']),
                    pack('phone-proof', *pending.transcript), ec.ECDSA(hashes.SHA256()))
            except Exception:
                raise Rejected() from None
            if pending.hello['mode'] == 'invite':
                expected = hmac.digest(unb64(self.invitation[0]['k'], 32),
                                      pack('invite-proof', *pending.transcript), 'sha256')
                if not hmac.compare_digest(expected, unb64(proof['inviteProof'], 32)):
                    raise Rejected()
                channels = dict(self.channels)
                channels[pending.hello['channel']] = pending.hello['target']
                try:
                    with self.db:
                        self.db.execute('UPDATE identity SET phone=? WHERE id=1', (pending.hello['phoneKey'],))
                        self.db.execute('INSERT OR REPLACE INTO channels VALUES (?, ?)',
                                        (pending.hello['channel'], pending.hello['target']))
                except Exception:
                    self.failed = True
                    raise Rejected('storage_failed') from None
                self.phone, self.channels, self.invitation = pending.hello['phoneKey'], channels, None
            elif proof['inviteProof'] != '':
                raise Rejected('invalid_message')
            channels_text = ','.join(sorted(self.channels))
            answer = dict(v=1, type='authorized', epoch=self.epoch, channels=channels_text,
                          target=pending.hello['target'], signature=self.sign('receiver-authorized',
                          *pending.transcript, str(self.epoch), channels_text, pending.hello['target']))
            return Session(self, pending.hello, self.epoch), answer

    def register(self, token, closer):
        with self.lock:
            self._healthy()
            self.connections[token] = closer

    def unregister(self, token):
        with self.lock:
            self.connections.pop(token, None)

    def revoke(self):
        with self.lock:
            self.failed = True  # Also fail closed if commit fails.
            self.invitation = None
            closers = list(self.connections.values())
            try:
                if self.epoch == 2147483647:
                    raise OSError('epoch exhausted')
                with self.db:
                    self.db.execute('UPDATE identity SET epoch=?, phone=NULL WHERE id=1', (self.epoch + 1,))
                    self.db.execute('DELETE FROM channels')
                self.epoch += 1
                self.phone, self.channels = None, {}
                self.failed = False
            finally:
                for close in closers:
                    try:
                        close()
                    except Exception:
                        pass


class Pending:
    def __init__(self, hello, transcript, epoch, deadline):
        self.hello, self.transcript, self.epoch, self.deadline = hello, transcript, epoch, deadline
        self.used = False


class Session:
    def __init__(self, authorization, hello, epoch):
        self.authorization, self.hello, self.epoch = authorization, hello, epoch
        self.seen = set()

    def receive(self, message, paste, unlocked):
        operation = message.get('id') if isinstance(message, dict) else None
        response = dict(v=1, type='error')
        if not isinstance(operation, str) or not operation.isascii() or not 1 <= len(operation) <= 128:
            return dict(response, code='invalid_message')
        response['id'] = operation
        with self.authorization.lock:
            if operation in self.seen:
                return dict(response, code='duplicate_operation')
            self.seen.add(operation)
            try:
                fields(message, 'v type id text', 'final_text')
                text = message['text']
                if not isinstance(text, str) or not text.strip() or '\0' in text or not 1 <= len(text.encode('utf-8', 'strict')) <= 4096:
                    raise Rejected('invalid_message')
                auth = self.authorization
                auth._healthy()
                if (self.epoch != auth.epoch or auth.phone != self.hello['phoneKey'] or
                        auth.channels.get(self.hello['channel']) != self.hello['target'] or
                        auth.targets.get(self.hello['channel']) != self.hello['target']):
                    raise Rejected()
                if not unlocked():
                    raise Rejected('session_locked')
            except Rejected as exc:
                return dict(response, code=exc.code)
            except Exception:
                return dict(response, code='invalid_message')
            try:
                paste(text)
            except Exception:
                return dict(response, code='paste_failed')
            return dict(v=1, type='pasted', id=operation)

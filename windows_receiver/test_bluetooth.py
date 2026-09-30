"""Controllable RFCOMM entry using a duplex socket, not radio hardware."""
import socket
import tempfile
import threading
import unittest
from pathlib import Path

from test_receiver import Phone
from bluetooth import serve_client, write_message, read_message
from authorization import Authorization


class RfcommPeer:
    """Controllable framed RFCOMM boundary; no radio/SDP hardware claim."""
    def __init__(self, authorization, paste, unlocked=lambda: True):
        server, self.client = socket.socketpair()
        self.client.settimeout(3)
        self.stream = self.client.makefile('rwb', buffering=0)
        self.worker = threading.Thread(target=serve_client, args=(server, authorization, paste),
                                       kwargs={'unlocked': unlocked})
        self.worker.start()

    def __enter__(self): return self

    def __exit__(self, *args):
        try:
            self.client.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.stream.close()
        self.client.close()
        self.worker.join(3)
        if self.worker.is_alive():
            raise AssertionError('RFCOMM worker did not close')

    def exchange(self, phone, target, invitation=None):
        hello = phone.hello(target, invitation)
        write_message(self.stream, hello)
        challenge = read_message(self.stream)
        if challenge['type'] == 'error':
            return challenge
        write_message(self.stream, phone.proof(hello, challenge, invitation))
        answer = read_message(self.stream)
        if answer['type'] == 'authorized':
            phone.verify_authorized(answer)
        return answer

    def send_text(self, operation, text):
        write_message(self.stream, dict(v=1, type='final_text', id=operation, text=text))
        return read_message(self.stream)


class BluetoothTests(unittest.TestCase):
    def test_signed_session_persistent_duplicate_and_locked_paste(self):
        pasted = []
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        directory = temporary.name
        if True:
            authorization = Authorization(Path(directory))
            self.addCleanup(authorization.close)
            target = 'bt:AABBCCDDEEFF'
            authorization.set_target('bt', target)
            invitation = authorization.new_invitation('bt')
            server, client = socket.socketpair()
            self.addCleanup(client.close)
            self.addCleanup(server.close)
            unlocked = iter([False, True])
            worker = threading.Thread(target=serve_client, args=(server, authorization, pasted.append),
                                      kwargs={'unlocked': lambda: next(unlocked)})
            worker.start()
            client.settimeout(3)
            with client.makefile('rwb', buffering=0) as stream:
                phone = Phone()
                hello = phone.hello(target, invitation)
                write_message(stream, hello)
                challenge = read_message(stream)
                write_message(stream, phone.proof(hello, challenge, invitation))
                authorized = read_message(stream)
                phone.verify_authorized(authorized)
                self.assertEqual(authorized['channels'], 'bt')
                for operation, expected in [('one', 'session_locked'), ('one', 'duplicate_operation'), ('two', None)]:
                    write_message(stream, dict(v=1, type='final_text', id=operation, text='中文'))
                    answer = read_message(stream)
                    self.assertEqual(answer, dict(v=1, type='error', id=operation, code=expected)
                        if expected else dict(v=1, type='pasted', id=operation))
            client.close()
            worker.join(3)
            self.assertFalse(worker.is_alive())
            self.assertEqual(pasted, ['中文'])

    def test_revoke_waits_for_started_paste_and_no_new_paste_can_start_afterward(self):
        from concurrent.futures import ThreadPoolExecutor
        started, release, revoking = threading.Event(), threading.Event(), threading.Event()
        pasted = []
        def paste(text):
            started.set()
            if not release.wait(3):
                raise AssertionError('test did not release paste')
            pasted.append(text)
        with tempfile.TemporaryDirectory() as directory:
            authorization = Authorization(Path(directory))
            try:
                target = 'bt:AABBCCDDEEFF'
                authorization.set_target('bt', target)
                invitation = authorization.new_invitation('bt')
                phone = Phone()
                with RfcommPeer(authorization, paste) as peer, ThreadPoolExecutor(2) as pool:
                    self.assertEqual(peer.exchange(phone, target, invitation)['type'], 'authorized')
                    operation = pool.submit(peer.send_text, 'started', 'may complete')
                    self.assertTrue(started.wait(3))
                    def revoke():
                        revoking.set()
                        authorization.revoke()
                    revocation = pool.submit(revoke)
                    try:
                        self.assertTrue(revoking.wait(3))
                        self.assertFalse(revocation.done())
                    finally:
                        release.set()
                    revocation.result(3)
                    try:
                        operation.result(3)
                    except (EOFError, OSError):
                        pass  # Lost operation reply is input-result-unknown, not a retry.
                with RfcommPeer(authorization, paste) as peer:
                    self.assertEqual(peer.exchange(phone, target)['type'], 'error')
                self.assertEqual(pasted, ['may complete'])
            finally:
                release.set()
                authorization.close()

    def test_failed_store_refuses_subsequent_rfcomm_connections_without_worker_exception(self):
        import sqlite3
        with tempfile.TemporaryDirectory() as directory:
            authorization = Authorization(Path(directory))
            try:
                target = 'bt:AABBCCDDEEFF'
                authorization.set_target('bt', target)
                invitation = authorization.new_invitation('bt')
                phone = Phone()
                blocker = sqlite3.connect(Path(directory) / 'authorization.sqlite3')
                try:
                    blocker.execute('BEGIN IMMEDIATE')
                    with RfcommPeer(authorization, lambda text: self.fail('must not paste')) as peer:
                        # A FULL synchronous transaction blocked by another writer must fail closed.
                        peer.client.settimeout(10)
                        self.assertEqual(peer.exchange(phone, target, invitation)['code'], 'storage_failed')
                finally:
                    blocker.rollback()
                    blocker.close()
                with RfcommPeer(authorization, lambda text: self.fail('must not paste')) as peer:
                    self.assertEqual(read_message(peer.stream), dict(v=1, type='error', code='storage_failed'))
            finally:
                authorization.close()

    def test_startup_invites_only_after_selected_listener_and_sdp_ready(self):
        from bluetooth import run_bluetooth
        from contextlib import contextmanager
        with tempfile.TemporaryDirectory() as directory:
            authorization = Authorization(Path(directory))
            try:
                events = []
                class Listener:
                    def __enter__(self): return self
                    def __exit__(self, *args): events.append('closed')
                    def listen(self, backlog): events.append('listen')
                    def settimeout(self, timeout): pass
                    def accept(self): raise KeyboardInterrupt()
                def listener_factory(radio):
                    events.append(radio)
                    return Listener()
                @contextmanager
                def register(listener, service):
                    events.append(service)
                    yield
                    events.append('unregistered')
                def display(values):
                    events.append(values['a'])
                with self.assertRaises(KeyboardInterrupt):
                    run_bluetooth(authorization, lambda text: None,
                        radios=[('112233445566', 'physical')], listener_factory=listener_factory,
                        register_service=register, display=display, console=False)
                self.assertEqual(events[:4], ['112233445566', 'listen',
                    '9c8f8513-7d4d-4a70-82c7-11e1da28a041', '112233445566'])
                self.assertEqual(events[-1], 'closed')
            finally:
                authorization.close()

    def test_console_multiple_candidates_require_explicit_valid_choice(self):
        import io
        from contextlib import redirect_stdout
        from receiver import select_target
        candidates = [('112233445566', 'first'), ('AABBCCDDEEFF', 'second')]
        answers = iter(['0', 'not a choice', '2'])
        with redirect_stdout(io.StringIO()):
            self.assertEqual(select_target(candidates, 'physical radio',
                read=lambda prompt: next(answers)), candidates[1])
            self.assertEqual(select_target(['192.168.1.20'], 'LAN IPv4',
                read=lambda prompt: self.fail('single candidate must not prompt')), '192.168.1.20')

    def test_no_radio_stops_before_listener_or_invitation(self):
        from bluetooth import run_bluetooth
        with tempfile.TemporaryDirectory() as directory:
            authorization = Authorization(Path(directory))
            try:
                with self.assertRaisesRegex(OSError, 'No usable'):
                    run_bluetooth(authorization, lambda text: None, radios=[],
                        listener_factory=lambda radio: self.fail('must not create listener'),
                        display=lambda values: self.fail('must not show QR'), console=False)
            finally:
                authorization.close()

    def test_security_option_failure_closes_listener_without_binding(self):
        from bluetooth import open_listener
        from unittest.mock import patch
        class Listener:
            closed = False
            def setsockopt(self, *args): raise OSError('link security unavailable')
            def bind(self, value): raise AssertionError('must not bind insecure listener')
            def close(self): self.closed = True
        listener = Listener()
        with patch('bluetooth.sys.platform', 'win32'):
            with self.assertRaises(OSError):
                open_listener('112233445566', lambda *args: listener)
        self.assertTrue(listener.closed)

    def test_sdp_publishes_selected_physical_endpoint_and_removes_it_on_exit(self):
        import ctypes
        import uuid
        from bluetooth import register_rfcomm_service, WSAQUERYSETW, SERVICE_UUID
        class Listener:
            def getsockname(self): return '11:22:33:44:55:66', 7
        records = []
        def publish(pointer, operation, flags):
            query = ctypes.cast(pointer, ctypes.POINTER(WSAQUERYSETW)).contents
            endpoint = query.lpcsaBuffer.contents.LocalAddr.lpSockaddr.contents
            records.append((operation, endpoint.btAddr, endpoint.port,
                            query.lpServiceClassId.contents.as_uuid()))
            return 0
        with register_rfcomm_service(Listener(), SERVICE_UUID, publish):
            self.assertEqual(records, [(0, 0x112233445566, 7, uuid.UUID(SERVICE_UUID))])
        self.assertEqual(records[-1], (2, 0x112233445566, 7, uuid.UUID(SERVICE_UUID)))

    def test_listener_binds_selected_physical_radio_with_link_security(self):
        from bluetooth import select_radio, open_listener
        from unittest.mock import patch
        radios = [('AABBCCDDEEFF', 'first'), ('112233445566', 'second')]
        with self.assertRaises(ValueError):
            select_radio(radios)
        self.assertEqual(select_radio(radios, '112233445566'), '112233445566')
        class Listener:
            def __init__(self): self.options = []
            def setsockopt(self, *args): self.options.append(args)
            def bind(self, value): self.bound = value
            def getsockname(self): return self.bound[0], 7
            def close(self): self.closed = True
        listener = Listener()
        with patch('bluetooth.sys.platform', 'win32'):
            self.assertIs(open_listener('112233445566', lambda *args: listener), listener)
        self.assertEqual(listener.bound, ('11:22:33:44:55:66', 0xffffffff))
        self.assertEqual([v[1] for v in listener.options], [-2147483647, 2])


if __name__ == '__main__':
    unittest.main()

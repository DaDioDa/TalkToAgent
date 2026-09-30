"""Protocol tests through a real local WebSocket and recording paste boundary."""
import asyncio
import base64
import hashlib
import hmac
import json
import struct
import tempfile
import unittest
from functools import partial
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from websockets.asyncio.client import connect
from websockets.asyncio.server import serve

from receiver import handle_connection, MAX_FRAME_BYTES


def encode(data):
    return base64.urlsafe_b64encode(data).decode().rstrip('=')


def decode(value):
    return base64.urlsafe_b64decode(value + '=' * (-len(value) % 4))


def packed(*values):
    return b''.join(struct.pack('>I', len(v.encode())) + v.encode() for v in values)


class Phone:
    def __init__(self):
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.public = encode(self.key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo))

    def hello(self, target, invitation=None):
        return dict(v=1, type='hello', mode='invite' if invitation else 'resume',
                    channel=target.split(':')[0], target=target,
                    invite=invitation['i'] if invitation else '', phoneKey=self.public,
                    clientNonce=encode(bytes(range(32))))

    def proof(self, hello, challenge, invitation=None):
        self.transcript = [str(hello['v']), hello['mode'], hello['channel'], hello['target'],
                           hello['invite'], hello['phoneKey'], hello['clientNonce'],
                           challenge['receiverKey'], challenge['serverNonce'], str(challenge['epoch'])]
        receiver = serialization.load_der_public_key(decode(challenge['receiverKey']))
        receiver.verify(decode(challenge['signature']), packed('receiver-challenge', *self.transcript),
                        ec.ECDSA(hashes.SHA256()))
        if invitation:
            assert encode(hashlib.sha256(decode(challenge['receiverKey'])).digest()) == invitation['r']
        return dict(v=1, type='proof', signature=encode(self.key.sign(
            packed('phone-proof', *self.transcript), ec.ECDSA(hashes.SHA256()))),
            inviteProof=encode(hmac.digest(decode(invitation['k']),
                packed('invite-proof', *self.transcript), 'sha256')) if invitation else '')

    def verify_authorized(self, answer):
        receiver = serialization.load_der_public_key(decode(self.transcript[7]))
        receiver.verify(decode(answer['signature']), packed('receiver-authorized', *self.transcript,
                        str(answer['epoch']), answer['channels'], answer['target']),
                        ec.ECDSA(hashes.SHA256()))
        assert set(answer) == {'v', 'type', 'epoch', 'channels', 'target', 'signature'}
        assert answer['epoch'] == int(self.transcript[-1])


class RecordingPasteAction:
    def __init__(self):
        self.pasted = []
    def paste(self, text):
        self.pasted.append(text)


class ReceiverProtocolTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        from authorization import Authorization
        self.directory = tempfile.TemporaryDirectory()
        self.authorization = Authorization(Path(self.directory.name))
        self.paste_action = RecordingPasteAction()
        self.phone = Phone()
        self.unlocked = True
        self.server = await serve(partial(handle_connection, authorization=self.authorization,
            paste_action=self.paste_action, unlocked=lambda: self.unlocked), '127.0.0.1', 0,
            max_size=MAX_FRAME_BYTES, compression=None)
        port = self.server.sockets[0].getsockname()[1]
        self.target = f'wifi:127.0.0.1:{port}'
        self.authorization.set_target('wifi', self.target)
        self.url = f'ws://127.0.0.1:{port}/ws'
        self.invitation = self.authorization.new_invitation('wifi')

    async def asyncTearDown(self):
        self.server.close()
        await self.server.wait_closed()
        self.authorization.close()
        self.directory.cleanup()

    async def authenticate(self, ws, invitation=True, phone=None):
        phone = phone or self.phone
        invite = self.invitation if invitation else None
        hello = phone.hello(self.target, invite)
        await ws.send(json.dumps(hello))
        challenge = json.loads(await ws.recv())
        self.assertEqual(set(challenge), {'v', 'type', 'receiverKey', 'serverNonce', 'epoch', 'signature'})
        await ws.send(json.dumps(phone.proof(hello, challenge, invite)))
        answer = json.loads(await ws.recv())
        if answer['type'] == 'authorized':
            phone.verify_authorized(answer)
        return answer

    async def exchange(self, ws, phone=None, invitation=None, target=None, corrupt=False):
        phone = phone or self.phone
        hello = phone.hello(target or self.target, invitation)
        await ws.send(json.dumps(hello))
        challenge = json.loads(await ws.recv())
        if challenge['type'] == 'error':
            return challenge
        proof = phone.proof(hello, challenge, invitation)
        if corrupt:
            proof['inviteProof'] = encode(bytes(32))
        await ws.send(json.dumps(proof))
        answer = json.loads(await ws.recv())
        if answer['type'] == 'authorized':
            phone.verify_authorized(answer)
        return answer

    async def test_final_text_utf8_limit_is_preserved_after_persistent_authorization(self):
        text = 'a' * 4096
        async with connect(self.url) as ws:
            await self.authenticate(ws)
            await ws.send(json.dumps(dict(v=1, type='final_text', id='limit', text=text)))
            self.assertEqual(json.loads(await ws.recv()), dict(v=1, type='pasted', id='limit'))
        self.assertEqual(self.paste_action.pasted, [text])

    async def test_invalid_final_text_never_pastes_after_valid_authorization(self):
        texts = ['', ' \t\r\n', '\u00a0', '你' * 1366, 'bad\0text', '\ud800']
        for index, text in enumerate(texts):
            with self.subTest(index=index):
                async with connect(self.url) as ws:
                    await self.authenticate(ws, invitation=index == 0)
                    await ws.send(json.dumps(dict(v=1, type='final_text', id='invalid', text=text)))
                    self.assertEqual(json.loads(await ws.recv()), dict(v=1, type='error', id='invalid', code='invalid_message'))
        self.assertEqual(self.paste_action.pasted, [])

    async def test_ambiguous_or_extra_final_fields_never_paste(self):
        frames = [
            '{"v":1,"type":"final_text","id":"bad","text":"first","text":"second"}',
            '{"v":1,"type":"final_text","id":"bad","text":"test","extra":true}',
            '{"v":true,"type":"final_text","id":"bad","text":"test"}',
        ]
        for index, frame in enumerate(frames):
            with self.subTest(index=index):
                async with connect(self.url) as ws:
                    await self.authenticate(ws, invitation=index == 0)
                    await ws.send(frame)
                    response = json.loads(await ws.recv())
                    self.assertEqual(response['type'], 'error')
                    self.assertEqual(response['code'], 'invalid_message')
        self.assertEqual(self.paste_action.pasted, [])

    async def test_oversized_websocket_frame_closes_before_paste(self):
        from websockets.exceptions import ConnectionClosed
        async with connect(self.url) as ws:
            await self.authenticate(ws)
            await ws.send('x' * (MAX_FRAME_BYTES + 1))
            with self.assertRaises(ConnectionClosed):
                await ws.recv()
        self.assertEqual(self.paste_action.pasted, [])

    async def test_paste_failure_is_correlated_without_private_exception_detail(self):
        def fail(text):
            raise OSError('private paste detail')
        self.paste_action.paste = fail
        async with connect(self.url) as ws:
            await self.authenticate(ws)
            await ws.send(json.dumps(dict(v=1, type='final_text', id='failure', text='test')))
            response = await ws.recv()
            self.assertEqual(json.loads(response), dict(v=1, type='error', id='failure', code='paste_failed'))
            self.assertNotIn('private paste detail', response)

    async def test_invalid_proof_does_not_consume_invitation(self):
        async with connect(self.url) as ws:
            self.assertEqual((await self.exchange(ws, invitation=self.invitation, corrupt=True))['type'], 'error')
        async with connect(self.url) as ws:
            self.assertEqual((await self.authenticate(ws))['type'], 'authorized')

    async def test_invitation_refresh_and_exact_expiry_reject_old_without_consuming_new(self):
        now = [100.0]
        self.authorization.clock = lambda: now[0]
        old = self.authorization.new_invitation('wifi')
        self.invitation = self.authorization.new_invitation('wifi')
        async with connect(self.url) as ws:
            self.assertEqual((await self.exchange(ws, invitation=old))['code'], 'invitation_invalid')
        now[0] = 700.0
        async with connect(self.url) as ws:
            self.assertEqual((await self.exchange(ws, invitation=self.invitation))['code'], 'invitation_invalid')
        self.invitation = self.authorization.new_invitation('wifi')
        now[0] = 1299.999
        async with connect(self.url) as ws:
            self.assertEqual((await self.authenticate(ws))['type'], 'authorized')

    async def test_lost_authorized_reply_resumes_original_phone_without_resending_text(self):
        async with connect(self.url) as ws:
            hello = self.phone.hello(self.target, self.invitation)
            await ws.send(json.dumps(hello))
            challenge = json.loads(await ws.recv())
            await ws.send(json.dumps(self.phone.proof(hello, challenge, self.invitation)))
            # Drop the connection without consuming the authorized reply.
            await ws.close()
        async with connect(self.url) as ws:
            self.assertEqual((await self.exchange(ws, phone=Phone(), invitation=self.invitation))['type'], 'error')
        async with connect(self.url) as ws:
            self.assertEqual((await self.exchange(ws))['type'], 'authorized')
        self.assertEqual(self.paste_action.pasted, [])

    async def test_restart_keeps_identity_authorization_but_not_unused_invitation(self):
        from authorization import Authorization
        async with connect(self.url) as ws:
            self.assertEqual((await self.authenticate(ws))['type'], 'authorized')
        unused = self.authorization.new_invitation('wifi')
        self.server.close()
        await self.server.wait_closed()
        self.authorization.close()
        self.authorization = Authorization(Path(self.directory.name))
        port = int(self.target.rsplit(':', 1)[1])
        self.server = await serve(partial(handle_connection, authorization=self.authorization,
            paste_action=self.paste_action, unlocked=lambda: True), '127.0.0.1', port,
            max_size=MAX_FRAME_BYTES, compression=None)
        self.authorization.set_target('wifi', self.target)
        async with connect(self.url) as ws:
            hello = self.phone.hello(self.target)
            await ws.send(json.dumps(hello))
            challenge = json.loads(await ws.recv())
            self.assertEqual(encode(hashlib.sha256(decode(challenge['receiverKey'])).digest()), unused['r'])
            await ws.send(json.dumps(self.phone.proof(hello, challenge)))
            answer = json.loads(await ws.recv())
            self.phone.verify_authorized(answer)
            self.assertEqual(answer['type'], 'authorized')
            await ws.send(json.dumps(dict(v=1, type='final_text', id='after-restart', text='persisted')))
            self.assertEqual(json.loads(await ws.recv())['type'], 'pasted')
        async with connect(self.url) as ws:
            self.assertEqual((await self.exchange(ws, invitation=unused))['code'], 'invitation_invalid')
        self.assertEqual(self.paste_action.pasted, ['persisted'])

    async def test_failed_authorization_commit_refuses_reply_resume_and_existing_paste(self):
        import sqlite3
        async with connect(self.url) as active:
            await self.authenticate(active)
            invitation = self.authorization.new_invitation('wifi')
            blocker = sqlite3.connect(Path(self.directory.name) / 'authorization.sqlite3')
            try:
                blocker.execute('BEGIN IMMEDIATE')
                async with connect(self.url) as ws:
                    answer = await self.exchange(ws, invitation=invitation)
                    self.assertEqual(answer, dict(v=1, type='error', code='storage_failed'))
            finally:
                blocker.rollback()
                blocker.close()
            await active.send(json.dumps(dict(v=1, type='final_text', id='blocked', text='never')))
            self.assertEqual(json.loads(await active.recv()),
                dict(v=1, type='error', id='blocked', code='storage_failed'))
        async with connect(self.url) as ws:
            self.assertEqual(json.loads(await ws.recv())['code'], 'storage_failed')
        self.assertEqual(self.paste_action.pasted, [])

    async def test_cross_channel_requires_same_phone_invite_and_target_change_requires_new_invite(self):
        from test_bluetooth import RfcommPeer
        bt = 'bt:AABBCCDDEEFF'
        self.authorization.set_target('bt', bt)
        async with connect(self.url) as ws:
            await self.authenticate(ws)
        with RfcommPeer(self.authorization, self.paste_action.paste) as peer:
            self.assertEqual(peer.exchange(self.phone, bt)['type'], 'error')
        invitation = self.authorization.new_invitation('bt')
        with RfcommPeer(self.authorization, self.paste_action.paste) as peer:
            self.assertEqual(peer.exchange(Phone(), bt, invitation)['type'], 'error')
        with RfcommPeer(self.authorization, self.paste_action.paste) as peer:
            answer = peer.exchange(self.phone, bt, invitation)
            self.assertEqual((answer['type'], answer['channels']), ('authorized', 'bt,wifi'))
            self.assertEqual(peer.send_text('bt', 'Bluetooth')['type'], 'pasted')
        previous = self.target
        self.target = f'wifi:127.0.0.2:{previous.rsplit(":", 1)[1]}'
        self.authorization.set_target('wifi', self.target)
        async with connect(self.url) as ws:
            self.assertEqual((await self.exchange(ws))['type'], 'error')
        invitation = self.authorization.new_invitation('wifi')
        async with connect(self.url) as ws:
            self.assertEqual((await self.exchange(ws, invitation=invitation))['channels'], 'bt,wifi')
        async with connect(self.url) as ws:
            self.assertEqual((await self.exchange(ws, target=previous))['type'], 'error')
        async with connect(self.url) as ws:
            self.assertEqual((await self.exchange(ws))['type'], 'authorized')
        self.assertEqual(self.paste_action.pasted, ['Bluetooth'])

    async def test_revoke_closes_both_channels_and_refuses_resume_then_allows_replacement(self):
        from test_bluetooth import RfcommPeer
        from bluetooth import read_message
        bt = 'bt:AABBCCDDEEFF'
        self.authorization.set_target('bt', bt)
        async with connect(self.url) as active:
            await self.authenticate(active)
            invite = self.authorization.new_invitation('bt')
            with RfcommPeer(self.authorization, self.paste_action.paste) as peer:
                self.assertEqual(peer.exchange(self.phone, bt, invite)['channels'], 'bt,wifi')
                await asyncio.to_thread(self.authorization.revoke)
                await asyncio.wait_for(active.wait_closed(), 3)
                with self.assertRaises((EOFError, OSError)):
                    read_message(peer.stream)
            with RfcommPeer(self.authorization, self.paste_action.paste) as peer:
                self.assertEqual(peer.exchange(self.phone, bt)['type'], 'error')
        async with connect(self.url) as ws:
            self.assertEqual((await self.exchange(ws))['type'], 'error')
        self.invitation = self.authorization.new_invitation('wifi')
        async with connect(self.url) as ws:
            self.assertEqual((await self.authenticate(ws, phone=Phone()))['channels'], 'wifi')
            await ws.send(json.dumps(dict(v=1, type='final_text', id='replacement', text='new phone')))
            self.assertEqual(json.loads(await ws.recv())['type'], 'pasted')
        self.assertEqual(self.paste_action.pasted, ['new phone'])

    async def test_failed_revoke_closes_active_socket_and_never_claims_durable_success(self):
        import io
        import sqlite3
        from contextlib import redirect_stdout
        from receiver import console_command
        output = io.StringIO()
        async with connect(self.url) as ws:
            await self.authenticate(ws)
            blocker = sqlite3.connect(Path(self.directory.name) / 'authorization.sqlite3')
            try:
                blocker.execute('BEGIN IMMEDIATE')
                with redirect_stdout(output):
                    await asyncio.to_thread(console_command, 'revoke', self.authorization, 'wifi')
                await asyncio.wait_for(ws.wait_closed(), 3)
            finally:
                blocker.rollback()
                blocker.close()
        self.assertIn('durable revocation is NOT confirmed', output.getvalue())
        self.assertNotIn('authorization revoked', output.getvalue())
        self.assertNotIn(self.invitation['k'], output.getvalue())
        async with connect(self.url) as ws:
            self.assertEqual(json.loads(await ws.recv())['code'], 'storage_failed')
        self.assertEqual(self.paste_action.pasted, [])

    async def test_locked_windows_desktop_refuses_wifi_paste(self):
        self.unlocked = False
        async with connect(self.url) as ws:
            await self.authenticate(ws)
            await ws.send(json.dumps(dict(v=1, type='final_text', id='locked', text='never')))
            self.assertEqual(json.loads(await ws.recv()),
                dict(v=1, type='error', id='locked', code='session_locked'))
        self.assertEqual(self.paste_action.pasted, [])

    async def test_unreadable_authorization_store_is_not_rebuilt_as_unclaimed(self):
        import sqlite3
        from authorization import Authorization
        self.server.close()
        await self.server.wait_closed()
        self.authorization.close()
        store = Path(self.directory.name) / 'authorization.sqlite3'
        store.write_bytes(b'not a readable authorization database')
        for attempt in range(2):
            with self.assertRaises(sqlite3.DatabaseError):
                Authorization(Path(self.directory.name))
            self.assertEqual(store.read_bytes(), b'not a readable authorization database')

    async def test_console_show_never_refreshes_expiry_and_new_replaces_immediately(self):
        import io
        from contextlib import redirect_stdout
        from receiver import console_command
        now = [100.0]
        self.authorization.clock = lambda: now[0]
        self.invitation = self.authorization.new_invitation('wifi')
        displayed = []
        output = io.StringIO()
        now[0] = 699.0
        with redirect_stdout(output):
            console_command('show', self.authorization, 'wifi', displayed.append)
        self.assertEqual(displayed, [self.invitation])
        now[0] = 700.0
        with redirect_stdout(output):
            console_command('show', self.authorization, 'wifi', displayed.append)
        self.assertEqual(len(displayed), 1)
        self.assertIn('invitation_expired', output.getvalue())
        async with connect(self.url) as ws:
            self.assertEqual((await self.exchange(ws, invitation=self.invitation))['type'], 'error')
        with redirect_stdout(output):
            console_command('new', self.authorization, 'wifi', displayed.append)
        async with connect(self.url) as ws:
            self.assertEqual((await self.exchange(ws, invitation=displayed[-1]))['type'], 'authorized')
        self.assertNotIn(self.invitation['k'], output.getvalue())

    async def test_second_receiver_process_lock_refuses_store_without_invalidating_first_invite(self):
        from authorization import Authorization
        with self.assertRaisesRegex(OSError, 'already running'):
            Authorization(Path(self.directory.name))
        async with connect(self.url) as ws:
            self.assertEqual((await self.authenticate(ws))['type'], 'authorized')

    async def test_refresh_between_challenge_and_proof_invalidates_pending_claim(self):
        async with connect(self.url) as ws:
            old = self.invitation
            hello = self.phone.hello(self.target, old)
            await ws.send(json.dumps(hello))
            challenge = json.loads(await ws.recv())
            self.invitation = self.authorization.new_invitation('wifi')
            await ws.send(json.dumps(self.phone.proof(hello, challenge, old)))
            self.assertEqual(json.loads(await ws.recv())['code'], 'invitation_invalid')
        async with connect(self.url) as ws:
            self.assertEqual((await self.authenticate(ws))['type'], 'authorized')

    async def test_invitation_expiry_is_rechecked_after_challenge_before_commit(self):
        now = [100.0]
        self.authorization.clock = lambda: now[0]
        self.invitation = self.authorization.new_invitation('wifi')
        now[0] = 699.0
        async with connect(self.url) as ws:
            hello = self.phone.hello(self.target, self.invitation)
            await ws.send(json.dumps(hello))
            challenge = json.loads(await ws.recv())
            now[0] = 700.0
            await ws.send(json.dumps(self.phone.proof(hello, challenge, self.invitation)))
            self.assertEqual(json.loads(await ws.recv())['code'], 'invitation_invalid')
        self.invitation = self.authorization.new_invitation('wifi')
        async with connect(self.url) as ws:
            self.assertEqual((await self.authenticate(ws))['type'], 'authorized')

    async def test_parallel_phones_only_one_claims_invitation(self):
        async with connect(self.url) as first, connect(self.url) as second:
            phones = [Phone(), Phone()]
            sockets = [first, second]
            hellos = [phone.hello(self.target, self.invitation) for phone in phones]
            await asyncio.gather(*(ws.send(json.dumps(hello)) for ws, hello in zip(sockets, hellos)))
            challenges = await asyncio.gather(*(ws.recv() for ws in sockets))
            proofs = [phone.proof(hello, json.loads(challenge), self.invitation)
                      for phone, hello, challenge in zip(phones, hellos, challenges)]
            await asyncio.gather(*(ws.send(json.dumps(proof)) for ws, proof in zip(sockets, proofs)))
            answers = [json.loads(answer) for answer in await asyncio.gather(*(ws.recv() for ws in sockets))]
            self.assertEqual(sorted(answer['type'] for answer in answers), ['authorized', 'error'])
            winner = next(index for index, answer in enumerate(answers) if answer['type'] == 'authorized')
            phones[winner].verify_authorized(answers[winner])
            await sockets[winner].send(json.dumps(dict(v=1, type='final_text', id='winner', text='winner')))
            self.assertEqual(json.loads(await sockets[winner].recv())['type'], 'pasted')
        self.assertEqual(self.paste_action.pasted, ['winner'])

    async def test_receiver_entry_binds_selected_target_before_inviting(self):
        from receiver import run_receiver
        ready = asyncio.Event()
        invitations = []
        def display(values):
            invitations.append(values)
            ready.set()
        task = asyncio.create_task(run_receiver(0, self.authorization, self.paste_action,
            host='127.0.0.1', display=display, console=False, unlocked=lambda: True))
        try:
            waiter = asyncio.create_task(ready.wait())
            done, _ = await asyncio.wait([task, waiter], timeout=3,
                                         return_when=asyncio.FIRST_COMPLETED)
            if task in done:
                await task
            self.assertTrue(ready.is_set())
            values = invitations[0]
            async with connect(f"ws://{values['h']}:{values['p']}/ws") as ws:
                phone = Phone()
                hello = phone.hello(f"wifi:{values['h']}:{values['p']}", values)
                await ws.send(json.dumps(hello))
                challenge = json.loads(await ws.recv())
                await ws.send(json.dumps(phone.proof(hello, challenge, values)))
                answer = json.loads(await ws.recv())
                phone.verify_authorized(answer)
                self.assertEqual(answer['type'], 'authorized')
        finally:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
            if 'waiter' in locals():
                waiter.cancel()
                await asyncio.gather(waiter, return_exceptions=True)

    async def test_signed_invitation_pastes_exactly_once(self):
        text = '繁體中文 Mixed English：保留標點'
        async with connect(self.url) as ws:
            answer = await self.authenticate(ws)
            self.assertEqual((answer['type'], answer['channels']), ('authorized', 'wifi'))
            await ws.send(json.dumps(dict(v=1, type='final_text', id='one', text=text)))
            await ws.send(json.dumps(dict(v=1, type='final_text', id='two', text='never')))
            self.assertEqual(json.loads(await ws.recv()), dict(v=1, type='pasted', id='one'))
        self.assertEqual(self.paste_action.pasted, [text])


if __name__ == '__main__':
    unittest.main()

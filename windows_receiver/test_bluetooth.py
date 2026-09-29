import json
import unittest

from bluetooth import BluetoothSession


class BluetoothTests(unittest.TestCase):
    def test_authorized_persistent_session_and_rejection(self):
        pasted = []
        session = BluetoothSession('phone', lambda device: device == 'phone',
                                   lambda: True, pasted.append)
        self.assertEqual(session.receive({'type': 'final_text', 'id': '1', 'text': '中文'}),
                         {'type': 'pasted', 'id': '1'})
        self.assertEqual(session.receive({'type': 'final_text', 'id': '1', 'text': '中文'}),
                         {'type': 'error', 'id': '1', 'code': 'duplicate_operation'})
        self.assertEqual(session.receive({'type': 'final_text', 'id': '2', 'text': 'next'}),
                         {'type': 'pasted', 'id': '2'})
        self.assertEqual(pasted, ['中文', 'next'])
        denied = BluetoothSession('stranger', lambda device: False, lambda: True, pasted.append)
        self.assertEqual(denied.receive({'type': 'final_text', 'id': '3', 'text': 'no'}),
                         {'type': 'error', 'id': '3', 'code': 'unauthorized'})
        self.assertEqual(pasted, ['中文', 'next'])

    def test_locked_and_bad_text(self):
        pasted = []
        session = BluetoothSession('phone', lambda device: True, lambda: False, pasted.append)
        self.assertEqual(session.receive({'type': 'final_text', 'id': 'a', 'text': 'no'}),
                         {'type': 'error', 'id': 'a', 'code': 'session_locked'})
        self.assertEqual(session.receive({'type': 'final_text', 'id': 'b', 'text': 'x' * 4097}),
                         {'type': 'error', 'id': 'b', 'code': 'invalid_message'})
        self.assertEqual(pasted, [])

    def test_runtime_auth_lock_duplicate_and_paste(self):
        from bluetooth import serve_client, write_message, read_message
        import io

        class FakeConnection:
            def __init__(self, incoming):
                self.input = io.BytesIO(incoming)
                self.output = io.BytesIO()
            def settimeout(self, seconds):
                pass
            def makefile(self, *args, **kwargs):
                class View:
                    def __enter__(view): return view
                    def __exit__(view, *args): pass
                    def read(view, count): return self.input.read(count)
                    def write(view, data): return self.output.write(data)
                    def flush(view): pass
                return View()

        source = io.BytesIO()
        for message in ({'type': 'authenticate', 'pairingCode': 'secret'},
                        {'type': 'final_text', 'id': '1', 'text': 'test'},
                        {'type': 'final_text', 'id': '1', 'text': 'test'},
                        {'type': 'final_text', 'id': '2', 'text': 'test'}):
            write_message(source, message)
        connection = FakeConnection(source.getvalue())
        pasted = []
        unlocked = iter([False, True])
        serve_client(connection, 'device', 'secret', pasted.append, lambda: next(unlocked))
        connection.output.seek(0)
        self.assertEqual([read_message(connection.output) for _ in range(4)], [
            {'type': 'authenticated'},
            {'type': 'error', 'id': '1', 'code': 'session_locked'},
            {'type': 'error', 'id': '1', 'code': 'duplicate_operation'},
            {'type': 'pasted', 'id': '2'}])
        self.assertEqual(pasted, ['test'])

    def test_listener_security_failure_closes_socket(self):
        from bluetooth import open_listener
        from unittest.mock import patch
        class Socket:
            closed = False
            def setsockopt(self, *args):
                raise OSError('security unavailable')
            def close(self): self.closed = True
        sock = Socket()
        with patch('bluetooth.sys.platform', 'win32'):
            with self.assertRaisesRegex(OSError, 'security unavailable'):
                open_listener(lambda *args: sock)
        self.assertTrue(sock.closed)

    def test_sdp_registration_failure_closes_before_accept(self):
        from bluetooth import run_bluetooth, register_rfcomm_service
        from unittest.mock import patch
        events = []
        class Listener:
            def __enter__(self): return self
            def __exit__(self, *args): events.append('close')
            def getsockname(self): return ('00:00:00:00:00:00', 7)
            def listen(self, backlog): events.append('listen')
            def accept(self): raise AssertionError('must not accept')
        def fail(query, operation, flags):
            events.append(('register', operation))
            return -1
        with patch('bluetooth.sys.platform', 'win32'):
            with self.assertRaises(OSError):
                run_bluetooth('code', lambda text: None, lambda: Listener(),
                              lambda listener, uuid: register_rfcomm_service(
                                  listener, uuid, fail, lambda: 10050))
        self.assertEqual(events, ['listen', ('register', 0), 'close'])

    def test_sdp_registration_lifetime_and_bound_channel(self):
        from bluetooth import run_bluetooth, register_rfcomm_service, SERVICE_UUID
        from unittest.mock import patch
        import ctypes
        import uuid
        events = []
        class Listener:
            def __enter__(self): return self
            def __exit__(self, *args): events.append('close')
            def getsockname(self): return ('01:23:45:67:89:ab', 23)
            def listen(self, backlog): events.append('listen')
            def accept(self):
                events.append('accept')
                raise KeyboardInterrupt
        def set_service(query_ptr, operation, flags):
            from bluetooth import WSAQUERYSETW
            query = ctypes.cast(query_ptr, ctypes.POINTER(WSAQUERYSETW)).contents
            address = query.lpcsaBuffer.contents.LocalAddr
            sockaddr = address.lpSockaddr.contents
            events.append(('set', operation, flags, query.dwSize, query.dwNameSpace,
                           query.dwNumberOfCsAddrs, query.lpszServiceInstanceName,
                           query.lpServiceClassId.contents.as_uuid(),
                           address.iSockaddrLength, sockaddr.addressFamily,
                           sockaddr.btAddr, sockaddr.port,
                           query.lpcsaBuffer.contents.iSocketType,
                           query.lpcsaBuffer.contents.iProtocol))
            return 0
        with patch('bluetooth.sys.platform', 'win32'):
            with self.assertRaises(KeyboardInterrupt):
                run_bluetooth('code', lambda text: None, lambda: Listener(),
                              lambda listener, service_uuid: register_rfcomm_service(
                                  listener, service_uuid, set_service))
        self.assertEqual(events[0], 'listen')
        for index, operation in ((1, 0), (3, 2)):
            self.assertEqual(events[index], ('set', operation, 0, 120, 16, 1,
                'TalkToAgent', uuid.UUID(SERVICE_UUID), 40, 32,
                0x0123456789ab, 23, 1, 3))
        self.assertEqual(events[2], 'accept')
        self.assertEqual(events[4], 'close')

    def test_fragmented_reads(self):
        from bluetooth import read_message
        import io

        class Fragmented(io.BytesIO):
            def read(self, size=-1):
                return super().read(min(size, 1))

        payload = json.dumps({'type': 'authenticate', 'pairingCode': 'abc'}).encode()
        stream = Fragmented(len(payload).to_bytes(4, 'big') + payload)
        self.assertEqual(read_message(stream)['pairingCode'], 'abc')

    def test_stream_framing_and_reconnect_no_replay(self):
        from bluetooth import read_message
        import io
        payload = json.dumps({'type': 'final_text', 'id': 'x', 'text': 'a'}).encode()
        stream = io.BytesIO(len(payload).to_bytes(4, 'big') + payload + b'\x00\x01\x00\x00')
        self.assertEqual(read_message(stream)['id'], 'x')
        with self.assertRaises(ValueError):
            read_message(stream)

import json
import unittest
from functools import partial

from websockets.asyncio.client import connect
from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed

from receiver import MAX_FRAME_BYTES, MAX_TEXT_BYTES, handle_connection


class RecordingPasteAction:
    def __init__(self):
        self.pasted = []

    def paste(self, text):
        self.pasted.append(text)


class ReceiverProtocolTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.paste_action = RecordingPasteAction()
        await self.start_server(self.paste_action)

    async def start_server(self, paste_action):
        self.server = await serve(
            partial(
                handle_connection,
                pairing_code="pairing-test-code",
                paste_action=paste_action,
            ),
            "127.0.0.1",
            0,
            max_size=MAX_FRAME_BYTES,
            compression=None,
        )
        port = self.server.sockets[0].getsockname()[1]
        self.url = f"ws://127.0.0.1:{port}/ws"

    async def asyncTearDown(self):
        self.server.close()
        await self.server.wait_closed()

    async def authenticate(self, websocket, pairing_code="pairing-test-code"):
        await websocket.send(
            json.dumps({"type": "authenticate", "pairingCode": pairing_code})
        )
        return json.loads(await websocket.recv())

    async def test_authenticated_final_text_is_pasted_once_without_modification(self):
        final_text = "繁體中文 Mixed English：保留標點"
        async with connect(self.url) as websocket:
            self.assertEqual(
                await self.authenticate(websocket), {"type": "authenticated"}
            )
            await websocket.send(json.dumps({"type": "final_text", "text": final_text}))
            await websocket.send(
                json.dumps({"type": "final_text", "text": "must not paste twice"})
            )
            self.assertEqual(await websocket.recv(), '{"type":"pasted"}')

        self.assertEqual(self.paste_action.pasted, [final_text])

    async def test_wrong_pairing_code_never_pastes(self):
        async with connect(self.url) as websocket:
            self.assertEqual(
                await self.authenticate(websocket, "wrong-code"),
                {"type": "authentication_failed"},
            )

        self.assertEqual(self.paste_action.pasted, [])

    async def test_malformed_authentication_is_rejected_without_paste(self):
        async with connect(self.url) as websocket:
            await websocket.send("not-json")
            self.assertEqual(
                await websocket.recv(),
                '{"type":"error","code":"invalid_message"}',
            )

        self.assertEqual(self.paste_action.pasted, [])

    async def test_blank_and_whitespace_only_text_are_rejected(self):
        for text in ("", " \t\r\n", "\u00a0"):
            with self.subTest(text=repr(text)):
                async with connect(self.url) as websocket:
                    self.assertEqual(
                        await self.authenticate(websocket), {"type": "authenticated"}
                    )
                    await websocket.send(
                        json.dumps({"type": "final_text", "text": text})
                    )
                    self.assertEqual(
                        json.loads(await websocket.recv()),
                        {"type": "error", "code": "invalid_message"},
                    )

        self.assertEqual(self.paste_action.pasted, [])

    async def test_text_at_utf8_byte_limit_is_pasted_once(self):
        final_text = "a" * MAX_TEXT_BYTES
        async with connect(self.url) as websocket:
            self.assertEqual(
                await self.authenticate(websocket), {"type": "authenticated"}
            )
            await websocket.send(json.dumps({"type": "final_text", "text": final_text}))
            self.assertEqual(await websocket.recv(), '{"type":"pasted"}')

        self.assertEqual(self.paste_action.pasted, [final_text])

    async def test_oversized_utf8_text_is_rejected_without_paste(self):
        too_large = "你" * (MAX_TEXT_BYTES // len("你".encode("utf-8")) + 1)
        async with connect(self.url) as websocket:
            self.assertEqual(
                await self.authenticate(websocket), {"type": "authenticated"}
            )
            await websocket.send(json.dumps({"type": "final_text", "text": too_large}))
            self.assertEqual(
                json.loads(await websocket.recv()),
                {"type": "error", "code": "invalid_message"},
            )

        self.assertEqual(self.paste_action.pasted, [])

    async def test_nul_in_text_is_rejected_without_paste(self):
        async with connect(self.url) as websocket:
            self.assertEqual(
                await self.authenticate(websocket), {"type": "authenticated"}
            )
            await websocket.send(
                json.dumps({"type": "final_text", "text": "bad\0text"})
            )
            self.assertEqual(
                json.loads(await websocket.recv()),
                {"type": "error", "code": "invalid_message"},
            )

        self.assertEqual(self.paste_action.pasted, [])

    async def test_malformed_unicode_is_rejected_without_paste(self):
        async with connect(self.url) as websocket:
            self.assertEqual(
                await self.authenticate(websocket), {"type": "authenticated"}
            )
            await websocket.send('{"type":"final_text","text":"\\ud800"}')
            self.assertEqual(
                json.loads(await websocket.recv()),
                {"type": "error", "code": "invalid_message"},
            )

        self.assertEqual(self.paste_action.pasted, [])

    async def test_unexpected_fields_are_rejected_without_paste(self):
        async with connect(self.url) as websocket:
            self.assertEqual(
                await self.authenticate(websocket), {"type": "authenticated"}
            )
            await websocket.send(
                json.dumps({"type": "final_text", "text": "test", "extra": True})
            )
            self.assertEqual(
                json.loads(await websocket.recv()),
                {"type": "error", "code": "invalid_message"},
            )

        self.assertEqual(self.paste_action.pasted, [])

    async def test_websocket_frame_limit_closes_before_paste(self):
        async with connect(self.url) as websocket:
            self.assertEqual(
                await self.authenticate(websocket), {"type": "authenticated"}
            )
            await websocket.send("x" * (MAX_FRAME_BYTES + 1))
            with self.assertRaises(ConnectionClosed):
                await websocket.recv()

        self.assertEqual(self.paste_action.pasted, [])

    async def test_paste_failure_never_returns_success_or_exception_details(self):
        class FailingPasteAction:
            def paste(self, text):
                raise OSError("private paste detail")

        self.server.close()
        await self.server.wait_closed()
        await self.start_server(FailingPasteAction())

        async with connect(self.url) as websocket:
            self.assertEqual(
                await self.authenticate(websocket), {"type": "authenticated"}
            )
            await websocket.send(json.dumps({"type": "final_text", "text": "test"}))
            failure = await websocket.recv()
            self.assertEqual(
                json.loads(failure), {"type": "error", "code": "paste_failed"}
            )
            self.assertNotIn("private paste detail", failure)


if __name__ == "__main__":
    unittest.main()

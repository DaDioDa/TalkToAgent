"""Windows WebSocket receiver for one manually submitted text message per connection."""

from __future__ import annotations

import argparse
import asyncio
import ctypes
import hmac
import json
import secrets
import socket
import sys
from ctypes import wintypes
from functools import partial
from typing import Protocol

from websockets.asyncio.server import ServerConnection, serve
from websockets.exceptions import ConnectionClosed

MAX_TEXT_BYTES = 4096
MAX_FRAME_BYTES = 32768
AUTHENTICATION_TIMEOUT_SECONDS = 10
FINAL_TEXT_TIMEOUT_SECONDS = 60
WEBSOCKET_PATH = "/ws"


class PasteAction(Protocol):
    def paste(self, text: str) -> None:
        """Write text to the clipboard and paste it into the current focus."""


class WindowsPasteAction:
    """Use the Windows Unicode clipboard and inject Ctrl+V without adding keys."""

    CF_UNICODETEXT = 13
    GMEM_MOVEABLE = 0x0002
    VK_CONTROL = 0x11
    VK_V = 0x56
    KEYEVENTF_KEYUP = 0x0002

    def __init__(self) -> None:
        if sys.platform != "win32":
            raise OSError("The real paste action is available only on Windows.")
        self._user32 = ctypes.WinDLL("user32", use_last_error=True)
        self._kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        self._configure_windows_api()

    def _configure_windows_api(self) -> None:
        self._user32.OpenClipboard.argtypes = [wintypes.HWND]
        self._user32.OpenClipboard.restype = wintypes.BOOL
        self._user32.EmptyClipboard.argtypes = []
        self._user32.EmptyClipboard.restype = wintypes.BOOL
        self._user32.SetClipboardData.argtypes = [wintypes.UINT, wintypes.HANDLE]
        self._user32.SetClipboardData.restype = wintypes.HANDLE
        self._user32.CloseClipboard.argtypes = []
        self._user32.CloseClipboard.restype = wintypes.BOOL
        self._user32.keybd_event.argtypes = [
            wintypes.BYTE,
            wintypes.BYTE,
            wintypes.DWORD,
            ctypes.c_size_t,
        ]
        self._user32.keybd_event.restype = None
        self._kernel32.GlobalAlloc.argtypes = [wintypes.UINT, ctypes.c_size_t]
        self._kernel32.GlobalAlloc.restype = wintypes.HGLOBAL
        self._kernel32.GlobalLock.argtypes = [wintypes.HGLOBAL]
        self._kernel32.GlobalLock.restype = ctypes.c_void_p
        self._kernel32.GlobalUnlock.argtypes = [wintypes.HGLOBAL]
        self._kernel32.GlobalUnlock.restype = wintypes.BOOL
        self._kernel32.GlobalFree.argtypes = [wintypes.HGLOBAL]
        self._kernel32.GlobalFree.restype = wintypes.HGLOBAL

    @staticmethod
    def _raise_last_error(operation: str) -> None:
        error = ctypes.get_last_error()
        raise OSError(error, f"Windows {operation} failed.")

    def paste(self, text: str) -> None:
        encoded = (text + "\0").encode("utf-16-le")
        if not self._user32.OpenClipboard(None):
            self._raise_last_error("OpenClipboard")

        clipboard_open = True
        memory = None
        try:
            if not self._user32.EmptyClipboard():
                self._raise_last_error("EmptyClipboard")
            memory = self._kernel32.GlobalAlloc(self.GMEM_MOVEABLE, len(encoded))
            if not memory:
                self._raise_last_error("GlobalAlloc")
            destination = self._kernel32.GlobalLock(memory)
            if not destination:
                self._raise_last_error("GlobalLock")
            try:
                ctypes.memmove(destination, encoded, len(encoded))
            finally:
                self._kernel32.GlobalUnlock(memory)

            if not self._user32.SetClipboardData(self.CF_UNICODETEXT, memory):
                self._raise_last_error("SetClipboardData")
            memory = None  # Windows owns the allocation after SetClipboardData.
        finally:
            if memory:
                self._kernel32.GlobalFree(memory)
            if clipboard_open:
                if not self._user32.CloseClipboard():
                    self._raise_last_error("CloseClipboard")
                clipboard_open = False

        self._user32.keybd_event(self.VK_CONTROL, 0, 0, 0)
        self._user32.keybd_event(self.VK_V, 0, 0, 0)
        self._user32.keybd_event(self.VK_V, 0, self.KEYEVENTF_KEYUP, 0)
        self._user32.keybd_event(self.VK_CONTROL, 0, self.KEYEVENTF_KEYUP, 0)


def _reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def _parse_object(frame: object) -> dict[str, object] | None:
    if not isinstance(frame, str):
        return None
    try:
        parsed = json.loads(frame, object_pairs_hook=_reject_duplicate_keys)
    except (json.JSONDecodeError, ValueError):
        return None
    return parsed if isinstance(parsed, dict) else None


def _valid_final_text(text: object) -> bool:
    if not isinstance(text, str) or not text.strip() or "\0" in text:
        return False
    try:
        return len(text.encode("utf-8", errors="strict")) <= MAX_TEXT_BYTES
    except UnicodeEncodeError:
        return False


async def _send_error(connection: ServerConnection, code: str) -> None:
    await connection.send(json.dumps({"type": "error", "code": code}, separators=(",", ":")))
    await connection.close(code=1008)


async def handle_connection(
    connection: ServerConnection,
    pairing_code: str,
    paste_action: PasteAction,
) -> None:
    """Authenticate one socket and deliver at most one valid final text."""
    if connection.request.path != WEBSOCKET_PATH:
        await _send_error(connection, "invalid_message")
        return

    try:
        authentication_frame = await asyncio.wait_for(
            connection.recv(), timeout=AUTHENTICATION_TIMEOUT_SECONDS
        )
    except (asyncio.TimeoutError, ConnectionClosed):
        await connection.close(code=1008)
        return

    authentication = _parse_object(authentication_frame)
    if (
        authentication is None
        or set(authentication) != {"type", "pairingCode"}
        or authentication.get("type") != "authenticate"
        or not isinstance(authentication.get("pairingCode"), str)
    ):
        await _send_error(connection, "invalid_message")
        return

    supplied_pairing_code = authentication["pairingCode"]
    if not supplied_pairing_code.isascii() or not hmac.compare_digest(
        supplied_pairing_code, pairing_code
    ):
        await connection.send('{"type":"authentication_failed"}')
        await connection.close(code=1008)
        return

    await connection.send('{"type":"authenticated"}')
    try:
        final_text_frame = await asyncio.wait_for(
            connection.recv(), timeout=FINAL_TEXT_TIMEOUT_SECONDS
        )
    except (asyncio.TimeoutError, ConnectionClosed):
        await connection.close(code=1008)
        return

    final_text_message = _parse_object(final_text_frame)
    if (
        final_text_message is None
        or set(final_text_message) != {"type", "text"}
        or final_text_message.get("type") != "final_text"
        or not _valid_final_text(final_text_message.get("text"))
    ):
        await _send_error(connection, "invalid_message")
        return

    text = final_text_message["text"]
    try:
        paste_action.paste(text)
    except Exception:
        # Don't include exception details: they may contain private input text.
        await _send_error(connection, "paste_failed")
        return

    await connection.send('{"type":"pasted"}')
    await connection.close(code=1000)


def _local_ipv4_addresses() -> list[str]:
    addresses: set[str] = set()
    route_probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # UDP connect selects the local route and doesn't send a network packet.
        route_probe.connect(("192.0.2.1", 80))
        addresses.add(route_probe.getsockname()[0])
    except OSError:
        pass
    finally:
        route_probe.close()

    try:
        addresses.update(socket.gethostbyname_ex(socket.gethostname())[2])
    except OSError:
        pass
    return sorted(address for address in addresses if not address.startswith("127."))


async def run_receiver(port: int, pairing_code: str, paste_action: PasteAction) -> None:
    handler = partial(
        handle_connection,
        pairing_code=pairing_code,
        paste_action=paste_action,
    )
    async with serve(
        handler,
        "0.0.0.0",
        port,
        max_size=MAX_FRAME_BYTES,
        max_queue=1,
        compression=None,
    ):
        addresses = _local_ipv4_addresses()
        print(f"Windows Receiver listening on port {port} at /ws")
        for address in addresses:
            print(f"  ws://{address}:{port}/ws")
        if not addresses:
            print("  No LAN IPv4 address found; check the Windows network connection.")
        print(f"Pairing code (enter in the phone app): {pairing_code}")
        print("WARNING: ws:// is NOT encrypted; pairing only checks authorization.")
        print("Use only on a trusted private home LAN, never on office or public networks.")
        print("In the app, use the IP for the same Wi-Fi as the phone, authenticate, then send text.")
        print("Focus a Notepad/browser text field before sending.")
        print("The clipboard will be replaced; this receiver does not save submitted text.")
        print("Press Ctrl+C to stop the receiver.")
        await asyncio.Future()


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Receive one authenticated manual text per connection."
    )
    parser.add_argument(
        "--port", type=int, default=8765, help="WebSocket listen port (default: 8765)"
    )
    arguments = parser.parse_args()
    if not 1 <= arguments.port <= 65535:
        parser.error("--port must be between 1 and 65535")

    pairing_code = secrets.token_urlsafe(18)
    paste_action = WindowsPasteAction()
    try:
        asyncio.run(run_receiver(arguments.port, pairing_code, paste_action))
    except KeyboardInterrupt:
        print("Receiver stopped.")


if __name__ == "__main__":
    main()

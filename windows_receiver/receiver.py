"""Windows WebSocket receiver for one manually submitted text message per connection."""

from __future__ import annotations

import argparse
import asyncio
import ctypes
import json
import os
from pathlib import Path
import socket
import threading
import sys
from ctypes import wintypes
from contextlib import contextmanager
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


async def _send_error(connection: ServerConnection, code: str) -> None:
    await connection.send(json.dumps({"v": 1, "type": "error", "code": code}, separators=(",", ":")))
    await connection.close(code=1008)


async def handle_connection(
    connection: ServerConnection,
    authorization,
    paste_action: PasteAction,
    unlocked=None,
) -> None:
    """Shared P-256 handshake; Wi-Fi delivers at most one final_text."""
    from authorization import Rejected, parse_message
    if unlocked is None:
        from bluetooth import session_unlocked
        unlocked = session_unlocked
    loop = asyncio.get_running_loop()
    token = object()
    def close():
        loop.call_soon_threadsafe(lambda: asyncio.create_task(connection.close(code=1008)))
    try:
        authorization.register(token, close)
        if connection.request is None or connection.request.path != WEBSOCKET_PATH:
            raise Rejected('invalid_message')
        hello = parse_message(await asyncio.wait_for(connection.recv(), AUTHENTICATION_TIMEOUT_SECONDS))
        pending, challenge = authorization.challenge(hello, 'wifi')
        await connection.send(json.dumps(challenge, separators=(',', ':')))
        proof = parse_message(await asyncio.wait_for(connection.recv(), AUTHENTICATION_TIMEOUT_SECONDS))
        session, answer = authorization.authorize(pending, proof)
        await connection.send(json.dumps(answer, separators=(',', ':')))
        message = parse_message(await asyncio.wait_for(connection.recv(), FINAL_TEXT_TIMEOUT_SECONDS))
        response = session.receive(message, paste_action.paste, unlocked)
        await connection.send(json.dumps(response, separators=(',', ':')))
        await connection.close(code=1000 if response['type'] == 'pasted' else 1008)
    except Rejected as exc:
        try:
            await _send_error(connection, exc.code)
        except ConnectionClosed:
            pass
    except (asyncio.TimeoutError, ConnectionClosed):
        await connection.close(code=1008)
    except Exception:
        try:
            await _send_error(connection, 'internal_error')
        except ConnectionClosed:
            pass
    finally:
        authorization.unregister(token)


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


def select_target(candidates, label, read=input):
    """A single candidate is automatic; multiple candidates require explicit choice."""
    if not candidates:
        print(f'No usable {label}; startup stopped. Check the Windows adapter and retry.')
        raise OSError(f'No usable {label}; startup stopped.')
    if len(candidates) == 1:
        return candidates[0]
    for index, candidate in enumerate(candidates, 1):
        print(f'{index}: {candidate}')
    while True:
        try:
            choice = int(read(f'Select {label} [1-{len(candidates)}]: '))
            if 1 <= choice <= len(candidates):
                return candidates[choice - 1]
        except ValueError:
            pass
        except EOFError:
            print('Target selection requires an interactive console. Remove stdin redirection and restart.')
            raise OSError('Target selection requires an interactive console.') from None
        print('Select one listed target.')


def display_invitation(values):
    from invitation import show_terminal
    print('WARNING: anyone holding this QR can claim authorization. Do not share,')
    print('capture, redirect or save this terminal output. Invitation expires in 10 minutes.')
    print('Use show to redraw without extending expiry; enlarge the terminal if needed.')
    try:
        show_terminal(values)
    except OSError as exc:
        # This module emits only fixed terminal-repair messages, not key material.
        print(str(exc))
        raise


def console_command(command, authorization, channel, display=display_invitation):
    from authorization import Rejected
    try:
        if command == 'new':
            display(authorization.new_invitation(channel))
        elif command == 'show':
            display(authorization.show_invitation())
        elif command == 'revoke':
            authorization.revoke()
            print('All Wi-Fi and Bluetooth application authorization revoked; sessions closed.')
        else:
            print('Commands: new / show / revoke. Ctrl+C stops the receiver.')
    except Rejected as exc:
        print(f'Operation refused: {exc.code}. Use new for an expired invitation.')
    except Exception:
        # Never log exception text, URI, proof or key material.
        if command == 'revoke':
            print('Revocation failed; this process refuses all input.')
            print('durable revocation is NOT confirmed. Stop and repair the authorization store.')
        else:
            print('Invitation not displayed. Repair terminal settings and use show;')
            print('use new only when you intend to replace the invitation.')


@contextmanager
def console_session(authorization, channel, display=display_invitation, enabled=True):
    stopped = threading.Event()
    def run():
        while not stopped.is_set():
            try:
                command = input('new / show / revoke > ').strip().lower()
            except (EOFError, OSError):
                return
            if not stopped.is_set():
                console_command(command, authorization, channel, display)
    if enabled:
        # Blocking stdin must not prevent Ctrl+C/event-loop shutdown.
        threading.Thread(target=run, name='receiver-console', daemon=True).start()
    try:
        yield
    finally:
        stopped.set()


async def run_receiver(port, authorization, paste_action, host=None,
                       display=display_invitation, console=True, unlocked=None):
    if host is None:
        from invitation import validate_target
        candidates = []
        for address in _local_ipv4_addresses():
            try:
                validate_target('wifi', f'wifi:{address}:{port}')
                candidates.append(address)
            except ValueError:
                pass
        host = select_target(candidates, 'LAN IPv4 address')
    handler = partial(handle_connection, authorization=authorization,
                      paste_action=paste_action, unlocked=unlocked)
    async with serve(handler, host, port, max_size=MAX_FRAME_BYTES,
                     max_queue=1, compression=None) as server:
        bound_port = server.sockets[0].getsockname()[1]
        authorization.set_target('wifi', f'wifi:{host}:{bound_port}')
        print(f'Windows Receiver listening at ws://{host}:{bound_port}/ws')
        print('WARNING: ws:// is NOT encrypted; use only a trusted private home LAN.')
        print('Focus a text field. Clipboard is replaced; no Enter or automatic resend.')
        display(authorization.new_invitation('wifi'))
        with console_session(authorization, 'wifi', display, console):
            await asyncio.Future()


def main() -> None:
    from authorization import Authorization
    parser = argparse.ArgumentParser(description='QR invitation and durable single-phone authorization.')
    parser.add_argument('--port', type=int, default=8765, help='WebSocket port (default: 8765)')
    parser.add_argument('--bluetooth', action='store_true', help='Use secure RFCOMM on a physical radio')
    parser.add_argument('--forget-bluetooth-device', action='store_true',
                        help='Legacy flag: revoke ALL Wi-Fi and Bluetooth application authorization and exit')
    arguments = parser.parse_args()
    if not 1 <= arguments.port <= 65535:
        parser.error('--port must be between 1 and 65535')
    directory = os.environ.get('LOCALAPPDATA')
    if not directory or not Path(directory).is_absolute():
        parser.exit(1, 'LOCALAPPDATA is unavailable; refusing insecure authorization storage.\n')
    authorization = None
    try:
        authorization = Authorization(Path(directory) / 'TalkToAgent' / 'receiver')
        if arguments.forget_bluetooth_device:
            authorization.revoke()
            print('All Wi-Fi and Bluetooth application authorization revoked (not system pairing).')
            return
        paste_action = WindowsPasteAction()
        if arguments.bluetooth:
            from bluetooth import run_bluetooth
            run_bluetooth(authorization, paste_action.paste)
        else:
            asyncio.run(run_receiver(arguments.port, authorization, paste_action))
    except KeyboardInterrupt:
        print('Receiver stopped.')
    except Exception:
        parser.exit(1, 'Receiver operation failed; no authorization or revocation success confirmed.\n')
    finally:
        if authorization is not None:
            authorization.close()


if __name__ == "__main__":
    main()

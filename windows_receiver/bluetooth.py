"""Fail-closed Windows RFCOMM transport and framed Bluetooth protocol."""
import hmac
import json
import socket
import struct
import sys
import ctypes
import uuid
from contextlib import contextmanager
from ctypes import wintypes

from receiver import MAX_FRAME_BYTES, _valid_final_text, _reject_duplicate_keys

SERVICE_UUID = '9c8f8513-7d4d-4a70-82c7-11e1da28a041'


def _read_exact(stream, size):
    chunks = []
    remaining = size
    while remaining:
        chunk = stream.read(remaining)
        if not chunk:
            break
        chunks.append(chunk)
        remaining -= len(chunk)
    return b''.join(chunks)


def read_message(stream):
    header = _read_exact(stream, 4)
    if len(header) != 4:
        raise EOFError('incomplete frame header')
    size = int.from_bytes(header, 'big')
    if not 0 < size <= MAX_FRAME_BYTES:
        raise ValueError('invalid frame size')
    data = _read_exact(stream, size)
    if len(data) != size:
        raise EOFError('incomplete frame')
    try:
        message = json.loads(data.decode('utf-8', 'strict'), object_pairs_hook=_reject_duplicate_keys)
    except (UnicodeError, ValueError) as exc:
        raise ValueError('invalid message') from exc
    if not isinstance(message, dict):
        raise ValueError('invalid message')
    return message


def write_message(stream, message):
    data = json.dumps(message, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    if len(data) > MAX_FRAME_BYTES:
        raise ValueError('invalid frame size')
    stream.write(len(data).to_bytes(4, 'big') + data)
    stream.flush()


class BluetoothSession:
    """One authorized connection; operation IDs are scoped to this connection."""

    def __init__(self, device, authorized, unlocked, paste):
        self.device = device
        self.authorized = authorized
        self.unlocked = unlocked
        self.paste = paste
        self.seen = set()

    def receive(self, message):
        operation = message.get('id') if isinstance(message, dict) else None
        response = {'type': 'error'}
        if isinstance(operation, str) and 0 < len(operation) <= 128 and operation.isascii():
            response['id'] = operation
        else:
            response['code'] = 'invalid_message'
            return response
        if operation in self.seen:
            response['code'] = 'duplicate_operation'
            return response
        self.seen.add(operation)  # No retry after ambiguous paste outcomes.
        if not self.authorized(self.device):
            response['code'] = 'unauthorized'
        elif (set(message) != {'type', 'id', 'text'} or message['type'] != 'final_text'
              or not _valid_final_text(message['text'])):
            response['code'] = 'invalid_message'
        elif not self.unlocked():
            response['code'] = 'session_locked'
        else:
            try:
                self.paste(message['text'])
            except Exception:
                response['code'] = 'paste_failed'
            else:
                return {'type': 'pasted', 'id': operation}
        return response


def authenticate(message, expected_code):
    if (not isinstance(message, dict) or set(message) != {'type', 'pairingCode'}
            or message['type'] != 'authenticate'
            or not isinstance(message['pairingCode'], str)):
        return {'type': 'error', 'code': 'invalid_message'}
    code = message['pairingCode']
    if not code.isascii() or not hmac.compare_digest(code, expected_code):
        return {'type': 'authentication_failed'}
    return {'type': 'authenticated'}


# ws2bth.h: optname is signed because CPython converts it to a C int.
SOL_RFCOMM = 3
SO_BTH_AUTHENTICATE = -2147483647
SO_BTH_ENCRYPT = 2


def session_unlocked():
    """Fail closed if this process cannot query the interactive desktop."""
    if sys.platform != 'win32':
        return False
    try:
        user32 = ctypes.WinDLL('user32', use_last_error=True)
        user32.OpenInputDesktop.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        user32.OpenInputDesktop.restype = wintypes.HANDLE
        user32.CloseDesktop.argtypes = [wintypes.HANDLE]
        user32.CloseDesktop.restype = wintypes.BOOL
        user32.GetThreadDesktop.argtypes = [wintypes.DWORD]
        user32.GetThreadDesktop.restype = wintypes.HANDLE
        user32.GetUserObjectInformationW.argtypes = [wintypes.HANDLE, ctypes.c_int,
            ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(wintypes.DWORD)]
        user32.GetUserObjectInformationW.restype = wintypes.BOOL
        kernel32 = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel32.GetCurrentThreadId.restype = wintypes.DWORD
        desktop = user32.OpenInputDesktop(0, False, 0x0001)  # DESKTOP_READOBJECTS
        if not desktop:
            return False
        try:
            current = user32.GetThreadDesktop(kernel32.GetCurrentThreadId())
            # Compare desktop names, not handles (separate opens have distinct handles).
            def name(handle):
                buffer = ctypes.create_unicode_buffer(256)
                needed = wintypes.DWORD()
                if not user32.GetUserObjectInformationW(handle, 2, buffer,
                        ctypes.sizeof(buffer), ctypes.byref(needed)):
                    return None
                return buffer.value
            return name(desktop) is not None and name(desktop) == name(current)
        finally:
            user32.CloseDesktop(desktop)
    except (OSError, AttributeError):
        return False


def serve_client(connection, address, pairing_code, paste, unlocked=session_unlocked):
    """One authenticated connection. An ID is never retried on this connection."""
    connection.settimeout(10)
    with connection.makefile('rwb', buffering=0) as stream:
        try:
            answer = authenticate(read_message(stream), pairing_code)
            write_message(stream, answer)
            if answer['type'] != 'authenticated':
                return
            connection.settimeout(60)
            session = BluetoothSession(address, lambda _: True, unlocked, paste)
            while True:
                write_message(stream, session.receive(read_message(stream)))
        except (EOFError, OSError, ValueError):
            return


def open_listener(socket_factory=socket.socket):
    """Bind with mandatory link security; never downgrade on option failure."""
    if sys.platform != 'win32':
        raise OSError('Windows Bluetooth RFCOMM is required')
    listener = socket_factory(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
    try:
        for option in (SO_BTH_AUTHENTICATE, SO_BTH_ENCRYPT):
            listener.setsockopt(SOL_RFCOMM, option, struct.pack('I', 1))
        listener.bind(('00:00:00:00:00:00', 0))
        return listener
    except BaseException:
        listener.close()
        raise


# Winsock2.h / ws2bth.h: use native alignment (in particular, 8-byte pointers
# and BTH_ADDR on x64). No raw SDP blob: NS_BTH builds the record from CSADDR_INFO.
class GUID(ctypes.Structure):
    _fields_ = [('Data1', wintypes.DWORD), ('Data2', wintypes.WORD),
                ('Data3', wintypes.WORD), ('Data4', ctypes.c_ubyte * 8)]

    def as_uuid(self):
        return uuid.UUID(bytes_le=bytes(self))


class SOCKADDR_BTH(ctypes.Structure):
    _fields_ = [('addressFamily', wintypes.WORD), ('btAddr', ctypes.c_uint64),
                ('serviceClassId', GUID), ('port', wintypes.DWORD)]


class SOCKET_ADDRESS(ctypes.Structure):
    _fields_ = [('lpSockaddr', ctypes.POINTER(SOCKADDR_BTH)),
                ('iSockaddrLength', ctypes.c_int)]


class CSADDR_INFO(ctypes.Structure):
    _fields_ = [('LocalAddr', SOCKET_ADDRESS), ('RemoteAddr', SOCKET_ADDRESS),
                ('iSocketType', ctypes.c_int), ('iProtocol', ctypes.c_int)]


class WSAQUERYSETW(ctypes.Structure):
    _fields_ = [('dwSize', wintypes.DWORD),
                ('lpszServiceInstanceName', ctypes.c_wchar_p),
                ('lpServiceClassId', ctypes.POINTER(GUID)),
                ('lpVersion', ctypes.c_void_p), ('lpszComment', ctypes.c_wchar_p),
                ('dwNameSpace', wintypes.DWORD), ('lpNSProviderId', ctypes.c_void_p),
                ('lpszContext', ctypes.c_wchar_p),
                ('dwNumberOfProtocols', wintypes.DWORD),
                ('lpafpProtocols', ctypes.c_void_p),
                ('lpszQueryString', ctypes.c_wchar_p),
                ('dwNumberOfCsAddrs', wintypes.DWORD),
                ('lpcsaBuffer', ctypes.POINTER(CSADDR_INFO)),
                ('dwOutputFlags', wintypes.DWORD), ('lpBlob', ctypes.c_void_p)]


@contextmanager
def register_rfcomm_service(listener, service_uuid, wsa_set_service=None,
                            wsa_get_last_error=None):
    """Publish the bound RFCOMM endpoint; always remove the record on exit."""
    if sys.platform != 'win32':
        raise OSError('Windows Bluetooth SDP is required')
    address, channel = listener.getsockname()
    if not isinstance(channel, int) or not 1 <= channel <= 30:
        raise ValueError('RFCOMM listener has no assigned channel')
    bt_addr = int(address.replace(':', ''), 16)
    if not 0 <= bt_addr < 1 << 48:
        raise ValueError('invalid Bluetooth address')
    if wsa_set_service is None:
        ws2 = ctypes.WinDLL('ws2_32')
        wsa_set_service = ws2.WSASetServiceW
        wsa_set_service.argtypes = [ctypes.POINTER(WSAQUERYSETW), ctypes.c_int,
                                    wintypes.DWORD]
        wsa_set_service.restype = ctypes.c_int
        wsa_get_last_error = ws2.WSAGetLastError
        wsa_get_last_error.argtypes = []
        wsa_get_last_error.restype = ctypes.c_int

    service_id = GUID.from_buffer_copy(uuid.UUID(service_uuid).bytes_le)
    sockaddr = SOCKADDR_BTH(socket.AF_BLUETOOTH, bt_addr, GUID(), channel)
    csaddr = CSADDR_INFO(SOCKET_ADDRESS(ctypes.pointer(sockaddr), ctypes.sizeof(sockaddr)),
                         SOCKET_ADDRESS(), socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
    query = WSAQUERYSETW()
    query.dwSize = ctypes.sizeof(query)
    query.lpszServiceInstanceName = 'TalkToAgent'
    query.lpServiceClassId = ctypes.pointer(service_id)
    query.dwNameSpace = 16  # NS_BTH
    query.dwNumberOfCsAddrs = 1
    query.lpcsaBuffer = ctypes.pointer(csaddr)

    def set_service(operation):
        if wsa_set_service(ctypes.byref(query), operation, 0) != 0:
            error = wsa_get_last_error() if wsa_get_last_error else 0
            raise OSError(error, f'WSASetServiceW operation {operation} failed')

    set_service(0)  # RNRSERVICE_REGISTER
    try:
        yield
    finally:
        set_service(2)  # RNRSERVICE_DELETE; DEREGISTER is invalid for Bluetooth.


def run_bluetooth(pairing_code, paste, listener_factory=open_listener,
                  register_service=register_rfcomm_service):
    """Register SDP before accepting; registration failure closes the listener."""
    with listener_factory() as listener:
        listener.listen(1)
        with register_service(listener, SERVICE_UUID):
            print('Bluetooth RFCOMM listening; temporary pairing code:', pairing_code)
            print('Stop with Ctrl+C to revoke the code and close the service.')
            while True:
                connection, address = listener.accept()
                with connection:
                    serve_client(connection, address, pairing_code, paste)

"""Fail-closed Windows RFCOMM transport and framed Bluetooth protocol."""
import json
import threading
import socket
import struct
import sys
import ctypes
import uuid
from contextlib import contextmanager
from ctypes import wintypes

from authorization import MAX_FRAME_BYTES, Rejected, parse_message

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
        return parse_message(data.decode('utf-8', 'strict'))
    except (UnicodeError, Rejected) as exc:
        raise ValueError('invalid message') from exc


def write_message(stream, message):
    data = json.dumps(message, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    if len(data) > MAX_FRAME_BYTES:
        raise ValueError('invalid frame size')
    stream.write(len(data).to_bytes(4, 'big') + data)
    stream.flush()


# ws2bth.h: optname is signed because CPython converts it to a C int.
SOL_RFCOMM = 3
SO_BTH_AUTHENTICATE = -2147483647
SO_BTH_ENCRYPT = 2
BT_PORT_ANY = 0xffffffff  # Windows reserves a server channel; port 0 is client-only.


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


def serve_client(connection, authorization, paste, unlocked=session_unlocked):
    """Shared handshake and durable gate; no MAC pin or console approval."""
    token = object()
    def close():
        try:
            connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        connection.close()
    try:
        connection.settimeout(10)
        with connection.makefile('rwb', buffering=0) as stream:
            try:
                authorization.register(token, close)
                pending, challenge = authorization.challenge(read_message(stream), 'bt')
                write_message(stream, challenge)
                session, answer = authorization.authorize(pending, read_message(stream))
                write_message(stream, answer)
                connection.settimeout(None)  # Idle time doesn't revoke a session.
                while True:
                    response = session.receive(read_message(stream), paste, unlocked)
                    write_message(stream, response)
            except Rejected as exc:
                write_message(stream, dict(v=1, type='error', code=exc.code))
            except ValueError:
                write_message(stream, dict(v=1, type='error', code='invalid_message'))
            except (EOFError, OSError):
                pass
            except Exception:
                write_message(stream, dict(v=1, type='error', code='internal_error'))
    except (EOFError, OSError):
        pass
    finally:
        authorization.unregister(token)
        close()


def select_radio(radios, requested=None):
    """Never choose an arbitrary adapter when several are installed."""
    if requested is not None:
        if requested not in {address for address, _ in radios}:
            raise ValueError('requested radio is unavailable')
        return requested
    if len(radios) != 1:
        raise ValueError('select exactly one available physical radio')
    return radios[0][0]


def enumerate_radios():
    """BluetoothFindFirst/NextRadio + GetRadioInfo, closing both handle types.

    ABI: https://learn.microsoft.com/windows/win32/api/bluetoothapis/ns-bluetoothapis-bluetooth_radio_info
    """
    if sys.platform != 'win32':
        raise OSError('Windows Bluetooth is required')
    class Params(ctypes.Structure):
        _fields_ = [('dwSize', wintypes.DWORD)]
    class Info(ctypes.Structure):
        _fields_ = [('dwSize', wintypes.DWORD), ('address', ctypes.c_uint64),
                    ('name', wintypes.WCHAR * 248), ('deviceClass', wintypes.ULONG),
                    ('subversion', wintypes.USHORT), ('manufacturer', wintypes.USHORT)]
    api = ctypes.WinDLL('bthprops.cpl', use_last_error=True)
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    api.BluetoothFindFirstRadio.argtypes = [ctypes.POINTER(Params), ctypes.POINTER(wintypes.HANDLE)]
    api.BluetoothFindFirstRadio.restype = wintypes.HANDLE
    api.BluetoothFindNextRadio.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.HANDLE)]
    api.BluetoothFindNextRadio.restype = wintypes.BOOL
    api.BluetoothGetRadioInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(Info)]
    api.BluetoothGetRadioInfo.restype = wintypes.DWORD
    api.BluetoothFindRadioClose.argtypes = [wintypes.HANDLE]
    api.BluetoothFindRadioClose.restype = wintypes.BOOL
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel.CloseHandle.restype = wintypes.BOOL
    handle = wintypes.HANDLE()
    params = Params(ctypes.sizeof(Params))
    enumeration = api.BluetoothFindFirstRadio(ctypes.byref(params), ctypes.byref(handle))
    if not enumeration:
        error = ctypes.get_last_error()
        if error == 259:  # ERROR_NO_MORE_ITEMS
            return []
        raise OSError(error, 'radio enumeration failed')
    radios = []
    try:
        while True:
            try:
                info = Info()
                info.dwSize = ctypes.sizeof(Info)
                error = api.BluetoothGetRadioInfo(handle, ctypes.byref(info))
                if error:
                    raise OSError(error, 'radio information failed')
                address = f'{info.address:012X}'
                from invitation import validate_target
                validate_target('bt', 'bt:' + address)
                radios.append((address, info.name))
            finally:
                kernel.CloseHandle(handle)
            if not api.BluetoothFindNextRadio(enumeration, ctypes.byref(handle)):
                error = ctypes.get_last_error()
                if error != 259:
                    raise OSError(error, 'radio enumeration failed')
                break
    finally:
        api.BluetoothFindRadioClose(enumeration)
    return sorted(set(radios))


def open_listener(radio, socket_factory=socket.socket):
    """Bind with mandatory link security; never downgrade on option failure."""
    if sys.platform != 'win32':
        raise OSError('Windows Bluetooth RFCOMM is required')
    from invitation import validate_target
    validate_target('bt', 'bt:' + radio)
    address = ':'.join(radio[i:i+2] for i in range(0, 12, 2))
    listener = socket_factory(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
    try:
        for option in (SO_BTH_AUTHENTICATE, SO_BTH_ENCRYPT):
            listener.setsockopt(SOL_RFCOMM, option, struct.pack('I', 1))
        listener.bind((address, BT_PORT_ANY))
        bound, channel = listener.getsockname()
        if bound.replace(':', '').upper() != radio or type(channel) is not int or not 1 <= channel <= 30:
            raise OSError('RFCOMM bound endpoint does not match selected radio')
        return listener
    except BaseException:
        listener.close()
        raise


# Winsock2.h uses native alignment for pointer-bearing WSAQUERYSETW / CSADDR_INFO.
# ws2bth.h packs SOCKADDR_BTH to 1-byte alignment (30 bytes on Windows).
# NS_BTH builds the SDP record from CSADDR_INFO; no raw SDP blob is required.
class GUID(ctypes.Structure):
    _fields_ = [('Data1', wintypes.DWORD), ('Data2', wintypes.WORD),
                ('Data3', wintypes.WORD), ('Data4', ctypes.c_ubyte * 8)]

    def as_uuid(self):
        return uuid.UUID(bytes_le=bytes(self))


class SOCKADDR_BTH(ctypes.Structure):
    _pack_ = 1
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
    if not 0 < bt_addr < (1 << 48) - 1:
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


def run_bluetooth(authorization, paste, listener_factory=open_listener,
                  register_service=register_rfcomm_service, radios=None,
                  display=None, console=True):
    """Invitation, listener and SDP share one explicitly selected physical radio."""
    from receiver import select_target, display_invitation, console_session
    from invitation import validate_target
    if radios is None:
        radios = enumerate_radios()
    for address, name in radios:
        validate_target('bt', 'bt:' + address)
    selected = select_target(radios, 'physical Bluetooth radio')
    radio = select_radio(radios, selected[0])
    display = display or display_invitation
    workers = []
    clients = set()
    clients_lock = threading.Lock()
    def work(connection):
        try:
            serve_client(connection, authorization, paste)
        finally:
            with clients_lock:
                clients.discard(connection)
    with listener_factory(radio) as listener:
        listener.listen(8)
        with register_service(listener, SERVICE_UUID):
            authorization.set_target('bt', 'bt:' + radio)
            print(f'Secure Bluetooth RFCOMM listening on {radio}.')
            display(authorization.new_invitation('bt'))
            try:
                with console_session(authorization, 'bt', display, console):
                    while True:
                        connection, _ = listener.accept()
                        with clients_lock:
                            clients.add(connection)
                        worker = threading.Thread(target=work, args=(connection,), daemon=True)
                        workers = [thread for thread in workers if thread.is_alive()]
                        workers.append(worker)
                        worker.start()
            finally:
                with clients_lock:
                    for connection in list(clients):
                        try:
                            connection.shutdown(socket.SHUT_RDWR)
                        except OSError:
                            pass
                        connection.close()
                for worker in workers:
                    worker.join(11)

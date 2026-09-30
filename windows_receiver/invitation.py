"""Strict protocol-v1 invitation URIs and standard (not Micro) terminal QR."""
import ipaddress
import os
import re
import sys


def validate_target(channel, target):
    if not isinstance(target, str):
        raise ValueError('invalid target')
    if channel == 'wifi':
        parts = target.split(':')
        if len(parts) != 3 or parts[0] != 'wifi':
            raise ValueError('invalid target')
        address = ipaddress.IPv4Address(parts[1])
        if str(address) != parts[1] or address.is_unspecified or address.is_multicast or str(address) == '255.255.255.255':
            raise ValueError('invalid IPv4')
        if not re.fullmatch(r'[1-9][0-9]{0,4}', parts[2]) or int(parts[2]) > 65535:
            raise ValueError('invalid port')
    elif channel == 'bt':
        if not re.fullmatch(r'bt:[0-9A-F]{12}', target) or target[3:] in ('000000000000', 'FFFFFFFFFFFF'):
            raise ValueError('invalid radio')
    else:
        raise ValueError('invalid channel')


def invitation_fields(target, identifier, secret, fingerprint):
    channel = target.split(':')[0]
    validate_target(channel, target)
    values = dict(c=channel, i=identifier, k=secret, r=fingerprint)
    if channel == 'wifi':
        _, host, port = target.split(':')
        values.update(h=host, p=port)
    else:
        values['a'] = target[3:]
    return values


def target_for(values):
    if values.get('c') == 'wifi':
        return f"wifi:{values['h']}:{values['p']}"
    if values.get('c') == 'bt':
        return f"bt:{values['a']}"
    raise ValueError('invalid channel')


def parse_uri(uri):
    from authorization import Rejected, unb64
    if (not isinstance(uri, str) or not uri.isascii() or len(uri) > 1024 or
            any(c.isspace() or ord(c) < 33 or ord(c) == 127 for c in uri) or
            '%' in uri or '#' in uri or not uri.startswith('talktoagent://invite/1?')):
        raise ValueError('invalid invitation URI')
    values = {}
    for item in uri[len('talktoagent://invite/1?'):].split('&'):
        parts = item.split('=')
        if len(parts) != 2 or not parts[0] or not parts[1] or parts[0] in values:
            raise ValueError('invalid query')
        values[parts[0]] = parts[1]
    required = {'c', 'i', 'k', 'r'}
    if values.get('c') == 'wifi':
        required.update(('h', 'p'))
    elif values.get('c') == 'bt':
        required.add('a')
    else:
        raise ValueError('invalid channel')
    if set(values) != required:
        raise ValueError('invalid fields')
    try:
        for field, size in (('i', 16), ('k', 32), ('r', 32)):
            unb64(values[field], size)
    except Rejected:
        raise ValueError('invalid binary field') from None
    validate_target(values['c'], target_for(values))
    return values


def to_uri(values):
    fields = ['c', 'h', 'p', 'i', 'k', 'r'] if values.get('c') == 'wifi' else ['c', 'a', 'i', 'k', 'r']
    if set(values) != set(fields) or any(not isinstance(v, str) for v in values.values()):
        raise ValueError('invalid fields')
    uri = 'talktoagent://invite/1?' + '&'.join(f'{k}={values[k]}' for k in fields)
    parse_uri(uri)
    return uri


def show_terminal(values, out=None):
    import segno
    uri = to_uri(values)
    terminal_out = out  # Preserve Segno's native Windows console path for default stdout.
    out = sys.stdout if out is None else out
    if not out.isatty():
        raise OSError('QR output requires an interactive terminal. Remove output redirection/logging and restart.')
    qr = segno.make(uri, micro=False, encoding='ascii')
    width, height = qr.symbol_size(border=4)
    # Full-size terminal encoding uses two columns per module, one row per module.
    # Keep one spare column so the final module never wraps on auto-wrap terminals.
    required = (width * 2 + 1, height + 5)
    try:
        size = os.get_terminal_size(out.fileno())
    except (OSError, ValueError):
        raise OSError('Cannot measure terminal. Use an interactive Windows terminal and restart.') from None
    if size.columns < required[0] or size.lines < required[1]:
        raise OSError(f'Terminal too small: need at least {required[0]} columns and {required[1]} rows. '
                      'Maximize terminal or reduce font size, then restart; if already running, use show '
                      '(does not extend expiry).')
    # No URI/manual fallback, image file or redirected secret output.
    qr.terminal(out=terminal_out, compact=False, border=4)

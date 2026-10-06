"""Strict fake external tools, executed as real native processes. No ADB import."""
import json
import os
import pathlib
import sys

workspace = pathlib.Path(sys.argv[1])
tool, *argv = sys.argv[2:]
log = workspace / 'native-argv.jsonl'
with log.open('a', encoding='utf-8') as stream:
    stream.write(json.dumps([tool, argv], ensure_ascii=False) + '\n')
if os.environ.get('SAFE_UPDATE_TEST_FAIL_TOOL') == tool:
    print('Success')  # A success-looking message must not hide a native error exit.
    print('simulated native failure', file=sys.stderr)
    sys.exit(7)
package = 'com.example.talktoagent'
if tool == 'adb':
    if argv == ['devices', '-l']:
        print('List of devices attached\nfake-serial device')
    else:
        assert argv[:2] == ['-s', 'fake-serial'], argv
        args = argv[2:]
        replies = {
            ('get-state',): 'device',
            ('shell', 'am', 'get-current-user'): '10',
            ('shell', 'pm', 'list', 'users'): 'Users:\nUserInfo{10:Work:30} running',
            ('shell', 'pm', 'list', 'packages', '--user', '10'): 'package:' + package,
            ('shell', 'getprop', 'ro.build.version.sdk'): '36',
            ('shell', 'pm', 'path', '--user', '10', package): 'package:/data/app/fake/base.apk',
            ('shell', 'am', 'start', '--user', '10', '-W', '-n', package + '/com.example.talktoagent.MainActivity'): 'Status: ok',
        }
        if tuple(args) in replies:
            print(replies[tuple(args)])
        elif args[:2] == ['pull', '/data/app/fake/base.apk'] and len(args) == 3:
            pathlib.Path(args[2]).write_bytes(b'new' if (workspace / 'installed').exists() else b'old')
            print('1 file pulled')
        elif args[:4] == ['install', '--user', '10', '-r'] and len(args) == 5:
            assert pathlib.Path(args[4]).read_bytes() == b'new'
            (workspace / 'installed').touch()
            print('Success')
        else:
            raise AssertionError('Unexpected command: ' + repr(argv))
elif tool == 'gradle':
    assert argv[0] == '--init-script'
    assert '-PbluetoothTestApp=false' in argv
    # Respect the actual isolated output communicated through the init script.
    init = pathlib.Path(argv[1]).read_text(encoding='utf-8')
    assert str(workspace).replace('\\', '/') + '/build' in init, (str(workspace), init)
    output = workspace / 'build/app/outputs/apk/debug/app-debug.apk'
    output.parent.mkdir(parents=True)
    output.write_bytes(b'new')
elif tool == 'aapt':
    assert argv[:2] == ['dump', 'badging'] and len(argv) == 3
    assert pathlib.Path(argv[2]).exists()
    print("package: name='com.example.talktoagent' versionCode='1' versionName='1.0'\nsdkVersion:'36'")
elif tool == 'signer':
    assert argv[:3] == ['verify', '--verbose', '--print-certs'] and len(argv) == 4
    assert pathlib.Path(argv[3]).exists()
    print('Verifies\nNumber of signers: 1\nSigner #1 certificate SHA-256 digest: ' + 'a' * 64)
else:
    raise AssertionError('Unexpected external tool: ' + tool)

r"""Actual Android/JCA coordinator ↔ Python/cryptography over a real local WebSocket.

Run after Gradle has resolved Gson:
  .\windows_receiver\.venv\Scripts\python.exe -m unittest discover -s tests -v
JVM repository and desktop paste are system-boundary fakes, not hardware evidence.
"""
import asyncio
from functools import partial
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'windows_receiver'))
from authorization import Authorization
from invitation import to_uri
from receiver import handle_connection
from websockets.asyncio.client import connect
from websockets.asyncio.server import serve


class AndroidReceiverInteropTests(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def setUpClass(cls):
        java_home = os.environ.get('JAVA_HOME')
        cls.java = str(Path(java_home) / 'bin' / 'java.exe') if java_home else shutil.which('java')
        javac = str(Path(java_home) / 'bin' / 'javac.exe') if java_home else shutil.which('javac')
        gson_root = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1/com.google.code.gson/gson'
        catalog = (ROOT / 'gradle/libs.versions.toml').read_text(encoding='utf-8')
        version = re.search(r'^gson\s*=\s*"([^"]+)"', catalog, re.MULTILINE).group(1)
        jars = sorted(gson_root.glob(f'{version}/*/gson-{version}.jar'))
        if not cls.java or not javac or not jars:
            raise RuntimeError('Interop tests require a JDK and Gson resolved by Gradle; run :app:testDebugUnitTest first')
        cls.compiled = tempfile.TemporaryDirectory(prefix='talktoagent-java-interop-')
        source = ROOT / 'app/src/main/java/com/example/talktoagent'
        names = ['Invitation', 'StrictJson', 'AuthorizationRepository', 'AuthorizationProtocol', 'ConnectionCoordinator', 'ManualTextProtocol']
        cls.classpath = os.pathsep.join([cls.compiled.name, str(jars[-1])])
        subprocess.run([javac, '-encoding', 'UTF-8', '-cp', str(jars[-1]), '-d', cls.compiled.name,
                        *(str(source / f'{name}.java') for name in names),
                        str(ROOT / 'tests/interop/AndroidProtocolClient.java')], check=True, capture_output=True, timeout=60)

    @classmethod
    def tearDownClass(cls):
        cls.compiled.cleanup()

    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='talktoagent-interop-')
        self.authorization = Authorization(Path(self.directory.name))
        self.pasted = []
        class Paste:
            def paste(_, text):
                self.pasted.append(text)
        self.server = await serve(partial(handle_connection, authorization=self.authorization,
                                         paste_action=Paste(), unlocked=lambda: True), '127.0.0.1', 0)
        port = self.server.sockets[0].getsockname()[1]
        self.url = f'ws://127.0.0.1:{port}/ws'
        self.authorization.set_target('wifi', f'wifi:127.0.0.1:{port}')
        self.client = None

    async def asyncTearDown(self):
        if self.client is not None:
            if self.client.poll() is None:
                self.client.kill()
            await asyncio.to_thread(self.client.wait, 5)
            self.client.stdin.close()
            self.client.stdout.close()
            self.client.stderr.close()
        self.server.close()
        await asyncio.wait_for(self.server.wait_closed(), 5)
        self.authorization.close()
        self.directory.cleanup()

    async def read(self):
        line = await asyncio.wait_for(asyncio.to_thread(self.client.stdout.readline), 10)
        if not line:
            details = await asyncio.wait_for(asyncio.to_thread(self.client.stderr.read), 2)
            self.fail('JVM client exited: ' + details.decode('utf-8', 'replace'))
        return line.decode('utf-8').rstrip('\r\n')

    async def write(self, line):
        self.client.stdin.write((line + '\n').encode('utf-8'))
        self.client.stdin.flush()

    async def exchange_authentication(self, websocket, forward_authorized=True):
        await websocket.send(await self.read())
        await self.write(await websocket.recv())
        await websocket.send(await self.read())
        authorized = await websocket.recv()
        self.assertEqual(json.loads(authorized)['type'], 'authorized')
        if forward_authorized:
            await self.write(authorized)

    async def run_flow(self, mode):
        self.client = subprocess.Popen([self.java, '-Dfile.encoding=UTF-8', '-cp', self.classpath,
            'com.example.talktoagent.AndroidProtocolClient', mode],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        await self.write(to_uri(self.authorization.new_invitation('wifi')))
        async with connect(self.url) as websocket:
            await self.exchange_authentication(websocket, forward_authorized=mode != 'lost')
            if mode != 'lost':
                await websocket.send(await self.read())
                await self.write(await websocket.recv())
        if mode == 'lost':
            # A committed authorization must resume without reusing the invitation secret.
            async with connect(self.url) as websocket:
                await self.exchange_authentication(websocket)
                await websocket.send(await self.read())
                await self.write(await websocket.recv())
        self.assertEqual(await self.read(), 'PASTED')
        async with connect(self.url) as websocket:
            await self.exchange_authentication(websocket)
            await websocket.send(await self.read())
            await self.write(await websocket.recv())
        self.assertEqual(await self.read(), 'RESUMED')
        self.assertEqual(await asyncio.to_thread(self.client.wait, 10), 0)
        self.assertEqual(self.pasted, ['繁體中文 Mixed English：只貼一次', '原手機重連，不補送舊文字'])

    async def test_android_scanned_invitation_and_resume_paste_only_explicit_finals(self):
        await self.run_flow('normal')

    async def test_android_recovers_committed_authorization_after_reply_is_lost(self):
        await self.run_flow('lost')

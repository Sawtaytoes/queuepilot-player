"""Shared paths and helpers for the QueuePilot Test environment.

The supervisor and the command-line tool both import this module. Neither one
reaches outside this container: the emulator console and ADB listen on loopback,
and the supervisor's internal API is a Unix socket.
"""

import asyncio
import hashlib
import json
import os
import re
import time
from pathlib import Path

STATE = Path(os.environ.get('QPT_STATE', '/state'))
ARTIFACTS = Path(os.environ.get('QPT_ARTIFACTS', '/artifacts'))
FIXTURES = Path(os.environ.get('QPT_FIXTURES', '/fixtures'))
LOGS = STATE / 'logs'
AVD_HOME = STATE / 'avd'
HOME = STATE / 'home'
LOCK_PATH = STATE / 'test.lock'
SESSION_PATH = STATE / 'test-session.json'
PIN_PATH = STATE / 'pinned-apk.json'
SOCKET_PATH = Path(os.environ.get('QPT_SOCKET', '/run/queuepilot-test/control.sock'))

AVD_NAME = 'queuepilot-test'
SYSTEM_IMAGE = 'system-images;android-30;android-tv;x86'
SERIAL = 'emulator-5554'
CONSOLE_PORT = 5554
PACKAGE = os.environ.get('QPT_PACKAGE', 'dev.queuepilot.player.prototype')
ACTIVITY = os.environ.get('QPT_ACTIVITY', 'dev.queuepilot.player.MainActivity')
APK_PATTERN = re.compile(os.environ.get('QPT_RELEASE_PATTERN', r'^queuepilot-lab-(\d+(?:\.\d+)*)\.apk$'))
TEST_SNAPSHOT = 'queuepilot-test-owner-session'

ENVIRONMENT = {
    **os.environ,
    'HOME': str(HOME),
    'ANDROID_AVD_HOME': str(AVD_HOME),
    'ANDROID_EMULATOR_HOME': str(HOME / '.android'),
    'ANDROID_USER_HOME': str(HOME / '.android'),
}


def prepare_directories():
    for directory in (LOGS, AVD_HOME, HOME / '.android', SOCKET_PATH.parent):
        directory.mkdir(parents=True, exist_ok=True)
    # An empty token file turns off console authentication. The console listens on
    # loopback inside this container only.
    (HOME / '.emulator_console_auth_token').write_text('')


def file_sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as handle:
        for block in iter(lambda: handle.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def latest_release():
    """The highest-versioned release APK, or None when the directory has none."""
    best = None
    if not ARTIFACTS.is_dir():
        return None
    for path in ARTIFACTS.iterdir():
        match = APK_PATTERN.match(path.name)
        if not match:
            continue
        version = tuple(int(part) for part in match.group(1).split('.'))
        if best is None or version > best[0]:
            best = (version, path, match.group(1))
    if best is None:
        return None
    return {'path': str(best[1]), 'version': best[2]}


def read_json(path, default=None):
    try:
        return json.loads(Path(path).read_text())
    except (FileNotFoundError, ValueError):
        return default


def write_json(path, value):
    temporary = Path(str(path) + '.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.replace(path)


async def run(*command, timeout=120, check=True, env=None):
    """Run a command and return its decoded standard output."""
    process = await asyncio.create_subprocess_exec(
        *command,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
        env=env or ENVIRONMENT,
    )
    try:
        output, _ = await asyncio.wait_for(process.communicate(), timeout)
    except asyncio.TimeoutError:
        process.kill()
        await process.wait()
        raise RuntimeError(f'{command[0]} timed out after {timeout} s')
    text = output.decode(errors='replace')
    if check and process.returncode != 0:
        raise RuntimeError(f'{" ".join(command[:4])} failed ({process.returncode}): {text.strip()[-400:]}')
    return text


async def adb(*arguments, timeout=120, check=True):
    return await run('adb', '-s', SERIAL, *arguments, timeout=timeout, check=check)


async def installed_apk_sha256():
    listing = await adb('shell', 'pm', 'path', PACKAGE, check=False, timeout=30)
    paths = [line[len('package:'):].strip() for line in listing.splitlines() if line.startswith('package:')]
    base = next((path for path in paths if path.endswith('/base.apk')), None)
    if not base:
        return None
    output = await adb('shell', 'sha256sum', base, timeout=60)
    return output.split()[0]


async def installed_version_name():
    output = await adb('shell', 'dumpsys', 'package', PACKAGE, check=False, timeout=30)
    match = re.search(r'versionName=(\S+)', output)
    return match.group(1) if match else None


async def install_apk(path, allow_uninstall=False):
    """Install over the existing app, keeping its data. Never uninstall unless allowed."""
    output = await adb('install', '-r', '-d', str(path), timeout=300, check=False)
    if 'Success' in output:
        return output
    if 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' in output and allow_uninstall:
        await adb('uninstall', PACKAGE, timeout=120, check=False)
        output = await adb('install', str(path), timeout=300, check=False)
        if 'Success' in output:
            return output
    raise RuntimeError(f'APK install failed: {output.strip()[-400:]}')


class Console:
    """A minimal client for the emulator console on loopback."""

    def __init__(self, port=CONSOLE_PORT):
        self.port = port
        self.reader = None
        self.writer = None
        self.lock = asyncio.Lock()

    async def _connect(self):
        self.reader, self.writer = await asyncio.wait_for(
            asyncio.open_connection('127.0.0.1', self.port), 5)
        await self._read_reply(10)

    async def _read_reply(self, timeout):
        lines = []
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError('emulator console did not answer')
            line = await asyncio.wait_for(self.reader.readline(), remaining)
            if not line:
                raise ConnectionError('emulator console closed')
            text = line.decode(errors='replace').rstrip('\r\n')
            if text == 'OK':
                return lines
            if text.startswith('KO'):
                raise RuntimeError(f'emulator console refused: {text}')
            lines.append(text)

    async def command(self, line, timeout=15):
        async with self.lock:
            for attempt in (1, 2):
                try:
                    if self.writer is None:
                        await self._connect()
                    self.writer.write(line.encode() + b'\r\n')
                    await self.writer.drain()
                    return await self._read_reply(timeout)
                except (ConnectionError, OSError, asyncio.TimeoutError):
                    self.close()
                    if attempt == 2:
                        raise

    def close(self):
        if self.writer is not None:
            self.writer.close()
        self.reader = self.writer = None

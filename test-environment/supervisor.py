#!/usr/bin/env python3
"""QueuePilot Test supervisor.

This is the always-on part of the environment. It serves the browser page,
starts the Android TV emulator when the page asks for it, carries remote input
to the emulator console, stops the emulator after an idle period, and keeps the
owner's session intact while an automated test borrows the emulator.

It has no Docker socket and no TrueNAS access. It controls only its own child
processes: Xvfb, x11vnc, ADB and the emulator.
"""

import asyncio
import contextlib
import fcntl
import hashlib
import json
import os
import signal
import time
from pathlib import Path

from aiohttp import ClientSession, ClientTimeout, WSMsgType, web

import qptest
from qptest import Console, adb, read_json, write_json

WEB_ROOT = Path(os.environ.get('QPT_WEB_ROOT', Path(__file__).with_name('web')))
NOVNC_ROOT = Path(os.environ.get('QPT_NOVNC_ROOT', '/opt/novnc'))
PORT = int(os.environ.get('QPT_PORT', '8080'))
IDLE_SECONDS = int(float(os.environ.get('QPT_IDLE_MINUTES', '30')) * 60)
IDLE_WARNING_SECONDS = min(300, IDLE_SECONDS // 3)
BOOT_TIMEOUT_SECONDS = int(os.environ.get('QPT_BOOT_TIMEOUT', '600'))
DISPLAY_SIZE = os.environ.get('QPT_DISPLAY_SIZE', '1920x1080')
EMULATOR_MEMORY = os.environ.get('QPT_EMULATOR_MEMORY', '4096')
EMULATOR_CORES = os.environ.get('QPT_EMULATOR_CORES', '4')
AUTHZ_URL = os.environ.get('QPT_AUTHZ_URL', '')
PUBLIC_URL = os.environ.get('QPT_PUBLIC_URL', '').rstrip('/')
VNC_PORT = 5900
DISPLAY = ':0'

# Remote buttons reach the guest the way a desktop keyboard does: XTEST key events
# (xdotool) into the emulator window, which forwards them to the guest's virtual
# keyboard. The emulator's own shortcuts supply Back (Ctrl+Backspace) and Menu
# (Ctrl+M). This system image refuses `sendevent` from the shell user, and the
# console's `event send` does not reach it. Media keys have no X equivalent the
# emulator forwards, so they use `input keyevent` on a persistent ADB shell.
X_KEYS = {
    'up': 'Up', 'down': 'Down', 'left': 'Left', 'right': 'Right', 'ok': 'Return',
    'back': 'ctrl+BackSpace', 'home': 'Home', 'menu': 'ctrl+m', 'delete': 'BackSpace',
}
ANDROID_KEYS = {
    'playPause': 'KEYCODE_MEDIA_PLAY_PAUSE', 'rewind': 'KEYCODE_MEDIA_REWIND',
    'fastForward': 'KEYCODE_MEDIA_FAST_FORWARD',
}


def shell_quote(text):
    return "'" + text.replace("'", "'\\''") + "'"


class RemoteInput:
    """Carries remote presses and typed text to the emulator."""

    def __init__(self):
        self.shell = None
        self.is_focused = False
        self.lock = asyncio.Lock()

    async def _xdotool(self, *arguments):
        process = await asyncio.create_subprocess_exec(
            'xdotool', *arguments, env={**qptest.ENVIRONMENT, 'DISPLAY': DISPLAY},
            stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.PIPE)
        _, error = await process.communicate()
        if process.returncode != 0:
            raise RuntimeError(f'xdotool failed: {error.decode(errors="replace").strip()[-200:]}')

    async def focus(self):
        # No window manager runs, so keyboard focus follows the pointer.
        await self._xdotool('mousemove', '960', '540')
        self.is_focused = True

    async def _shell(self, line):
        if self.shell is None or self.shell.returncode is not None:
            self.shell = await asyncio.create_subprocess_exec(
                'adb', '-s', qptest.SERIAL, 'shell', stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL, env=qptest.ENVIRONMENT)
        self.shell.stdin.write(line.encode() + b'\n')
        await self.shell.stdin.drain()

    async def press(self, key):
        async with self.lock:
            if key in X_KEYS:
                if not self.is_focused:
                    await self.focus()
                await self._xdotool('key', '--clearmodifiers', X_KEYS[key])
            elif key in ANDROID_KEYS:
                await self._shell(f'input keyevent {ANDROID_KEYS[key]}')
            else:
                raise ValueError('unknown key')

    async def type_text(self, text):
        # `input text` covers punctuation that the emulator window does not
        # translate from X key events (a colon, for example).
        text = ''.join(character for character in text if 32 <= ord(character) < 127)[:200]
        if not text:
            return
        async with self.lock:
            await self._shell('input text ' + shell_quote(text.replace(' ', '%s')))

    def close(self):
        if self.shell is not None and self.shell.returncode is None:
            with contextlib.suppress(ProcessLookupError):
                self.shell.kill()
        self.shell = None
        self.is_focused = False


def now():
    return time.time()


def lock_is_held():
    """True while a test command holds the shared lock file."""
    qptest.LOCK_PATH.touch(exist_ok=True)
    with open(qptest.LOCK_PATH, 'a') as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return True
        fcntl.flock(handle, fcntl.LOCK_UN)
        return False


class Environment:
    def __init__(self):
        self.state = 'stopped'
        self.phase = ''
        self.error = None
        self.started_at = None
        self.ready_at = None
        self.last_activity = now()
        self.last_stop = read_json(qptest.STATE / 'last-stop.json')
        self.test = None
        self.app = {}
        self.processes = {}
        self.lifecycle = asyncio.Lock()
        self.console = Console()
        self.remote = RemoteInput()
        self.clients = set()
        self.start_task = None
        self.idle_seconds = IDLE_SECONDS
        self.is_display_ready = False
        self.stop_after_test = False

    # ----- status -------------------------------------------------------
    def status(self):
        idle_stop_at = None
        if self.state == 'running' and not self.test:
            idle_stop_at = self.last_activity + self.idle_seconds
        return {
            'type': 'status',
            'state': self.state,
            'phase': self.phase,
            'error': self.error,
            'startedAt': self.started_at,
            'readyAt': self.ready_at,
            'idleStopAt': idle_stop_at,
            'idleWarningSeconds': IDLE_WARNING_SECONDS,
            'idleMinutes': self.idle_seconds / 60,
            'serverTime': now(),
            'isDisplayReady': self.is_display_ready,
            'test': self.test,
            'app': self.app,
            'lastStop': self.last_stop,
        }

    async def broadcast(self, message=None):
        payload = json.dumps(message or self.status())
        for client in list(self.clients):
            with contextlib.suppress(Exception):
                await client.send_str(payload)

    def set_phase(self, state, phase):
        self.state = state
        self.phase = phase
        print(f'[{state}] {phase}', flush=True)
        asyncio.get_event_loop().create_task(self.broadcast())

    def touch(self):
        self.last_activity = now()

    # ----- lifecycle ----------------------------------------------------
    def request_start(self):
        self.touch()
        if self.state in ('running', 'starting'):
            return self.start_task
        if self.start_task is None or self.start_task.done():
            self.start_task = asyncio.get_event_loop().create_task(self.start())
        return self.start_task

    async def start(self):
        async with self.lifecycle:
            if self.state == 'running':
                return
            self.error = None
            self.started_at = now()
            self.ready_at = None
            try:
                await self._start()
            except Exception as error:  # noqa: BLE001 - reported to the page
                self.error = str(error)
                print(f'start failed: {error}', flush=True)
                await self._teardown()
                self.set_phase('error', 'Start failed')
                raise

    async def _start(self):
        qptest.prepare_directories()
        self.set_phase('starting', 'Preparing the Android TV device')
        await self._ensure_avd()
        self.set_phase('starting', 'Starting the display')
        await self._spawn('xvfb', ['Xvfb', DISPLAY, '-screen', '0', f'{DISPLAY_SIZE}x24', '-nolisten', 'tcp', '-nocursor'])
        for _ in range(100):
            if Path('/tmp/.X11-unix/X0').exists():
                break
            await asyncio.sleep(0.1)
        await self._spawn('x11vnc', [
            'x11vnc', '-display', DISPLAY, '-rfbport', str(VNC_PORT), '-localhost', '-forever', '-shared',
            '-nopw', '-viewonly', '-noxrecord', '-noxfixes', '-nowf', '-nocursor', '-wait', '10', '-defer', '10',
            '-quiet'])
        for _ in range(100):
            with contextlib.suppress(OSError):
                _, writer = await asyncio.open_connection('127.0.0.1', VNC_PORT)
                writer.close()
                self.is_display_ready = True
                break
            await asyncio.sleep(0.1)
        await qptest.run('adb', 'start-server', timeout=30)
        arguments = [
            'emulator', '-avd', qptest.AVD_NAME, '-port', str(qptest.CONSOLE_PORT), '-gpu', 'swiftshader_indirect',
            '-memory', EMULATOR_MEMORY, '-cores', EMULATOR_CORES, '-no-boot-anim', '-no-metrics', '-no-audio',
            '-skin', '1920x1080', '-fixed-scale']
        session = read_json(qptest.SESSION_PATH)
        if session and not lock_is_held():
            # A test ended without restoring the owner's session (for example, the
            # container restarted). Boot from the saved session instead.
            arguments += ['-snapshot', qptest.TEST_SNAPSHOT, '-no-snapshot-save']
            self.set_phase('starting', 'Restoring your session after an interrupted test')
        else:
            arguments += ['-no-snapshot']
            self.set_phase('starting', 'Starting Android')
        await self._spawn('emulator', arguments)
        deadline = time.monotonic() + BOOT_TIMEOUT_SECONDS
        while True:
            self._check_alive('emulator')
            devices = await qptest.run('adb', 'devices', timeout=15, check=False)
            if f'{qptest.SERIAL}\tdevice' in devices:
                break
            if time.monotonic() > deadline:
                raise RuntimeError('the emulator did not attach to ADB')
            await asyncio.sleep(1)
        self.set_phase('starting', 'Android is booting')
        while True:
            self._check_alive('emulator')
            booted = await adb('shell', 'getprop', 'sys.boot_completed', timeout=15, check=False)
            if booted.strip() == '1':
                break
            if time.monotonic() > deadline:
                raise RuntimeError('Android did not finish booting')
            await asyncio.sleep(2)
        if session and not lock_is_held():
            await self._finish_restore()
        self.set_phase('starting', 'Installing QueuePilot')
        await self.ensure_app()
        await adb('shell', 'am', 'start', '-n', f'{qptest.PACKAGE}/{qptest.ACTIVITY}', timeout=30, check=False)
        self.ready_at = now()
        self.touch()
        print(f'ready in {self.ready_at - self.started_at:.1f} s', flush=True)
        self.set_phase('running', 'Ready')
        asyncio.get_event_loop().create_task(self._watch_emulator())

    async def _ensure_avd(self):
        avd = qptest.AVD_HOME / f'{qptest.AVD_NAME}.avd'
        if not (qptest.AVD_HOME / f'{qptest.AVD_NAME}.ini').exists():
            await qptest.run('bash', '-c', f"printf 'no\\n' | avdmanager create avd --name {qptest.AVD_NAME} "
                             f"--package '{qptest.SYSTEM_IMAGE}' --device tv_1080p --force", timeout=300)
        config = avd / 'config.ini'
        values = dict(line.split('=', 1) for line in config.read_text().splitlines() if '=' in line)
        values.update({
            'hw.lcd.width': '1920', 'hw.lcd.height': '1080', 'hw.lcd.density': '240',
            'hw.initialOrientation': 'landscape', 'skin.name': '1920x1080', 'skin.path': '1920x1080',
            'hw.keyboard': 'yes', 'hw.ramSize': EMULATOR_MEMORY, 'hw.cpu.ncore': EMULATOR_CORES,
            'hw.gpu.enabled': 'yes', 'hw.gpu.mode': 'swiftshader_indirect', 'disk.dataPartition.size': '6G',
            'hw.audioInput': 'no',
        })
        config.write_text(''.join(f'{key}={value}\n' for key, value in values.items()))
        # Without a window manager the emulator window keeps its saved position.
        # Pin it to the origin so the 1:1 window fills the virtual display.
        user = avd / 'emulator-user.ini'
        settings = dict(line.split(' = ', 1) for line in user.read_text().splitlines()
                        if ' = ' in line) if user.exists() else {}
        settings.update({'window.x': '0', 'window.y': '0'})
        user.write_text(''.join(f'{key} = {value}\n' for key, value in settings.items()))

    async def _spawn(self, name, command):
        environment = {**qptest.ENVIRONMENT, 'DISPLAY': DISPLAY}
        log = open(qptest.LOGS / f'{name}.log', 'ab')
        log.write(f'\n--- {time.strftime("%Y-%m-%dT%H:%M:%S")} {" ".join(command)}\n'.encode())
        log.flush()
        process = await asyncio.create_subprocess_exec(
            *command, stdout=log, stderr=log, env=environment, start_new_session=True)
        log.close()
        self.processes[name] = process
        return process

    def _check_alive(self, name):
        process = self.processes.get(name)
        if process is None or process.returncode is not None:
            tail = (qptest.LOGS / f'{name}.log').read_text(errors='replace')[-600:]
            raise RuntimeError(f'{name} exited: {tail.strip()}')

    async def _watch_emulator(self):
        process = self.processes.get('emulator')
        if process is None:
            return
        await process.wait()
        if self.state == 'running' and not self.lifecycle.locked():
            async with self.lifecycle:
                self.error = 'The emulator exited unexpectedly'
                await self._teardown()
                self._record_stop('emulator exited')
                self.set_phase('stopped', 'Stopped')

    async def stop(self, reason):
        async with self.lifecycle:
            if self.state == 'stopped':
                return
            self.set_phase('stopping', 'Stopping Android')
            await self._teardown()
            self._record_stop(reason)
            self.set_phase('stopped', 'Stopped')

    def _record_stop(self, reason):
        self.last_stop = {'reason': reason, 'at': now()}
        write_json(qptest.STATE / 'last-stop.json', self.last_stop)

    async def _teardown(self):
        emulator = self.processes.get('emulator')
        if emulator and emulator.returncode is None:
            with contextlib.suppress(Exception):
                await self.console.command('kill', timeout=5)
            with contextlib.suppress(asyncio.TimeoutError):
                await asyncio.wait_for(emulator.wait(), 30)
        self.console.close()
        self.remote.close()
        self.is_display_ready = False
        for name in ('emulator', 'x11vnc', 'xvfb'):
            process = self.processes.pop(name, None)
            if process and process.returncode is None:
                with contextlib.suppress(ProcessLookupError):
                    os.killpg(process.pid, signal.SIGTERM)
                try:
                    await asyncio.wait_for(process.wait(), 10)
                except asyncio.TimeoutError:
                    with contextlib.suppress(ProcessLookupError):
                        os.killpg(process.pid, signal.SIGKILL)
                    await process.wait()
        await qptest.run('adb', 'kill-server', timeout=15, check=False)

    # ----- app ----------------------------------------------------------
    async def ensure_app(self):
        """Install the pinned build, or else the latest release, when it differs."""
        pin = read_json(qptest.PIN_PATH)
        target = None
        if pin and Path(pin.get('path', '')).is_file():
            target = {'path': pin['path'], 'version': pin.get('label', 'pinned build'), 'source': 'pinned'}
        else:
            release = qptest.latest_release()
            if release:
                target = {**release, 'source': 'release'}
        if target is None:
            self.app = {'warning': 'No release APK is available'}
            return
        wanted = qptest.file_sha256(target['path'])
        installed = await qptest.installed_apk_sha256()
        warning = None
        if installed != wanted:
            if self.state == 'starting':
                self.set_phase('starting', f'Installing QueuePilot {target["version"]}')
            try:
                await qptest.install_apk(target['path'])
            except RuntimeError as error:
                # Keep the installed app and its data; report instead of failing the start.
                warning = f'QueuePilot {target["version"]} was not installed: {error}'
                print(warning, flush=True)
        self.app = {
            'source': target['source'] if warning is None else 'installed',
            'version': target['version'],
            'versionName': await qptest.installed_version_name(),
            'sha256': wanted,
            'warning': warning,
        }

    # ----- tests --------------------------------------------------------
    async def begin_test(self, label):
        if not lock_is_held():
            raise web.HTTPConflict(text='Hold the test lock before beginning a test')
        if self.test:
            raise web.HTTPConflict(text='A test is already active')
        self.test = {'label': label[:120], 'startedAt': now(), 'status': 'starting'}
        # A test that had to start the emulator stops it again when it finishes.
        self.stop_after_test = self.state != 'running'
        await self.broadcast()
        try:
            task = self.request_start()
            if task is not None:
                await task
            if self.state != 'running':
                raise RuntimeError(self.error or 'The emulator is not running')
            self.test['status'] = 'saving'
            await self.broadcast()
            write_json(qptest.SESSION_PATH, {'label': self.test['label'], 'startedAt': self.test['startedAt'],
                                             'snapshot': qptest.TEST_SNAPSHOT})
            began = time.monotonic()
            await self.console.command(f'avd snapshot save {qptest.TEST_SNAPSHOT}', timeout=900)
            print(f'saved the session for "{self.test["label"]}" in {time.monotonic() - began:.1f} s', flush=True)
            self.test['status'] = 'running'
            await self.broadcast()
        except Exception:
            self.test = None
            with contextlib.suppress(FileNotFoundError):
                qptest.SESSION_PATH.unlink()
            await self.broadcast()
            raise

    async def end_test(self):
        if not self.test and not qptest.SESSION_PATH.exists():
            return
        if self.test:
            self.test['status'] = 'restoring'
        await self.broadcast()
        try:
            if self.state == 'running':
                began = time.monotonic()
                await self.console.command(f'avd snapshot load {qptest.TEST_SNAPSHOT}', timeout=900)
                print(f'restored the session in {time.monotonic() - began:.1f} s', flush=True)
                self.remote.close()
                await adb('wait-for-device', timeout=120)
                await self._finish_restore()
                await self.ensure_app()
        finally:
            self.test = None
            self.touch()
            await self.broadcast()
            if self.stop_after_test:
                self.stop_after_test = False
                asyncio.get_event_loop().create_task(self.stop('the automated test finished'))

    async def _finish_restore(self):
        with contextlib.suppress(Exception):
            await self.console.command(f'avd snapshot delete {qptest.TEST_SNAPSHOT}', timeout=300)
        with contextlib.suppress(FileNotFoundError):
            qptest.SESSION_PATH.unlink()

    # ----- input --------------------------------------------------------
    async def press(self, key):
        await self.remote.press(key)

    async def type_text(self, text):
        await self.remote.type_text(text)

    # ----- background ---------------------------------------------------
    async def monitor(self):
        while True:
            await asyncio.sleep(5)
            try:
                if self.test and self.test.get('status') == 'running' and not lock_is_held():
                    print('test lock released without an end request; restoring', flush=True)
                    async with self.lifecycle:
                        await self.end_test()
                if (self.state == 'running' and not self.test and not self.lifecycle.locked()
                        and now() >= self.last_activity + self.idle_seconds):
                    await self.stop('idle')
                await self.broadcast()
            except Exception as error:  # noqa: BLE001 - keep monitoring
                print(f'monitor: {error}', flush=True)


environment = None


# ----- authorization ------------------------------------------------------
authorization_cache = {}


async def is_authorized(request):
    """Ask the household SSO whether this browser session may open the host."""
    if not AUTHZ_URL:
        return True, None
    cookie = request.headers.get('Cookie', '')
    key = hashlib.sha256(cookie.encode()).hexdigest()
    cached = authorization_cache.get(key)
    if cached and cached[0] > time.monotonic():
        return cached[1], cached[2]
    headers = {
        'X-Original-Method': request.method,
        'X-Original-URL': f'{PUBLIC_URL}{request.path_qs}',
        'X-Forwarded-For': request.headers.get('X-Forwarded-For', request.remote or ''),
        'Cookie': cookie,
    }
    try:
        async with ClientSession(timeout=ClientTimeout(total=5)) as session:
            async with session.get(AUTHZ_URL, headers=headers, allow_redirects=False) as response:
                allowed = response.status == 200
                location = response.headers.get('Location')
    except Exception as error:  # noqa: BLE001 - fail closed
        print(f'authorization check failed: {error}', flush=True)
        return False, None
    authorization_cache[key] = (time.monotonic() + (60 if allowed else 5), allowed, location)
    if len(authorization_cache) > 500:
        authorization_cache.clear()
    return allowed, location


@web.middleware
async def authorization(request, handler):
    allowed, location = await is_authorized(request)
    if allowed:
        return await handler(request)
    if request.method == 'GET' and location and 'text/html' in request.headers.get('Accept', ''):
        raise web.HTTPFound(location)
    raise web.HTTPUnauthorized(text='Sign in through the household sign-in page.')


@web.middleware
async def security_headers(request, handler):
    response = await handler(request)
    response.headers.setdefault('Cache-Control', 'no-store')
    response.headers.setdefault('X-Content-Type-Options', 'nosniff')
    response.headers.setdefault('Referrer-Policy', 'same-origin')
    response.headers.setdefault('X-Frame-Options', 'DENY')
    return response


# ----- public routes ------------------------------------------------------
async def index(request):
    return web.FileResponse(WEB_ROOT / 'index.html')


async def api_status(request):
    return web.json_response(environment.status())


async def control_socket(request):
    socket = web.WebSocketResponse(heartbeat=20)
    await socket.prepare(request)
    environment.clients.add(socket)
    await socket.send_str(json.dumps(environment.status()))
    try:
        async for message in socket:
            if message.type != WSMsgType.TEXT:
                continue
            try:
                data = json.loads(message.data)
                await handle_control(socket, data)
            except Exception as error:  # noqa: BLE001 - reported to the page
                await socket.send_str(json.dumps({'type': 'notice', 'level': 'error', 'text': str(error)[:300]}))
    finally:
        environment.clients.discard(socket)
    return socket


async def handle_control(socket, data):
    kind = data.get('type')
    if kind == 'start':
        environment.request_start()
        return
    if kind == 'keepRunning':
        environment.touch()
        await environment.broadcast()
        return
    if environment.test:
        await socket.send_str(json.dumps({
            'type': 'notice', 'level': 'warning',
            'text': 'An automated test is using the emulator. Input is blocked until it finishes.'}))
        return
    if kind == 'stop':
        environment.touch()
        asyncio.get_event_loop().create_task(environment.stop('stopped from the page'))
        return
    if environment.state != 'running':
        await socket.send_str(json.dumps({'type': 'notice', 'level': 'info', 'text': 'Android is not ready yet.'}))
        return
    if kind == 'key':
        environment.touch()
        await environment.press(data.get('key'))
    elif kind == 'text':
        environment.touch()
        await environment.type_text(str(data.get('text', '')))


async def vnc_socket(request):
    if environment.state not in ('starting', 'running') or not environment.is_display_ready:
        raise web.HTTPServiceUnavailable(text='The display is not running')
    socket = web.WebSocketResponse(protocols=('binary',), max_msg_size=0)
    await socket.prepare(request)
    try:
        reader, writer = await asyncio.wait_for(asyncio.open_connection('127.0.0.1', VNC_PORT), 5)
    except Exception:  # noqa: BLE001
        await socket.close()
        return socket

    async def upstream():
        async for message in socket:
            if message.type == WSMsgType.BINARY:
                writer.write(message.data)
                await writer.drain()
        writer.close()

    async def downstream():
        while True:
            chunk = await reader.read(65536)
            if not chunk:
                break
            await socket.send_bytes(chunk)
        await socket.close()

    tasks = [asyncio.ensure_future(upstream()), asyncio.ensure_future(downstream())]
    await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
    for task in tasks:
        task.cancel()
    writer.close()
    return socket


# ----- internal routes (Unix socket, used by the command-line tool) -------
async def internal_status(request):
    return web.json_response(environment.status())


async def internal_start(request):
    task = environment.request_start()
    if task is not None:
        await asyncio.shield(task)
    return web.json_response(environment.status())


async def internal_stop(request):
    if environment.test:
        raise web.HTTPConflict(text='A test is active')
    await environment.stop((await request.json()).get('reason', 'stopped by command'))
    return web.json_response(environment.status())


async def internal_begin(request):
    body = await request.json()
    await environment.begin_test(str(body.get('label', 'automated test')))
    return web.json_response(environment.status())


async def internal_end(request):
    async with environment.lifecycle:
        await environment.end_test()
    return web.json_response(environment.status())


async def internal_ensure_app(request):
    await environment.ensure_app()
    await environment.broadcast()
    return web.json_response(environment.status())


async def internal_idle(request):
    body = await request.json()
    minutes = float(body.get('minutes', IDLE_SECONDS / 60))
    environment.idle_seconds = max(60, int(minutes * 60))
    environment.touch()
    await environment.broadcast()
    return web.json_response(environment.status())


async def main():
    global environment
    qptest.prepare_directories()
    environment = Environment()
    public = web.Application(middlewares=[security_headers, authorization])
    public.add_routes([
        web.get('/', index),
        web.get('/api/status', api_status),
        web.get('/control', control_socket),
        web.get('/vnc', vnc_socket),
        web.static('/static', WEB_ROOT),
        web.static('/novnc/core', NOVNC_ROOT / 'core'),
        web.static('/novnc/vendor', NOVNC_ROOT / 'vendor'),
    ])
    internal = web.Application()
    internal.add_routes([
        web.get('/status', internal_status),
        web.post('/start', internal_start),
        web.post('/stop', internal_stop),
        web.post('/test/begin', internal_begin),
        web.post('/test/end', internal_end),
        web.post('/app/ensure', internal_ensure_app),
        web.post('/idle', internal_idle),
    ])
    public_runner = web.AppRunner(public, access_log=None)
    internal_runner = web.AppRunner(internal, access_log=None)
    await public_runner.setup()
    await internal_runner.setup()
    with contextlib.suppress(FileNotFoundError):
        qptest.SOCKET_PATH.unlink()
    await web.TCPSite(public_runner, '0.0.0.0', PORT).start()
    await web.UnixSite(internal_runner, str(qptest.SOCKET_PATH)).start()
    print(f'QueuePilot Test listening on {PORT}; idle stop after {IDLE_SECONDS // 60} min', flush=True)
    monitor = asyncio.get_event_loop().create_task(environment.monitor())
    stop_event = asyncio.Event()
    for signal_number in (signal.SIGTERM, signal.SIGINT):
        asyncio.get_event_loop().add_signal_handler(signal_number, stop_event.set)
    await stop_event.wait()
    monitor.cancel()
    if environment.test:
        # Leave the session file so the next start restores the owner's state.
        print('shutting down during a test; the next start restores the saved session', flush=True)
    await environment.stop('container stopped')
    await public_runner.cleanup()
    await internal_runner.cleanup()


if __name__ == '__main__':
    asyncio.run(main())

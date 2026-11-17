"""Tests for `queuepilot-test run`: whatever stops a run, the session is restored and the lock
released only after the test command and its processes have ended."""

import argparse
import fcntl
import importlib.machinery
import importlib.util
import os
import signal
import tempfile
import threading
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOKEN = '31.4159'  # a sleep length no other process uses


def running(token):
    """Process numbers of `sleep <token>`."""
    found = []
    for cmdline in Path('/proc').glob('[0-9]*/cmdline'):
        try:
            if cmdline.read_bytes() == b'sleep\0' + token.encode() + b'\0':
                found.append(int(cmdline.parent.name))
        except OSError:
            pass
    return found


def load_cli(state):
    os.environ['QPT_STATE'] = str(state)
    loader = importlib.machinery.SourceFileLoader('queuepilot_test_cli', str(HERE / 'queuepilot-test'))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


class RunTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        state = Path(self.directory.name)
        self.cli = load_cli(state)
        self.cli.qptest.STATE = state
        self.cli.qptest.LOCK_PATH = state / 'test.lock'
        self.cli.qptest.SESSION_PATH = state / 'test-session.json'
        self.cli.QUEUE_PATH = state / 'queue'
        self.calls = []
        self.cli.call = self.record
        self.cli.require_emulator = lambda: None
        self.saved = {number: signal.getsignal(number) for number in self.cli.turnqueue.Stopper.SIGNALS}
        self.cli.turnqueue.Stopper.GRACE = 0.5

    def tearDown(self):
        self.cli.turnqueue.Stopper.GRACE = 10
        for number, handler in self.saved.items():
            signal.signal(number, handler)
        self.directory.cleanup()

    def record(self, method, path, body=None):
        self.calls.append(path)
        if path == '/test/end':
            probe = open(self.cli.qptest.LOCK_PATH, 'a')
            with self.assertRaises(BlockingIOError, msg='the lock must be held until the session is restored'):
                fcntl.flock(probe, fcntl.LOCK_EX | fcntl.LOCK_NB)
            probe.close()
            self.assertEqual(running(TOKEN), [], 'the command must have ended before the session is restored')

    def run_cli(self, command):
        arguments = argparse.Namespace(command=['--', 'sh', '-c', command], source=None, apk=None,
                                       label='test', wait=5)
        with self.assertRaises(SystemExit) as finished:
            self.cli.command_run(arguments)
        probe = open(self.cli.qptest.LOCK_PATH, 'a')
        fcntl.flock(probe, fcntl.LOCK_EX | fcntl.LOCK_NB)  # released afterwards
        probe.close()
        return finished.exception.code

    def test_a_stop_while_the_command_runs_ends_it_before_restoring(self):
        threading.Timer(0.5, os.kill, (os.getpid(), signal.SIGHUP)).start()
        status = self.run_cli(f'trap "" TERM; sleep {TOKEN}; true')
        self.assertEqual(status, -signal.SIGKILL)
        self.assertEqual(self.calls, ['/test/begin', '/test/end'])

    def test_stops_during_setup_and_cleanup_still_restore_the_session(self):
        def begin_then_stop_twice():
            os.kill(os.getpid(), signal.SIGTERM)
            os.kill(os.getpid(), signal.SIGINT)

        self.cli.require_emulator = begin_then_stop_twice
        status = self.run_cli('exit 0')
        self.assertEqual(status, 128 + signal.SIGTERM)
        self.assertEqual(self.calls, ['/test/begin', '/test/end'])


if __name__ == '__main__':
    unittest.main()

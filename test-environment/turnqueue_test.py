"""Tests for the first come, first served emulator queue (turnqueue.py)."""

import fcntl
import itertools
import os
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import turnqueue  # noqa: E402


class Clock:
    """A fake time source: every call is one nanosecond later."""

    def __init__(self):
        self.ticks = itertools.count(1_000)

    def __call__(self):
        return next(self.ticks)


class TurnQueueTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.queue = self.root / 'queue'
        self.lock_path = self.root / 'test.lock'
        self.lock_path.touch()
        self.clock = Clock()
        self.live = set()
        self.opened = []

    def tearDown(self):
        for handle in self.opened:
            handle.close()
        self.directory.cleanup()

    def ticket(self, label, pid):
        self.live.add(pid)
        return turnqueue.Ticket(self.queue, label, pid=pid, start=7, now_ns=self.clock,
                                alive=lambda other, start: other in self.live and start == 7).enter()

    def lock(self):
        handle = open(self.lock_path, 'a')
        self.opened.append(handle)
        return handle

    def test_tickets_wait_in_arrival_order(self):
        first = self.ticket('first', 101)
        second = self.ticket('second', 102)
        third = self.ticket('third', 103)
        self.assertEqual(first.position(), 0)
        self.assertEqual(second.position(), 1)
        self.assertEqual(third.position(), 2)
        self.assertEqual(third.labels_ahead(), ['first', 'second'])

    def test_a_later_ticket_does_not_take_the_free_lock(self):
        self.ticket('first', 101)
        later = self.ticket('later', 102)
        messages = []
        now = iter([0, 10])
        taken = turnqueue.take_turn(self.lock(), later, deadline=5, sleep=lambda _: None,
                                    monotonic=lambda: next(now), report=messages.append)
        self.assertFalse(taken)
        self.assertEqual(messages, ['queuepilot-test: waiting; 1 queued ahead: first'])
        self.assertEqual([path.read_text() for path in self.queue.iterdir()], ['first'])

    def test_the_ticket_of_a_gone_process_is_removed(self):
        gone = self.ticket('gone', 101)
        waiting = self.ticket('waiting', 102)
        self.live.discard(101)
        self.assertEqual(waiting.position(), 0)
        self.assertFalse(gone.path.exists())

    def test_the_first_ticket_takes_the_lock_and_leaves_the_queue(self):
        first = self.ticket('first', 101)
        self.ticket('second', 102)
        lock = self.lock()
        self.assertTrue(turnqueue.take_turn(lock, first, deadline=5, sleep=lambda _: None,
                                            monotonic=lambda: 0, report=lambda _: None))
        self.assertEqual([path.read_text() for path in self.queue.iterdir()], ['second'])
        with self.assertRaises(BlockingIOError):
            fcntl.flock(self.lock(), fcntl.LOCK_EX | fcntl.LOCK_NB)

    def test_the_first_ticket_waits_while_another_run_holds_the_lock(self):
        holder = self.lock()
        fcntl.flock(holder, fcntl.LOCK_EX)
        first = self.ticket('first', 101)
        messages = []
        sleeps = []
        now = iter([0, 1, 2, 10])

        def sleep(seconds):
            sleeps.append(seconds)
            if len(sleeps) == 2:
                fcntl.flock(holder, fcntl.LOCK_UN)

        taken = turnqueue.take_turn(self.lock(), first, deadline=5, sleep=sleep,
                                    monotonic=lambda: next(now), report=messages.append)
        self.assertTrue(taken)
        self.assertEqual(sleeps, [2, 2])
        self.assertEqual(messages, ['queuepilot-test: next in line; waiting for the current test to finish'])
        self.assertFalse(first.path)

    def test_queued_lists_live_tickets_for_status(self):
        self.assertEqual(turnqueue.queued(self.queue), [])
        pid = turnqueue.os.getpid()
        start = turnqueue.started(pid)
        self.queue.mkdir()
        (self.queue / f'{1:020d}-{pid}-{start}').write_text('this process')
        (self.queue / 'not-a-ticket').write_text('ignored')
        self.assertEqual(turnqueue.queued(self.queue), ['this process'])

    def test_a_reused_process_number_does_not_keep_an_old_ticket(self):
        pid = turnqueue.os.getpid()
        start = turnqueue.started(pid)
        self.assertTrue(turnqueue.is_alive(pid, start))
        if start:
            self.assertFalse(turnqueue.is_alive(pid, start + 1))
        self.queue.mkdir()
        old = self.queue / f'{1:020d}-{pid}-{start + 1}'
        old.write_text('before a restart')
        self.assertEqual(turnqueue.queued(self.queue), [] if start else ['before a restart'])




def stop_soon(number, after=0.3):
    """Send this process a signal from another thread, as an outside stop would."""
    timer = threading.Timer(after, os.kill, (os.getpid(), number))
    timer.start()
    return timer


class StopperTest(unittest.TestCase):
    def setUp(self):
        self.saved = {number: signal.getsignal(number) for number in turnqueue.Stopper.SIGNALS}
        self.stopper = turnqueue.Stopper(grace=0.5).install()

    def tearDown(self):
        for number, handler in self.saved.items():
            signal.signal(number, handler)

    def test_a_stop_while_waiting_exits(self):
        with self.assertRaises(SystemExit) as stopped:
            os.kill(os.getpid(), signal.SIGHUP)
        self.assertEqual(stopped.exception.code, 128 + signal.SIGHUP)

    def test_a_stop_after_the_turn_skips_the_command(self):
        self.stopper.has_turn = True
        os.kill(os.getpid(), signal.SIGTERM)  # no SystemExit: cleanup must still run
        self.assertEqual(self.stopper.run(['sh', '-c', 'exit 3']), 128 + signal.SIGTERM)
        self.assertIsNone(self.stopper.child)

    def test_a_stop_ends_the_command_and_its_descendants(self):
        for number in turnqueue.Stopper.SIGNALS:
            stopper = turnqueue.Stopper(grace=5).install()
            stopper.has_turn = True
            stop_soon(number)
            status = stopper.run(['sh', '-c', 'sleep 30; true'])
            self.assertIn(status, (-signal.SIGTERM, 128 + signal.SIGTERM))
            self.assertEqual(turnqueue.group_members(stopper.child.pid), [])

    def test_a_command_that_ignores_sigterm_is_killed_after_the_grace(self):
        self.stopper.has_turn = True
        stop_soon(signal.SIGTERM)
        began = time.monotonic()
        status = self.stopper.run(['sh', '-c', 'trap "" TERM; sleep 30; true'])
        self.assertEqual(status, -signal.SIGKILL)
        self.assertLess(time.monotonic() - began, 5)
        self.assertEqual(turnqueue.group_members(self.stopper.child.pid), [])

    def test_a_background_process_is_ended_after_a_normal_exit(self):
        self.stopper.has_turn = True
        self.assertEqual(self.stopper.run(['sh', '-c', 'sleep 30 & exit 0']), 0)
        self.assertEqual(turnqueue.group_members(self.stopper.child.pid), [])

    def test_end_does_nothing_before_a_command(self):
        self.stopper.end()

if __name__ == '__main__':
    unittest.main()

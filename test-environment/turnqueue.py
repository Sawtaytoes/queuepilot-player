"""First come, first served turns at the shared emulator.

`queuepilot-test run` used to poll a non-blocking `flock` every five seconds. Whichever
waiter polled first after a run ended took the emulator, so a run queued early could lose
to later runs again and again. On 2026-11-17 several runs waited more than two hours while
later runs went ahead of them.

Now each waiter writes a ticket named by the time it arrived. Only the oldest live ticket
may take the lock, so runs start in the order they were queued. A ticket whose process is
gone (killed, or its container exec ended) is removed by the next waiter that reads the
queue. A ticket also records when its process started, so a ticket left from before a
container restart cannot match a new process that reuses the same number. The `flock` stays the actual guard, so a waiter running older code that ignores the
queue still cannot overlap a run.
"""

import fcntl
import os
import signal
import subprocess
import sys
import time
from pathlib import Path


def started(pid):
    """The process's start time in clock ticks since boot, or 0 where /proc cannot tell."""
    try:
        stat = Path(f'/proc/{pid}/stat').read_text()
    except OSError:
        return 0
    return int(stat.rsplit(')', 1)[1].split()[19])


def is_alive(pid, start):
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        pass
    return start == 0 or started(pid) in (0, start)


class Ticket:
    """One waiter's place in the queue."""

    def __init__(self, queue_dir, label, pid=None, start=None, now_ns=time.time_ns, alive=is_alive):
        self.queue_dir = Path(queue_dir)
        self.label = label
        self.pid = os.getpid() if pid is None else pid
        self.start = started(self.pid) if start is None else start
        self.now_ns = now_ns
        self.alive = alive
        self.path = None

    def enter(self):
        self.queue_dir.mkdir(parents=True, exist_ok=True)
        self.path = self.queue_dir / f'{self.now_ns():020d}-{self.pid}-{self.start}'
        self.path.write_text(self.label)
        return self

    def leave(self):
        if self.path is not None:
            self.path.unlink(missing_ok=True)
            self.path = None

    def waiting(self):
        """The live tickets, oldest first. Tickets of processes that are gone are removed."""
        tickets = []
        for path in sorted(self.queue_dir.glob('*-*-*')):
            try:
                pid, start = (int(part) for part in path.name.split('-')[1:3])
            except ValueError:
                continue
            if path != self.path and not self.alive(pid, start):
                path.unlink(missing_ok=True)
                continue
            tickets.append(path)
        return tickets

    def position(self):
        """0 when this ticket is next; the number of tickets ahead of it otherwise."""
        tickets = self.waiting()
        return tickets.index(self.path) if self.path in tickets else len(tickets)

    def labels_ahead(self):
        tickets = self.waiting()
        ahead = tickets[:tickets.index(self.path)] if self.path in tickets else tickets
        labels = []
        for path in ahead:
            try:
                labels.append(path.read_text())
            except FileNotFoundError:
                pass
        return labels


def take_turn(lock, ticket, deadline, sleep=time.sleep, monotonic=time.monotonic, report=print):
    """Wait for this ticket to be first, then take the lock. Returns True with the lock held,
    or False at the deadline. The ticket is always removed before returning."""
    last = None
    try:
        while True:
            position = ticket.position()
            if position == 0:
                try:
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    return True
                except BlockingIOError:
                    pass
            if position != last:
                ahead = ticket.labels_ahead()
                if ahead:
                    report(f'queuepilot-test: waiting; {len(ahead)} queued ahead: ' + '; '.join(ahead))
                else:
                    report('queuepilot-test: next in line; waiting for the current test to finish')
                last = position
            if monotonic() > deadline:
                return False
            sleep(2)
    finally:
        ticket.leave()


def queued(queue_dir):
    """The labels of every live ticket, oldest first, for `queuepilot-test status`."""
    reader = Ticket(queue_dir, '', pid=-1, start=0)
    if not reader.queue_dir.is_dir():
        return []
    labels = []
    for path in reader.waiting():
        try:
            labels.append(path.read_text())
        except FileNotFoundError:
            pass
    return labels



def group_members(group):
    """The live (not zombie) processes in a process group, read from /proc."""
    members = []
    for stat in Path('/proc').glob('[0-9]*/stat'):
        try:
            fields = stat.read_text().rsplit(')', 1)[1].split()
        except (OSError, IndexError):
            continue
        if fields[0] != 'Z' and int(fields[2]) == group:
            members.append(int(stat.parent.name))
    return members


class Stopper:
    """What SIGTERM, SIGHUP, and SIGINT do during `queuepilot-test run`.

    While a run waits in the queue, a stop exits and the ticket is removed. Once the run has
    its turn, a stop never exits, because the session must be restored before the lock is
    released. It is recorded instead: a command that has not started is skipped, and a
    running command is ended. The command runs in its own process group, because
    `tools/tv-checks.sh` starts checks that would outlive a stopped shell. `run()` returns only
    when no process of the group is left: SIGTERM first, then SIGKILL after `grace` seconds.

    A process that leaves the group on purpose (`setsid`, such as the shared adb server) is
    not followed. The tests do not do that, and the adb server must outlive a run."""

    SIGNALS = (signal.SIGTERM, signal.SIGHUP, signal.SIGINT)
    GRACE = 10

    def __init__(self, grace=None, sleep=time.sleep, monotonic=time.monotonic):
        self.grace = self.GRACE if grace is None else grace
        self.sleep = sleep
        self.monotonic = monotonic
        self.has_turn = False
        self.child = None
        self.stopped = None
        self.stopped_at = None

    def install(self):
        for number in self.SIGNALS:
            signal.signal(number, self.stop)
        return self

    def stop(self, number, _frame=None):
        if not self.has_turn:
            sys.exit(128 + number)
        if self.stopped is None:
            self.stopped, self.stopped_at = number, self.monotonic()
        self.signal_group(signal.SIGTERM)

    def signal_group(self, number):
        if self.child is None:
            return
        try:
            os.killpg(self.child.pid, number)
        except ProcessLookupError:
            pass

    def run(self, command, **options):
        """Run the command in a new process group until it and its group have ended, and
        return its exit status. A run already stopped skips the command."""
        if self.stopped is None:
            self.child = subprocess.Popen(command, start_new_session=True, **options)
            if self.stopped is not None:  # stopped while the command was starting
                self.signal_group(signal.SIGTERM)
        status = 128 + self.stopped if self.child is None else self.child_status()
        self.end()
        return status

    def child_status(self):
        while True:
            try:
                return self.child.wait(timeout=0.2)
            except subprocess.TimeoutExpired:
                self.escalate()

    def escalate(self):
        if self.stopped_at is not None and self.monotonic() - self.stopped_at > self.grace:
            self.signal_group(signal.SIGKILL)

    def end(self):
        """Stop whatever is left of the command's group and return once none of it is left."""
        if self.child is None:
            return
        if group_members(self.child.pid):
            self.stopped_at = self.monotonic() if self.stopped_at is None else self.stopped_at
            self.signal_group(signal.SIGTERM)
        while group_members(self.child.pid):
            self.escalate()
            self.sleep(0.1)

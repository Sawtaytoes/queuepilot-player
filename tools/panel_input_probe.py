#!/usr/bin/env python3
"""Does the Display size panel lose a choice made soon after it opens? Emulator only.

    queuepilot-test run --source <checkout> -- python3 -u <checkout>/tools/panel_input_probe.py [trials]

A probe, not a check: it prints what happened and fails only if the app crashes. From
Settings, with the remote, it focuses Display size, presses OK, moves Down five times from the
checked 100% to 125%, and presses OK, at three speeds:

- burst: OK, the five Downs, and OK back to back in one `input keyevent` call (the probe does
  not measure the time between the keys);
- quick: OK, then the Downs and OK in a second call, as soon as `input` starts again;
- person: OK, a one-second pause, then each key as its own `input` command with a 0.25-second
  pause after it; each command's start-up time adds to the pauses and is not measured.

It also reads which row has focus as soon as it can after OK and again after a 1.5-second pause,
and, for comparison, taps 125% through the sequence display_smoke.py used: tap the row, wait for
the panel's text, tap 125%. Between trials it returns Display size to 100%. It clears the app's
data and uses the demo.

With --gaps it instead starts a second `input` command, with the Downs and OK, 0, 50, 100,
150, 200, and 300 ms after the one with OK. These are delays between starting the commands,
not measured times between the keys reaching the app: each command's start-up time varies. It
also tries to read from the system's event log when the panel's window gained input focus.
"""
import smoke as s
import re
import sys
import time

ARGS = [a for a in sys.argv[1:] if not a.startswith('--')]
TRIALS = int(ARGS[0]) if ARGS else 10
IS_GAPS = '--gaps' in sys.argv
assert s.adb('shell', 'getprop', 'ro.hardware').decode().strip() in ('ranchu', 'goldfish')
PANEL = 'Make everything smaller'


def keys(*codes):
    s.adb('shell', 'input', 'keyevent', *map(str, codes))


def nodes():
    return list(s.screen().iter('node'))


def label(node):
    return '' if node is None else (node.get('text') or node.get('content-desc') or '')


def focused():
    return next((n for n in nodes() if n.get('focused') == 'true'), None)


def size_row():
    for _ in range(20):
        found = next((t for t in s.text().split('\n') if t.startswith('Display size · ')), None)
        if found:
            return found
        time.sleep(.5)
    raise AssertionError('No Display size row\n' + s.text())


def closed(seconds=5):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if PANEL not in s.text():
            return True
        time.sleep(.3)
    return False


def to_settings():
    """Back to Settings with no panel open, whatever a stray key opened."""
    for _ in range(3):
        if PANEL not in s.text() and 'Cancel' not in s.text():
            break
        keys(4)
        time.sleep(.6)
    if not any(t.startswith('Display size · ') for t in s.text().split('\n')):
        s.click('Settings')
    return size_row()


def bounds(node):
    return tuple(map(int, re.findall(r'-?\d+', node.get('bounds'))))


def focus_size_row():
    """Moves remote focus to the Display size row, steering by where it is on screen: Down or Up
    to its row, then Right or Left. Left is pressed only if the row is to the left of focus."""
    keys(20)  # the first key after a tap leaves touch mode
    time.sleep(.4)
    path = []
    for _ in range(20):
        found = nodes()
        now = next((n for n in found if n.get('focused') == 'true'), None)
        target = next((n for n in found if n.get('text', '').startswith('Display size · ')), None)
        if target is None:
            raise AssertionError('No Display size row\n' + s.text())
        if now is not None and label(now).startswith('Display size'):
            return
        path.append(label(now))
        if now is None:
            code = 20
        else:
            x1, y1, x2, y2 = bounds(now)
            t1, u1, t2, u2 = bounds(target)
            middle = (u1 + u2) // 2
            if middle > y2:
                code = 20
            elif middle < y1:
                code = 19
            elif t1 >= x2:
                code = 22
            else:
                code = 21
        keys(code)
        time.sleep(.4)
    raise AssertionError('Could not focus Display size; focus went ' + ' -> '.join(map(repr, path)))


def reset():
    """Display size back to 100%, by tap, so each trial starts with 100% checked."""
    if to_settings() != 'Display size · 100%':
        s.click(size_row())
        s.contains(PANEL)
        s.choose_display_size(100)
    assert size_row() == 'Display size · 100%', size_row()


def outcome():
    if closed():
        return size_row()
    where = label(focused())
    keys(4)
    closed()
    return f'panel still open, focus on {where!r}'


def remote(speed):
    reset()
    focus_size_row()
    if speed == 'burst':
        keys(23, 20, 20, 20, 20, 20, 23)
    elif speed == 'quick':
        keys(23)
        keys(20, 20, 20, 20, 20, 23)
    else:
        keys(23)
        time.sleep(1)
        for _ in range(5):
            keys(20)
            time.sleep(.25)
        keys(23)
    return outcome()


def focus_after_open():
    reset()
    focus_size_row()
    keys(23)
    first = label(focused())
    time.sleep(1.5)
    second = label(focused())
    keys(4)
    closed()
    return f'{first!r} then {second!r}'


def tap():
    reset()
    s.click('Display size · 100%')
    s.contains(PANEL)
    s.click('125%')
    return size_row() if closed(3) else 'panel still open after one tap'


def gap(milliseconds):
    """OK, then the five Downs and OK started this many milliseconds later."""
    reset()
    focus_size_row()
    s.adb('shell', f'input keyevent 23 & sleep {milliseconds / 1000:.3f}; '
                   'input keyevent 20 20 20 20 20 23; wait')
    return outcome()


def focus_delay():
    """Milliseconds from the start of the OK `input` command to each input focus change."""
    reset()
    focus_size_row()
    s.adb('logcat', '-b', 'all', '-c')
    keys(23)
    time.sleep(2)
    log = s.adb('logcat', '-d', '-v', 'epoch', '-b', 'main,system,events').decode(errors='replace')
    start, changes = None, []
    for line in log.splitlines():
        parts = line.split()
        if not parts:
            continue
        try:
            when = float(parts[0])
        except ValueError:
            continue
        if start is None and 'com.android.commands.input.Input' in line:
            start = when
        elif start is not None and 'input_focus' in line:
            changes.append(f'+{round((when - start) * 1000)} ms {line.split("input_focus", 1)[1][:90].strip()}')
    keys(4)
    closed()
    if start is None:
        return 'no start of the OK input command in the log; focus time unknown'
    return '; '.join(changes) if changes else 'no input_focus change logged after OK'


s.post('_online')
s.adb('logcat', '-b', 'crash', '-c')
s.adb('shell', 'pm', 'clear', s.PACKAGE)
s.launch()
s.contains('Your people. Your queues.')
s.click('Explore the demo')
s.contains('What are we watching?')
s.click('Settings')
results = {}
TRIALS_BY_NAME = (
    [('focus-delay', focus_delay)]
    + [(f'gap-{ms}', lambda ms=ms: gap(ms)) for ms in (0, 50, 100, 150, 200, 300)]
    if IS_GAPS else
    [('focus', focus_after_open), ('burst', lambda: remote('burst')), ('quick', lambda: remote('quick')),
     ('person', lambda: remote('person')), ('tap', tap)])
for name, trial in TRIALS_BY_NAME:
    results[name] = []
    for number in range(1, TRIALS + 1):
        try:
            result = trial()
        except AssertionError as error:  # one failed trial is a result, not the end of the probe
            result = 'error: ' + str(error).split('\n')[0]
            to_settings()
        results[name].append(result)
        print(f'{name} {number}: {result}', flush=True)
reset()
print('Summary:', flush=True)
for name, found in results.items():
    counts = {}
    for result in found:
        counts[result] = counts.get(result, 0) + 1
    print(f'  {name}: {counts}', flush=True)
crashes = s.adb('logcat', '-d', '-b', 'crash').decode()
assert 'FATAL EXCEPTION' not in crashes, crashes

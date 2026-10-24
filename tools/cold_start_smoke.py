#!/usr/bin/env python3
"""Home shows live queues after a cold start, and saved queues never stay; emulator only.

Run right after household_smoke.py, which leaves the fixture QueuePilot connected and its queues
saved on the TV. It cannot run after layout_smoke.py, which clears the app's data and opens the
demo. It resets the display density first.

The defect (0.5.4 to 0.5.7): Home asked QueuePilot for its queues once, when the app opened. At
boot, or when a TV wakes, the app opens before Android has a default network (the shared
emulator's Wi-Fi connected 23 s after the app), the request failed with
"java.net.ConnectException: Failed to connect", and Home kept the saved queues under
"Saved queues · reconnect to start playback" with placeholder faces until the app was restarted.
"""
import smoke as s
import re,subprocess,time

SAVED='Saved queues · reconnect to start playback'

def texts():return [n.get('text','') for n in s.screen().iter('node')]
def wait(predicate,what,seconds=30):
    deadline=time.time()+seconds
    while time.time()<deadline:
        current=texts()
        if predicate(current):return current
        time.sleep(.5)
    raise AssertionError('Timed out: '+what+'\n'+'\n'.join(texts()))
def is_live(current):return 'Plays as Ada' in current and SAVED not in current
def log():return s.adb('logcat','-d','-s','QueuePilotQueues:*').decode()
def default_network():
    out=s.adb('shell','dumpsys','connectivity').decode()
    match=re.search(r'Active default network: (\S+)',out)
    return match.group(1) if match else ''
def wifi(is_on):
    s.adb('shell','svc','wifi','enable' if is_on else 'disable')
    for _ in range(60):
        if (default_network()!='none')==is_on:return
        time.sleep(.5)
    raise AssertionError('The default network did not '+('return' if is_on else 'go away'))
def cold_start():
    s.adb('shell','am','force-stop',s.PACKAGE);s.adb('logcat','-c');s.launch()

assert s.adb('shell','getprop','ro.hardware').decode().strip() in ('ranchu','goldfish')
s.adb('shell','wm','density','reset')
s.post('_online')

# 1. A plain cold start: Home draws the live queues and never the saved heading.
cold_start()
current=wait(is_live,'live queues on Home after a cold start')
for _ in range(4):
    assert SAVED not in texts(),'Saved heading after a cold start with QueuePilot answering'
    time.sleep(.5)
assert 'Queue list failed' not in log(),log()
s.shot('cold-start-home-live')

# 2. The cause: the app opens before Android has a default network. Home shows the saved copy
# at once, then the live queues as soon as the network arrives, with no restart.
try:
    s.adb('shell','am','force-stop',s.PACKAGE);wifi(False)
    s.adb('logcat','-c');s.launch()
    wait(lambda t:SAVED in t and 'Plays as Ada' in t,'saved queues while there is no network')
    failure=log()
    assert 'java.net.ConnectException' in failure,failure
    print('No network at the start:',*sorted({l.split('): ',1)[-1].strip() for l in failure.splitlines() if 'Exception' in l and ' at ' not in l}),sep='\n  ',flush=True)
    s.shot('cold-start-home-saved-no-network')
finally:
    wifi(True)
wait(is_live,'live queues after the network arrived',25)
retries=log()
assert 'Live queue retry now: network available' in retries and 'Live queues after a retry' in retries,retries
s.shot('cold-start-home-live-after-network')

# 3. QueuePilot itself is down at the cold start and comes back: the timed retry (2 s, 4 s, …)
# replaces the saved queues without any network change or key press.
s.post('_native_offline')
try:
    cold_start()
    wait(lambda t:SAVED in t,'saved queues while QueuePilot is down')
    time.sleep(1)
finally:
    s.post('_online')
wait(is_live,'live queues after QueuePilot came back',20)
retries=log()
assert 'Queues not live; retry in 2000 ms' in retries and 'Live queues after a retry' in retries,retries

# 4. Returning to the app retries at once: leave with Home while QueuePilot is down, wait until
# the next timed retry is 8 s away, bring QueuePilot back, and return to the app.
s.post('_native_offline')
try:
    cold_start()
    wait(lambda t:SAVED in t,'saved queues before leaving the app')
    s.adb('shell','input','keyevent','3')
    for _ in range(40):
        if 'retry in 8000 ms' in log():break
        time.sleep(.5)
    else:raise AssertionError('No third timed retry\n'+log())
finally:
    s.post('_online')
s.adb('logcat','-c');s.launch()
wait(is_live,'live queues after the app resumed',6)
assert 'Live queue retry now: resumed' in log(),log()

assert 'FATAL EXCEPTION' not in s.adb('logcat','-d','-b','crash').decode()
print('PASS: a cold start shows live queues; with no network at the start Home shows the saved queues and replaces them with the live queues when the network arrives; a QueuePilot outage at the start is retried on a backoff; and returning to the app retries at once',flush=True)

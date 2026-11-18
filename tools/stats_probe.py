#!/usr/bin/env python3
"""Playback stats on the emulator: the readout, its numbers, its cost, and that it stops.

Emulator only, through `queuepilot-test run` (it signs in to the fixtures from a clean app):

  queuepilot-test run --source /source/.worktrees/<branch> --label '<branch> stats' -- \\
    python3 -u /source/.worktrees/<branch>/tools/stats_probe.py

1. Turns the hidden readout on (`--ez playback-stats true`), plays the fixture episode, and
   reads the per-second `PlaybackStats` log: the video and audio rates against the file's own
   size and length, the verdict, and the engine's CPU time per sample.
2. Sends the app to the background and checks that sampling stops.
3. Has the fixture server send the video at 3 Mb/s, slower than the clip, and checks for a
   "not sustainable" verdict. Full speed is restored whatever happens. (The emulator console's
   `network speed` does not slow the guest's Wi-Fi, so it cannot do this.)
4. Turns the readout off and plays again: no stats, no readout. Compares the app's CPU time
   with the readout on and off.
"""
import re,subprocess,time
import smoke as s

CLOCK_TICKS=100
COMPONENT=s.PACKAGE+'/dev.queuepilot.player.MainActivity'
SLOW_KBPS=3000  # under the fixture clip's 4.8 Mb/s

def wait_text(value,tries=30):
    for _ in range(tries):
        if value in s.text():return
        time.sleep(.5)
    raise AssertionError('Missing text: '+value+'\n'+s.text())

def wait_for_network(seconds=120):
    """A restored snapshot brings the guest's network up 20 s or more later; the Plex link screen
    does not retry, so sign in only once the fixture server answers."""
    for _ in range(seconds):
        if subprocess.run(['adb','-s',s.SERIAL,'shell','ping','-c','1','-W','1','10.0.2.2'],capture_output=True).returncode==0:return
        time.sleep(1)
    raise AssertionError('The emulator has no network')

def sign_in():
    """A fresh fixture account, signed in as Ada."""
    wait_for_network()
    s.post('_online');s.adb('shell','pm','clear',s.PACKAGE);s.launch();wait_text('Your people. Your queues.')
    s.click('Explore the demo');wait_text('What are we watching?')
    s.click('Settings');s.click('Connect Plex');wait_text('ABCD');s.post('_claim');wait_text('What are we watching?')
    if s.has_desc('Switch profile, Morgan'):s.click('Switch profile, Morgan');s.click('Ada');wait_text('What are we watching?')

def cpu_seconds(pid):
    fields=s.adb('shell','cat',f'/proc/{pid}/stat').decode().rsplit(')',1)[1].split()
    return (int(fields[11])+int(fields[12]))/CLOCK_TICKS  # utime and stime

def pid():return s.adb('shell','pidof',s.PACKAGE).decode().strip()

def play_episode():
    s.click('Series');s.open_library('Series');s.click('Orbit Nine');s.click('Season 1')
    s.click('Orbit Nine, S1 · E9 · The signal');wait_text('Watch with Ada');s.click('Play')
    start_from_beginning()

def start_from_beginning(tries=20):
    """A title with a saved position asks before it plays; start from 0:00, so the probe's
    timings and the whole-file rate hold. An earlier step may have left the episode watched,
    with no saved position, so the question may not come."""
    for _ in range(tries):
        if 'Start from the beginning' in s.text():
            s.click('Start from the beginning');return
        time.sleep(.5)

def stats_lines(timed=False):
    """The logged samples; with their log times when timed, for diagnosing the rate."""
    log=s.adb('logcat','-d','-v','time' if timed else 'brief','PlaybackStats:I','*:S').decode(errors='replace')
    if timed:return [line[:18]+' '+line.split(': ',1)[1][:40] for line in log.splitlines() if ' I/PlaybackStats' in line]
    return [line.split(': ',1)[1] for line in log.splitlines() if line.startswith('I/PlaybackStats')]

def spacing(timed):
    """Seconds between consecutive timed samples."""
    times=[float(line[12:18])+60*int(line[9:11])+3600*int(line[6:8]) for line in timed]
    return [round(later-earlier,3) for earlier,later in zip(times,times[1:])]

def session_blocks(dump=None):
    """The app's own blocks of `dumpsys media_session`: each from a `package=<app>` line to the
    next `package=` line, so another app's session never counts."""
    dump=dump if dump is not None else s.adb('shell','dumpsys','media_session').decode(errors='replace')
    blocks=re.split(r'(?m)^[ \t]*package=',dump)[1:]
    return [block for block in blocks if re.match(re.escape(s.PACKAGE)+r'[ \t]*\r?$',block.splitlines()[0])]

def session_states(dump=None):
    """The PlaybackState of each of the app's media session blocks (2 paused, 3 playing; None
    without a state). Newer Android writes `state=PAUSED(2)`, older `state=2`."""
    states=[]
    for block in session_blocks(dump):
        match=re.search(r'state=PlaybackState \{state=(?:[A-Z_]+\()?(\d+)',block)
        states.append(int(match.group(1)) if match else None)
    return states


def device_seconds():return float(s.adb('shell','date','+%s.%N').decode().strip())

def sample_times():
    """The device time of every logged sample, from logcat's own epoch timestamps."""
    log=s.adb('logcat','-d','-v','epoch','PlaybackStats:I','*:S').decode(errors='replace')
    return [float(line.split()[0]) for line in log.splitlines() if ' I PlaybackStats' in line or 'I/PlaybackStats' in line]

def background_check(tries=2):
    """No sample more than 1 s after the app left the screen, for 6 s while it stays away. Times
    come from logcat's own timestamps, so a sample written just before Home but delivered late
    does not count. A busy emulator's launcher can hand the screen back to the app, which then
    samples, correctly; that try does not count."""
    for attempt in range(tries):
        s.adb('shell','input','keyevent','3');s.wait_backgrounded(20);left=device_seconds()
        came_back=False;until=time.time()+7
        while time.time()<until:
            came_back=came_back or s.is_in_front(s.resumed_activities());time.sleep(.5)
        came_back=came_back or s.is_in_front(s.resumed_activities())
        late=[round(at-left,2) for at in sample_times() if at>left+1]
        if not came_back:
            print('Samples around leaving the screen (s after):',[round(at-left,2) for at in sample_times() if at>left-3])
            assert not late,('No sampling more than 1 s after the app left the screen',late)
            print('Background: no samples for 6 s');return
        print('The app came back to the screen during the background check:',s.resumed_activities())
    raise AssertionError('The app kept coming back to the screen after Home')

def number(line,pattern):
    match=re.search(pattern,line);return float(match.group(1)) if match else None

def launch(stats):
    s.adb('shell','am','force-stop',s.PACKAGE);time.sleep(1)
    s.adb('shell','am','start','-n',COMPONENT,'--ez','fixture','true','--ez','playback-stats','true' if stats else 'false')
    wait_text('What are we watching?')

def ensure_inspector():
    """Install the emulator-only UI inspector, as tv-checks.sh does, when it is missing."""
    if b'dev.queuepilot.inspector' in s.adb('shell','pm','list','packages','dev.queuepilot.inspector'):return
    import os,subprocess
    source=os.environ['QUEUEPILOT_TEST_SOURCE'];fixtures=os.environ['FIXTURE_ROOT']
    subprocess.check_call(['bash',source+'/tools/ui-inspector/build.sh',fixtures+'/inspector-build'])
    s.adb('install','-r',fixtures+'/inspector-build/inspector.apk')

def main():
    hardware=s.adb('shell','getprop','ro.hardware').decode().strip()
    assert hardware in ('ranchu','goldfish'),'The probe must never target a physical TV'
    s.adb('shell','wm','size','reset');s.adb('shell','wm','density','reset')
    ensure_inspector()
    sign_in()
    size=len(open('/fixtures/sample.mp4','rb').read())
    # 1. The readout and its numbers.
    launch(True);s.adb('logcat','-c');play_episode();time.sleep(4)
    # Paused on screen, early in the 30-second clip: sampling continues, so the buffer and
    # throughput stay live, at most once a second.
    s.adb('shell','input','keyevent','127');time.sleep(1)
    states=session_states()
    if 2 not in states:
        print('The app\'s media session blocks:\n'+'\n---\n'.join(session_blocks()))
        raise AssertionError(('The player is paused (PlaybackState 2)',states))
    s.adb('logcat','-c');time.sleep(5)
    paused=stats_lines(timed=True);print('Paused:',len(paused),'samples, spacing',spacing(paused))
    # The adb calls around the 5 s wait take their own time, so count by the samples' spacing.
    assert len(paused)>=3,('Sampling continues while paused','\n'.join(paused))
    assert min(spacing(paused))>=.9,('At most one sample a second while paused','\n'.join(paused))
    s.adb('shell','input','keyevent','126');time.sleep(1);s.adb('logcat','-c')
    first=pid();cpu_before=cpu_seconds(first);time.sleep(15);cpu_on=cpu_seconds(first)-cpu_before
    s.shot('playback-stats')
    assert 'Buffered' in s.text(),'The readout is shown'
    lines=stats_lines();assert len(lines)>=12,('About one sample a second',len(lines))
    last=lines[-1];print('Last sample:',last)
    video=number(last,r'Video\s+([\d.]+) Mb/s now');average=number(last,r'Video\s+\S+ Mb/s now\s+([\d.]+) Mb/s avg')
    audio=number(last,r'Audio\s+([\d.]+) Mb/s now')
    file_mbps=size*8/30/1e6
    print(f'File: {file_mbps:.2f} Mb/s over 30 s; video now {video}, video average {average}, audio now {audio}')
    assert average and abs(average+(audio or 0)-file_mbps)/file_mbps<.1,('The average video and audio rates make up the file',average,audio,file_mbps)
    assert video and video>0,'The video rate at the playhead is known'
    audio_average=number(last,r'Audio\s+\S+ Mb/s now\s+([\d.]+) Mb/s avg')
    # A wrong track id would leave the selected audio track uncounted: its rates would be unknown.
    assert audio and audio>0 and audio_average and audio_average>0,('The selected audio track is counted',last)
    assert 'sustainable' in last and 'not sustainable' not in last,last
    costs=[number(l,r'Cost (\d+) µs/sample') for l in lines[2:]];costs=[c for c in costs if c is not None]
    mean_cost=sum(costs)/len(costs);worst=max(costs)
    print(f'Engine CPU per sample: mean {mean_cost:.0f} µs, worst {worst:.0f} µs, over {len(costs)} samples')
    assert mean_cost<2000,'A sample stays well under 2 ms of main-thread CPU on the emulator'
    # 2. Hidden: the app in the background samples nothing.
    background_check()
    # 3. A network slower than the clip: 3 Mb/s for 4.8 Mb/s of media.
    try:
        s.post(f'_video_kbps/{SLOW_KBPS}')
        launch(True);s.adb('logcat','-c');play_episode();time.sleep(25)
        lines=stats_lines();last=lines[-1] if lines else ''
        print('Slow network, last sample:',last)
        rebuffers=number(last,r'Rebuffers (\d+)')
        verdicts=[l for l in lines if 'not sustainable' in l]
        assert verdicts,'The verdict says the slow network cannot sustain the clip'
        print(f'Slow network: {len(verdicts)} of {len(lines)} samples not sustainable, rebuffers {rebuffers}')
        s.shot('playback-stats-slow')
    finally:
        s.post('_video_kbps/0')
        # Leave nothing playing at the slow speed's leftovers.
        s.adb('shell','am','force-stop',s.PACKAGE)
    # 4. Off: no readout, no stats; the CPU comparison.
    launch(False);s.adb('logcat','-c');play_episode();time.sleep(4)
    second=pid();cpu_before=cpu_seconds(second);time.sleep(15);cpu_off=cpu_seconds(second)-cpu_before
    assert not stats_lines(),'No stats when the readout is off'
    assert 'Buffered' not in s.text(),'No readout when it is off'
    print(f'App CPU over 15 s of playback: readout on {cpu_on:.2f} s, off {cpu_off:.2f} s')
    s.back();s.back()
    crashes=s.adb('logcat','-d','-b','crash').decode()
    assert not ('FATAL EXCEPTION' in crashes and s.PACKAGE in crashes),crashes
    print('PASS: playback stats readout, rates against the file, verdicts on a fast and a slow network, sampling stops when hidden, and nothing when off')

if __name__=='__main__':main()

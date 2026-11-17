#!/usr/bin/env python3
"""Plex intro/credits markers offer manual skips on library and queue playback; emulator only.

Run after native_smoke.py, which connects the fixture QueuePilot server and its people filter."""
import smoke as s
import json,time,urllib.request

FIXTURE='http://127.0.0.1:9010/'
def fetch(path):return json.load(urllib.request.urlopen(FIXTURE+path))
def events():return fetch('_native_events')
def timelines():return [r for r in fetch('_requests') if r['path']=='/:/timeline' and r['query'].get('ratingKey')==['102']]
def wait_for(predicate,label,limit=40):
    for _ in range(limit*4):
        if predicate():return
        time.sleep(.25)
    raise AssertionError('Timed out waiting for '+label)
def focused():return next((n for n in s.screen().iter('node') if n.get('focused')=='true'),None)
def skip_button():return next((n for n in s.screen().iter('node') if n.get('content-desc') in ('Skip Intro','Skip Credits')),None)
def skip(label,shot):
    """Wait for the button, check that it took focus without stopping playback, and press it."""
    wait_for(lambda:(lambda n:n is not None and n.get('content-desc')==label)(skip_button()),label)
    f=focused();assert f is not None and f.get('content-desc')==label,('Skip must take focus',f.attrib if f is not None else None)
    s.shot(shot);s.adb('shell','input','keyevent','23')
    wait_for(lambda:(lambda n:n is None or n.get('content-desc')!=label)(skip_button()),label+' to hide')

def library_paused_at():
    known=len(timelines());s.adb('shell','input','keyevent','127')
    wait_for(lambda:any(r['query'].get('state')==['paused'] for r in timelines()[known:]),'paused timeline')
    return int([r for r in timelines()[known:] if r['query'].get('state')==['paused']][-1]['query']['time'][0])

def queue_paused_at():
    known=max((e['sequence'] for e in events()),default=-1);s.adb('shell','input','keyevent','127')
    wait_for(lambda:any(e['sequence']>known and e['state']=='paused' for e in events()),'paused event')
    return [e for e in events() if e['sequence']>known and e['state']=='paused'][-1]['positionMs']

assert s.adb('shell','getprop','ro.hardware').decode().strip() in ('ranchu','goldfish')
s.post('_online');s.post('_markers')

# Library playback: intro 6-14 s, mid-credits 18-21 s, final credits 25-30 s; resume at 4 s.
s.adb('shell','am','force-stop',s.PACKAGE);s.launch();s.click('Home')
if s.has_desc('Switch profile, Morgan'):s.click('Switch profile, Morgan');s.click('Ada');s.contains('What are we watching?')
s.click('Series');s.open_library('Series');s.click('Orbit Nine');s.click('Season 1');s.click('Orbit Nine, S1 · E9 · The signal');s.click('Play');s.answer_resume('Resume from 0:04')
skip('Skip Intro','marker-skip-intro')
at=library_paused_at();assert 14000<=at<18000,at;s.adb('shell','input','keyevent','126')
skip('Skip Credits','marker-skip-credits')
at=library_paused_at();assert 21000<=at<25000,at;s.adb('shell','input','keyevent','126')
known=len(timelines());skip('Skip Credits','marker-skip-final-credits')
# Final credits finish the title: Plex receives a stopped timeline past its watched threshold.
wait_for(lambda:any(r['query'].get('state')==['stopped'] for r in timelines()[known:]),'stopped timeline')
stopped=[r for r in timelines()[known:] if r['query'].get('state')==['stopped']][-1]['query']
assert int(stopped['time'][0])>=0.9*int(stopped['duration'][0]),stopped
s.back();s.contains('Watch with Ada')

# Queue playback: the same skips, and final credits complete the item and start the next one.
s.post('_native_reset');s.click('Home');s.click('Watching');s.contains('2 queues');s.click('Movies & Shows · 2. Ada, Grace. Picks. Plays as Ada. Play queue')
skip('Skip Intro','marker-queue-skip-intro')
at=queue_paused_at();assert 14000<=at<18000,at;s.adb('shell','input','keyevent','126')
skip('Skip Credits','marker-queue-skip-credits')
at=queue_paused_at();assert 21000<=at<25000,at;s.adb('shell','input','keyevent','126')
skip('Skip Credits','marker-queue-skip-final-credits')
wait_for(lambda:any(e['index']==0 and e['state']=='ended' for e in events()),'completed queue item')
ended=next(e for e in events() if e['index']==0 and e['state']=='ended')
assert ended['positionMs']>=0.9*ended['durationMs'],ended
assert not any(e['index']==0 and e['state']=='skip' for e in events()),'Final credits complete the item; they are not a skip'
wait_for(lambda:any(e['index']==1 and e['state']=='playing' for e in events()),'next queue item')
s.adb('shell','input','keyevent','127');s.contains('The return');s.shot('marker-queue-next-item')
s.back();s.contains('Your watching queues')
assert 'FATAL EXCEPTION' not in s.adb('logcat','-d','-b','crash').decode()
s.post('_online')
print('PASS: Skip Intro and Skip Credits take focus inside Plex markers, land after them on library and queue playback, and final credits complete the item (Plex stopped timeline past 90%, queue advances)',flush=True)

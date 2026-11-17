#!/usr/bin/env python3
"""A file this TV can't play directly: the Ask panel, conversion on the server through Plex's
universal transcoder, Stop, the refused case, and conversion as the queue's own profile;
emulator only.

Run after playback_smoke.py, with the fixture QueuePilot server still connected: its queue that
plays as Grace proves a conversion runs as the queue's profile."""
import smoke as s
import json,time,urllib.request

FIXTURE='http://127.0.0.1:9010/'
CONVERSION='/video/:/transcode/universal'
def fetch(path):return json.load(urllib.request.urlopen(FIXTURE+path))
def calls(name):return [r for r in fetch('_requests') if r['path']==CONVERSION+'/'+name]
def segments():return [r for r in fetch('_requests') if r['path'].startswith(CONVERSION+'/session/') and r['path'].endswith('.ts')]
def timelines():return [r for r in fetch('_requests') if r['path']=='/:/timeline' and r['query'].get('ratingKey')==['102']]
def wait_for(predicate,label,limit=30):
    for _ in range(limit*4):
        if predicate():return
        time.sleep(.25)
    raise AssertionError('Timed out waiting for '+label)
def nodes():return list(s.screen().iter('node'))
def open_episode():
    s.click('Home');s.click('Series');s.open_library('Series');s.click('Orbit Nine');s.click('Season 1')
    s.click('Orbit Nine, S1 · E9 · The signal')
def setting(label):
    s.click('Settings');s.click('Playback')
    s.click(next(n.get('content-desc') for n in nodes() if n.get('content-desc','').startswith("When a file can't play directly · ")))
    s.click(label);s.contains("When a file can't play directly · "+label);s.back();s.back()
def leave(marker):
    """Back out of the player, whose first Back may only hide its controls."""
    for _ in range(3):
        s.back();time.sleep(1)
        if marker in s.text():return
    s.contains(marker)
def playing_after(known,profile):
    wait_for(lambda:any(r['query'].get('state')==['playing'] and r['profile']==profile for r in timelines()[known:]),'playing timeline as '+profile)
    return [r for r in timelines()[known:] if r['query'].get('state')==['playing']][0]

assert s.adb('shell','getprop','ro.hardware').decode().strip() in ('ranchu','goldfish')
s.post('_online');s.adb('shell','am','force-stop',s.PACKAGE);s.launch();s.click('Home')
if not s.has_desc('Switch profile, Ada'):
    s.click(next(n.get('content-desc') for n in nodes() if n.get('content-desc','').startswith('Switch profile, ')));s.click('Ada')
s.contains('What are we watching?')

# The setting defaults to Ask.
s.click('Settings');s.click('Playback');assert s.has_desc("When a file can't play directly · Ask");s.back();s.back()

# 1. Ask, then convert: the panel names the reason; the converted stream plays from the resume
# point, as Ada, and leaving the player ends the conversion.
s.post('_unplayable');open_episode();s.click('Play');s.answer_resume('Resume from 0:04')
s.contains("This file can't play directly");s.contains("This TV can't open WMV files like this one.")
s.contains('Stop so I can fix the file');s.shot('conversion-ask')
known=len(timelines());s.click('Convert on the server')
wait_for(lambda:segments(),'converted segments')
decision=calls('decision')[-1];start=calls('start.m3u8')[-1]
assert decision['query']==start['query'],(decision,start)
q=start['query'];session=q['session'][0]
assert q['path']==['/library/metadata/102'] and q['protocol']==['hls'] and q['directPlay']==['0'] and q['subtitles']==['none'],q
assert q['X-Plex-Session-Identifier']==[session] and q['X-Plex-Client-Identifier'][0],q
assert 'offset' not in q and not any('Token' in k for k in q),q
assert all(r['profile']=='ada' for r in calls('decision')+calls('start.m3u8')+segments()),'conversion requests as Ada'
first=playing_after(known,'ada');assert int(first['query']['time'][0])>=3000,first
s.shot('conversion-playing') # the HLS segments and the timeline above prove the conversion plays
s.adb('shell','input','keyevent','127');time.sleep(1)
leave('Watch with Ada')
wait_for(lambda:any(r['query'].get('session')==[session] and r['profile']=='ada' for r in calls('stop')),'conversion stopped')

# 2. Ask, then stop: nothing is converted, and the reason stays on screen.
asked=len(calls('decision'));s.click('Play');s.answer_resume('Start from the beginning')
s.click('Stop so I can fix the file');s.contains('Stopped');s.contains("This TV can't open WMV files like this one.")
s.click('Close');s.contains('Watch with Ada');assert len(calls('decision'))==asked

# 3. The setting says Stop: no question, no conversion.
setting('Stop');open_episode();s.click('Play');s.answer_resume('Start from the beginning')
s.contains('Settings says to stop.');assert not s.has_desc('Convert on the server')
s.click('Close');s.contains('Watch with Ada');assert len(calls('decision'))==asked

# 4. The setting says Convert on the server: it converts without asking.
setting('Convert on the server');open_episode();known=len(timelines());starts=len(calls('start.m3u8'))
s.click('Play');s.answer_resume('Start from the beginning')
wait_for(lambda:len(calls('start.m3u8'))>starts,'conversion without a question')
playing_after(known,'ada');assert not s.has_desc('Stop so I can fix the file');leave('Watch with Ada')

# 5. Plex refuses: the viewer reads Plex's reason.
s.post('_conversion_refused');starts=len(calls('start.m3u8'))
s.click('Play');s.answer_resume('Start from the beginning')
s.contains("The server can't convert this file");s.contains('Conversion is turned off on this server.')
assert len(calls('start.m3u8'))==starts;s.shot('conversion-refused');s.click('Close');leave('Watch with Ada')
s.post('_online');s.post('_unplayable')

# 6. In a queue that plays as Grace, the conversion runs with the queue profile's grant.
s.post('_native_reset');s.click('Home');s.click('Watching');s.contains('Your watching queues')
if any(n.get('text')=='Clear' for n in nodes()):s.click('Clear')
s.contains('Plays as Grace')
queue=next((n.get('content-desc') for n in nodes() if 'Plays as Grace' in n.get('content-desc','') and n.get('content-desc','').endswith('Play queue')),None)
assert queue,'No queue plays as Grace'
# A queue item reports progress to its QueuePilot session, which reports to Plex as the queue
# profile; the player sends no Plex timeline of its own for it.
known=len(timelines());starts=len(calls('start.m3u8'));pieces=len(segments());s.click(queue)
wait_for(lambda:len(calls('start.m3u8'))>starts,'queue conversion')
wait_for(lambda:len(segments())>pieces,'queue converted segments')
assert all(r['profile']=='grace' for r in calls('decision')[-1:]+calls('start.m3u8')[starts:]+segments()[pieces:]),'queue conversion as Grace'
wait_for(lambda:any(e['setId']=='movies-grace' and e['index']==0 and e['state']=='playing' for e in fetch('_native_events')),"playing event in Grace's queue session")
assert len(timelines())==known,'a queue item sent its own Plex timeline'
leave('Your watching queues')
assert len(timelines())==known,'a queue item sent its own Plex timeline'

# Restore the default for the checks that follow.
setting('Ask')
assert 'FATAL EXCEPTION' not in s.adb('logcat','-d','-b','crash').decode()
s.post('_online');s.post('_native_reset')
print("PASS: a file that can't play directly asks with its reason, converts on the server from the resume point as the playing profile (library as Ada, queue as Grace), ends the conversion on leaving, stops when asked or when Settings says so, converts without asking when Settings says so, and shows Plex's refusal",flush=True)

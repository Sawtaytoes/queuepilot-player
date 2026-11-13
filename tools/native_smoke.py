#!/usr/bin/env python3
"""Native queue, audience, graphics, rewind and durable progress checks; emulator only."""
import smoke as s
import json,time,urllib.request,re

def events():return json.load(urllib.request.urlopen('http://127.0.0.1:9010/_native_events'))
def wait_for(predicate):
    for _ in range(50):
        if predicate():return
        time.sleep(.2)
    raise AssertionError('Expected playback event did not arrive')
def pause_and_resume():
    """Pause, resume, and return how far playback stepped back on resume."""
    known=max((e['sequence'] for e in events()),default=-1)
    s.adb('shell','input','keyevent','127')
    wait_for(lambda:any(e['sequence']>known and e['state']=='paused' for e in events()))
    paused=[e for e in events() if e['sequence']>known and e['state']=='paused'][-1]
    s.adb('shell','input','keyevent','126');wait_for(lambda:any(e['sequence']>paused['sequence'] and e['state']=='playing' for e in events()))
    resumed=next(e for e in events() if e['sequence']>paused['sequence'] and e['state']=='playing')
    print('Rewind on resume:',paused['positionMs'],'->',resumed['positionMs'],flush=True)
    return paused['positionMs']-resumed['positionMs']
assert s.adb('shell','getprop','ro.hardware').decode().strip() in ('ranchu','goldfish')
s.post('_online')
if 'Decoded video:' in s.text():s.back()
s.post('_native_reset')
if 'Who are these queues for?' in s.text():s.back()
s.click('Home')
if s.has_desc('Switch profile, Morgan'):s.click('Switch profile, Morgan');s.click('Ada');s.contains('What are we watching?')
s.click('Watching')
if 'Connect QueuePilot' in s.text():
    s.click('Connect QueuePilot')
    field=next(n for n in s.screen().iter('node') if n.get('class')=='android.widget.EditText');x1,y1,x2,y2=map(int,re.findall(r'\d+',field.get('bounds')));s.adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));s.adb('shell','input','text','http://10.0.2.2:9010');s.adb('shell','input','keyevent','4');s.click('Connect')
else:
    s.click('Choose people');s.click('All people')
s.contains('5 queues');s.shot('native-queues')
assert 'Manga' not in s.text() and 'Webtoons' not in s.text()
s.click('Choose people');s.click('Grace');s.click('Apply');s.contains('0 queues');s.contains('Showing 0 of 5 queues')
s.click('Choose people');s.click('Ada');s.click('Apply');s.contains('2 queues');s.contains('Showing 2 of 5 queues');s.shot('native-people-filter')
s.click('Movies & Shows · 2. Ada, Grace. Picks. Plays as Ada. Play queue');time.sleep(6)
# Rewind on resume is Off by default: resuming continues from the paused position.
assert abs(pause_and_resume())<650
s.adb('shell','input','keyevent','127');s.contains('S1 · E9 · The signal');s.contains('Orbit Nine');s.adb('shell','input','keyevent','19');# The episode has no Role entries; its cast and faces come from the show.
s.click('Cast');s.contains('Nadia Lane');s.contains('Captain Mira');s.contains('Omar Reed');s.contains('Lena Hart')
s.cast_photos('Nadia Lane','Omar Reed');s.shot('native-cast-faces');s.click('Close')
portraits=[r for r in json.load(urllib.request.urlopen('http://127.0.0.1:9010/_requests')) if r['path']=='/photo/:/transcode' and r['query'].get('url')==['https://images.example.com/portraits/omar.jpg']]
assert portraits and all(r['profile']=='ada' for r in portraits),'External portraits must go through the playing server'
focused=False
for _ in range(12):
    focus=next((n for n in s.screen().iter('node') if n.get('focused')=='true'),None)
    identity=focus.get('resource-id','') if focus is not None else ''
    if 'exo_rew' in identity:focused=True;break
    if 'exo_progress' in identity:key='20'
    elif any(part in identity for part in ['exo_settings','exo_ffwd','exo_play_pause']):key='21'
    elif 'exo_prev' in identity:key='22'
    else:key='20' if focus is not None else '19'
    s.adb('shell','input','keyevent',key)
assert focused,'Rewind must be reachable with the D-pad'
s.shot('native-rounded-controls')
s.post('_native_offline');s.adb('shell','input','keyevent','126');time.sleep(.5);s.adb('shell','input','keyevent','127');time.sleep(1)
s.contains('Progress is saved locally');encrypted=s.adb('shell','run-as',s.PACKAGE,'cat','shared_prefs/secrets.xml').decode();assert 'native-events' in encrypted and 'fixture-session-key' not in encrypted
def outbox_size():
    secrets=s.adb('shell','run-as',s.PACKAGE,'cat','shared_prefs/secrets.xml').decode()
    return len(re.search(r'<string name="native-events">([^<]*)</string>',secrets).group(1))
# A paused TV reports every 10 seconds. While QueuePilot is unreachable, each report must replace
# the waiting paused report instead of adding one (a report is about 700 encrypted characters).
before=outbox_size();time.sleep(25);after=outbox_size()
print('Offline outbox while paused:',before,'->',after,'characters',flush=True)
assert after-before<300,'Paused progress reports must not pile up in the outbox'
s.post('_online');s.adb('shell','input','keyevent','126');time.sleep(.5);s.adb('shell','input','keyevent','127');time.sleep(1)
s.click('Next queue item');time.sleep(3);s.adb('shell','input','keyevent','127');s.contains('S2 · E3 · The return');s.contains('Harbor Lights');s.shot('native-next-item');wait_for(lambda:any(e['index']==1 for e in events()))
# An episode's own guest credit comes first; the show's matching credit is not repeated.
s.adb('shell','input','keyevent','19');s.click('Cast');s.contains('Captain Mira (flashback)');s.contains('Omar Reed');s.cast_photos('Nadia Lane','Omar Reed')
assert [n.get('text') for n in s.screen().iter('node')].count('Nadia Lane')==1;s.shot('native-cast-guest');s.click('Close')
assert any(e['index']==0 and e['state']=='skip' for e in events())
s.adb('shell','input','keyevent','126');wait_for(lambda:'Your watching queues' in s.text());wait_for(lambda:any(e['index']==1 and e['state']=='ended' for e in events()))
s.adb('shell','am','force-stop',s.PACKAGE);s.launch();s.click('Watching');s.contains('2 queues')
# A stored choice wins over the Off default, then Off is restored for the following checks.
s.click('Settings');s.click('Rewind on resume · Off');s.click('3 seconds');s.contains('Rewind on resume · 3 seconds')
s.post('_native_reset');s.click('Watching');s.click('Movies & Shows · 2. Ada, Grace. Picks. Plays as Ada. Play queue');time.sleep(6)
assert abs(pause_and_resume()-3000)<650
s.adb('shell','input','keyevent','127');s.back();s.contains('Your watching queues')
s.click('Settings');s.click('Rewind on resume · 3 seconds');s.click('Off');s.contains('Rewind on resume · Off')
assert 'FATAL EXCEPTION' not in s.adb('logcat','-d','-b','crash').decode()
print('PASS: Plex-only queues, persistent people filter, profile-bound native playback, cast faces/roles, rounded controls, resume rewind Off by default and a stored 3-second choice, encrypted offline history retry, next-item playback and restart')

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
assert s.adb('shell','getprop','ro.hardware').decode().strip() in ('ranchu','goldfish')
s.post('_online')
if 'Decoded video:' in s.text():s.back()
s.post('_native_reset')
if 'Who are these queues for?' in s.text():s.back()
s.click('Home')
if '●  Morgan' in s.text():s.click('●  Morgan');s.click('Ada');s.contains('What are we watching?')
s.click('Watching')
if 'Connect QueuePilot' in s.text():
    s.click('Connect QueuePilot')
    field=next(n for n in s.screen().iter('node') if n.get('class')=='android.widget.EditText');x1,y1,x2,y2=map(int,re.findall(r'\d+',field.get('bounds')));s.adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));s.adb('shell','input','text','http://10.0.2.2:9010');s.adb('shell','input','keyevent','4');s.click('Connect')
else:
    s.click('Choose people');s.click('All people')
s.contains('2 Plex queues');s.shot('native-queues')
assert 'Manga' not in s.text() and 'Webtoons' not in s.text()
s.click('Choose people');s.click('Grace');s.click('Apply');s.contains('0 Plex queues')
s.click('Choose people');s.click('Ada');s.click('Apply');s.contains('1 Plex queues');s.shot('native-people-filter')
s.click('Movies & Shows · 2. Play queue');time.sleep(6);s.adb('shell','input','keyevent','127')
wait_for(lambda:any(e['state']=='paused' for e in events()))
paused=[e for e in events() if e['state']=='paused'][-1]
s.adb('shell','input','keyevent','126');wait_for(lambda:any(e['sequence']>paused['sequence'] and e['state']=='playing' for e in events()))
resumed=next(e for e in events() if e['sequence']>paused['sequence'] and e['state']=='playing')
assert abs((paused['positionMs']-resumed['positionMs'])-3000)<650,(paused,resumed)
s.adb('shell','input','keyevent','127');s.contains('The signal');s.adb('shell','input','keyevent','19');s.click('Cast');s.contains('Nadia Lane');s.contains('Captain Mira');s.shot('native-cast-faces');s.click('Close')
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
s.post('_online');s.adb('shell','input','keyevent','126');time.sleep(.5);s.adb('shell','input','keyevent','127');time.sleep(1)
s.click('Next queue item');time.sleep(3);s.adb('shell','input','keyevent','127');s.contains('The return');s.shot('native-next-item');wait_for(lambda:any(e['index']==1 for e in events()))
assert any(e['index']==0 and e['state']=='skip' for e in events())
s.adb('shell','input','keyevent','126');wait_for(lambda:'Your watching queues' in s.text());wait_for(lambda:any(e['index']==1 and e['state']=='ended' for e in events()))
s.adb('shell','am','force-stop',s.PACKAGE);s.launch();s.click('Watching');s.contains('1 Plex queues')
assert 'FATAL EXCEPTION' not in s.adb('logcat','-d','-b','crash').decode()
print('PASS: Plex-only queues, persistent people filter, profile-bound native playback, cast faces/roles, rounded controls, 3-second resume rewind, encrypted offline history retry, next-item playback and restart')

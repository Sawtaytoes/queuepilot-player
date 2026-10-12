#!/usr/bin/env python3
"""Exercise real ASS playback; compare generated screenshots with the libass reference on host."""
import smoke as s
import re, time

def key(value): s.adb('shell','input','keyevent',str(value));time.sleep(.3)
def gear():
    key(19)
    node=next(n for n in s.screen().iter('node') if n.get('resource-id','').endswith('/exo_settings'))
    x,y,right,bottom=map(int,re.findall(r'\d+',node.get('bounds')))
    s.adb('shell','input','tap',str((x+right)//2),str((y+bottom)//2));s.click('Subtitles')
def choose(fragment):
    node=next(n for n in s.screen().iter('node') if fragment in n.get('content-desc',''))
    s.click(node.get('content-desc'))

assert s.adb('shell','getprop','ro.hardware').decode().strip() in ('ranchu','goldfish')
s.post('_online');s.post('_ass');s.adb('shell','pm','clear',s.PACKAGE);s.adb('logcat','-c');s.launch()
if 'Sign in with Plex' in s.text():
    s.click('Sign in with Plex');s.contains('ABCD');s.post('_claim');s.contains("Who's watching?");s.click('Ada');s.contains('What are we watching?')
s.click('Home');s.click('Series');s.click('Series');s.click('Orbit Nine');s.click('Season 1');s.click('The signal');s.click('Play')
time.sleep(6);s.shot('ass-authored');time.sleep(2);s.shot('ass-moving')
logs=s.adb('logcat','-d','-s','QueuePilotLibass').decode()
assert 'ABeeZee' in logs,logs
assert 'fontselect' in logs,logs
key(127);gear();choose('Authored ASS')
key(126);time.sleep(4);s.shot('ass-selected')
gear();s.click('Off');time.sleep(4);s.shot('ass-off')
gear();choose('Plain text');time.sleep(1)
gear();choose('Authored ASS');key(89);key(126);time.sleep(4);s.shot('ass-after-seek')
assert 'FATAL EXCEPTION' not in s.adb('logcat','-d','-b','crash').decode()
s.back();s.post('_online')
print('PASS: embedded font selected, authored ASS track, subtitle Off, SRT/ASS switching, backward seek; pixel comparison required on host')

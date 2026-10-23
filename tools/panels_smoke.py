#!/usr/bin/env python3
"""Real TV menus: placement, focus, persisted choices and playback track changes."""
import smoke as s
import re,time

def bounds(node):return tuple(map(int,re.findall(r'\d+',node.get('bounds'))))
def focused():return next((n for n in s.screen().iter('node') if n.get('focused')=='true'),None)
def selected():return [n.get('content-desc') for n in s.screen().iter('node') if n.get('selected')=='true' and n.get('content-desc')]
def key(value):s.adb('shell','input','keyevent',str(value));time.sleep(.2)
def node_id(suffix):return next(n for n in s.screen().iter('node') if n.get('resource-id','').endswith('/'+suffix))
def open_gear():
    key(19)
    box=bounds(node_id('exo_settings'))
    s.adb('shell','input','tap',str((box[0]+box[2])//2),str((box[1]+box[3])//2));s.contains('Playback speed')
    return box

def anchored(box):
    root=s.screen();panel=bounds(next(root.iter('node')))
    assert panel[3] <= box[1],('Panel must end above launching bottom control',panel,box)
    assert box[1]-panel[3]<70,('Panel must stay near control',panel,box)
    assert panel[2]>=box[0]-50,('Panel must align with control',panel,box)
    assert panel[0]>0 and panel[1]>0 and panel[2]<1920 and panel[3]<1080,panel

def gear_focus():
    f=focused();assert f is not None and f.get('resource-id','').endswith('/exo_settings'),str(f.attrib if f is not None else None)

assert s.adb('shell','getprop','ro.hardware').decode().strip() in ('ranchu','goldfish')
s.post('_online')
for density in (240,320):
    s.adb('shell','wm','density',str(density));time.sleep(1);s.launch();s.click('Home');s.click('Settings')
    installed=re.search(r'versionName=(\S+)',s.adb('shell','dumpsys','package',s.PACKAGE).decode()).group(1)
    s.contains('Playback preview · '+installed)
    # Rewind on resume defaults to Off; an explicitly stored choice survives a restart.
    s.click('Rewind on resume · Off');s.contains('Replay a little');assert selected()==['Off'],selected()
    f=focused();assert f is not None and f.get('content-desc')=='Off'
    panel=bounds(next(s.screen().iter('node')));assert panel[0]>960 and panel[2]<1920,panel
    s.shot('panel-rewind-'+str(density));key(20);s.back();s.contains('Rewind on resume · Off')
    assert focused().get('text')=='Rewind on resume · Off'
    s.click('Rewind on resume · Off');key(20);key(23);s.contains('Rewind on resume · 1 second')
    s.adb('shell','am','force-stop',s.PACKAGE);s.launch();s.click('Settings');s.click('Rewind on resume · 1 second');assert selected()==['1 second']
    key(19);key(23);s.contains('Rewind on resume · Off')
    s.post('_tracks');s.click('Series');s.click('Series');s.click('Orbit Nine');s.click('Season 1');s.click('Orbit Nine, S1 · E9 · The signal');s.click('Play');time.sleep(2);key(127)
    box=open_gear();anchored(box);assert focused().get('content-desc')=='Audio';time.sleep(4);s.shot('panel-playback-'+str(density));s.back();gear_focus()
    box=open_gear();s.click('Playback speed');anchored(box);assert selected()==['Normal · 1×'];s.click('1.5×');gear_focus()
    box=open_gear();s.click('Playback speed');assert selected()==['1.5×'];s.shot('panel-speed-'+str(density));s.click('Normal · 1×')
    box=open_gear();s.click('Audio');anchored(box);assert selected()==['Automatic'];s.contains('Commentary')
    row=next(n for n in s.screen().iter('node') if 'Commentary' in n.get('content-desc',''));s.click(row.get('content-desc'))
    open_gear();s.click('Audio');assert len(selected())==1 and 'Commentary' in selected()[0],selected();s.shot('panel-audio-'+str(density));s.back();gear_focus()
    box=open_gear();s.click('Subtitles');anchored(box);s.contains('Signs and songs')
    row=next(n for n in s.screen().iter('node') if 'Signs and songs' in n.get('content-desc',''));s.click(row.get('content-desc'))
    open_gear();s.click('Subtitles');assert len(selected())==1 and 'Signs and songs' in selected()[0],selected();s.shot('panel-subtitles-'+str(density));s.click('Off')
    open_gear();s.click('Subtitles');assert selected()==['Off'];s.back();gear_focus();s.back();s.contains('Watch with Ada')
s.post('_unnamed_tracks');s.click('Play');time.sleep(2);key(127)
open_gear();s.click('Audio')
choices=[n.get('content-desc') for n in s.screen().iter('node') if ' · Track ' in n.get('content-desc','')]
assert len(choices)==2 and choices[0]!=choices[1],choices
s.click(choices[1]);open_gear();s.click('Audio');assert selected()==[choices[1]];s.back();s.back()
assert 'FATAL EXCEPTION' not in s.adb('logcat','-d','-b','crash').decode()
s.post('_online')
print('PASS: 240/320dpi side panels, rewind default Off and persisted choice, D-pad selection/cancel, control-anchored playback menus, focus return, speed/audio/subtitle switching and subtitle Off')

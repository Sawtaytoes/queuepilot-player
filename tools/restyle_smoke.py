#!/usr/bin/env python3
"""Look C and the automatic Display size, screen by screen; emulator only.

Run after queue_history_smoke.py, signed in as Ada with the fixture QueuePilot server
connected. Enters a 97 in screen at 6.5 ft, checks that Auto reads 65% in the panel and in
Settings and that the Films grid gains columns, then photographs Home, the queue cards, the
full queue, the player controls, and Settings -> Display size at Auto and at 100%. Checks the
focus ring is the theme's lime. Leaves Display size at 100% for the checks after it.
"""
import smoke as s
import json,re,time,urllib.request

FIXTURE='http://127.0.0.1:9010'
RING=(163,207,104)  # TvTheme.FOCUS_RING, categorical.4.content
RAIL=('Home','Watching','History','Series','Movies','Search','Settings')  # focus fills these instead
assert s.adb('shell','getprop','ro.hardware').decode().strip() in ('ranchu','goldfish')
s.adb('shell','wm','size','reset');s.adb('shell','wm','density','reset')
width,height=map(int,re.findall(r'(\d+)x(\d+)',s.adb('shell','wm','size').decode())[-1])

def get(path):return json.load(urllib.request.urlopen(FIXTURE+path,timeout=5))
def nodes():return list(s.screen().iter('node'))
def texts():return [n.get('text','') for n in nodes()]
def bounds(node):return tuple(map(int,re.findall(r'-?\d+',node.get('bounds'))))
def key(code,times=1):
    for _ in range(times):s.adb('shell','input','keyevent',str(code));time.sleep(.25)
def wait(check,what,tries=30):
    for _ in range(tries):
        found=check()
        if found:return found
        time.sleep(.5)
    raise AssertionError('Timed out waiting for '+what+'\n'+'\n'.join(t for t in texts() if t))
def wait_text(value):return wait(lambda:value in texts(),value)
def size_label():
    s.click('Settings')
    return wait(lambda:next((t for t in texts() if t.startswith('Display size · ')),None),'Display size')
def open_panel():
    s.click(size_label());wait_text('Display size');s.contains('Make everything smaller')
def press(prefix):
    """Moves focus up to the control whose spoken name starts with prefix and selects it."""
    for _ in range(25):
        focused=next((n for n in nodes() if n.get('focused')=='true'),None)
        if focused is not None and focused.get('content-desc','').startswith(prefix):break
        key(19)
    else:raise AssertionError('Could not focus '+prefix)
    key(23)
def measure(prefix,value,expected):
    """Enters a screen size or distance with the remote, as a viewer does; the panel reopens
    with Auto recomputed. Up from the selected size reaches the two buttons above the list."""
    press(prefix);field=wait(lambda:[n for n in nodes() if n.get('class')=='android.widget.EditText'],'the number field')[0]
    assert field.get('focused')=='true',field.attrib
    s.adb('shell','input','text',value);key(66)
    wait(lambda:any(t.startswith('Auto · ') for t in texts()) and 'Display size' in texts(),'the Display size panel again')
    wait_text(expected)
def choose(label,expected_setting):
    open_panel();s.click(label);wait_text(expected_setting)
def ring_at(node):
    x1,y1,x2,y2=bounds(node);image=s.pixels(s.adb('exec-out','screencap','-p'));w,h,step,rows=image
    row=rows[(y1+y2)//2];pixel=tuple(row[(x1+1)*step:(x1+1)*step+3])
    return all(abs(a-b)<=18 for a,b in zip(pixel,RING)),pixel
import xml.etree.ElementTree as ET
_request=urllib.request.Request(FIXTURE+'/library/sections/2/all',headers={'X-Plex-Token':'fixture-ada'})
FILMS=[v.get('title') for v in ET.fromstring(urllib.request.urlopen(_request,timeout=5).read()).iter('Video')]
def films_columns():
    """Open the invented Films library and count the posters in its first row."""
    s.click('Movies');s.click('Films')
    posters=wait(lambda:(lambda found:found if len(found)>=4 else None)([n for n in nodes() if n.get('content-desc') in FILMS]),'film posters')
    top=min(bounds(n)[1] for n in posters)
    return len([n for n in posters if bounds(n)[1]==top])

s.post('_online');s.post('_native_reset')
s.adb('shell','am','force-stop',s.PACKAGE);s.launch();s.click('Home');s.contains('What are we watching?')
if s.has_desc('Switch profile, Morgan'):s.click('Switch profile, Morgan');s.click('Ada');s.contains('What are we watching?')

# Auto from the screen and the distance: 97 in at 6.5 ft is 65%.
open_panel()
measure('Screen size, ','97','Screen · 97 in')
measure('Viewing distance, ','6.5','Distance · 6.5 ft')
wait_text('Auto · 65%')
assert texts().count('50%')+texts().count('125%')>=1,'The manual sizes run from 50% to 125%'
press('Auto · 65%');wait_text('Display size · Auto · 65%')
state=s.app_file('shared_prefs/state.xml');assert state is None or '<int name="display-size" value="0" />' in state

columns={}
for mode,setting in (('auto','Display size · Auto · 65%'),('100','Display size · 100%')):
    if mode=='100':choose('100%',setting)
    assert size_label()==setting,(size_label(),setting)
    open_panel();time.sleep(.6);s.shot(f'restyle-{mode}-settings-display-size');s.click('Cancel')
    s.click('Home');s.contains('What are we watching?');key(22);time.sleep(1);s.shot(f'restyle-{mode}-home')
    s.click('Watching');wait_text('Your watching queues')
    if 'Clear' in texts():s.click('Clear')
    wait_text('Plays as Ada')
    # Right leaves the rail if a tap left focus there, and Down reaches the first queue card;
    # Left is never pressed, as it opens the rail over the content.
    key(22);key(20);time.sleep(.5)
    focused=next((n for n in nodes() if n.get('focused')=='true'),None)
    if focused is not None and focused.get('content-desc') not in RAIL and not focused.get('content-desc','').startswith('Switch profile'):
        is_ring,pixel=ring_at(focused);assert is_ring,(f'The focus ring is not the theme lime at {mode}',pixel,focused.get('content-desc'))
    s.shot(f'restyle-{mode}-queue-cards')
    s.click('Full queue. Movies & Shows. Ada');wait_text('Orbit Nine · Season 1');time.sleep(.8)
    s.shot(f'restyle-{mode}-full-queue')
    s.click('7. Harbor Lights, S2 · E3 · The return. Not watched');s.click('Play only this item')
    time.sleep(3);key(127);wait_text('S2 · E3 · The return');time.sleep(.8)
    s.shot(f'restyle-{mode}-player')
    s.back();wait(lambda:'Hide 3 watched items' in texts() or 'Show 3 watched items' in texts(),'the full queue again')
    s.click('Home');s.contains('What are we watching?')
    columns[mode]=films_columns();s.shot(f'restyle-{mode}-films');s.click('Home')
assert columns['auto']>columns['100'],('Auto at 65% must show more columns than 100%',columns)
assert 'FATAL EXCEPTION' not in s.adb('logcat','-d','-b','crash').decode()
print(f'PASS: Auto is 65% for 97 in at 6.5 ft and persists as Auto, Films shows {columns["auto"]} columns at Auto and {columns["100"]} at 100%, the focus ring is lime, and the restyle screens were captured at both sizes',flush=True)

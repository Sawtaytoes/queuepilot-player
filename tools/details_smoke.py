#!/usr/bin/env python3
"""Title facts, the show header, Lucide player controls, and BIF seek thumbnails; emulator only.

Run after rail_smoke.py, signed in as the fixture account."""
import smoke as s
import json,re,subprocess,time,urllib.request

FIXTURE='http://127.0.0.1:9010/'
def requests(path):return [r for r in json.load(urllib.request.urlopen(FIXTURE+'_requests')) if r['path']==path]
def nodes():return list(s.screen().iter('node'))
def bounds(node):return tuple(map(int,re.findall(r'-?\d+',node.get('bounds'))))
def focused():return next((n for n in nodes() if n.get('focused')=='true'),None)
def wait(check,what,limit=30):
    for _ in range(limit):
        found=check()
        if found:return found
        time.sleep(.3)
    raise AssertionError('Timed out waiting for '+what+'\n'+s.text())

assert s.adb('shell','getprop','ro.hardware').decode().strip() in ('ranchu','goldfish')
s.post('_online');s.adb('shell','wm','size','reset');s.adb('shell','wm','density','reset')
width,height=map(int,re.findall(r'(\d+)x(\d+)',s.adb('shell','wm','size').decode())[-1])
s.adb('shell','am','force-stop',s.PACKAGE);s.launch();s.click('Home');s.contains('What are we watching?')
if s.has_desc('Switch profile, Morgan'):s.click('Switch profile, Morgan');s.click('Ada');s.contains('What are we watching?')

# A film's title screen: the facts line, genres, scores, time left, credits, and a cast row.
s.click('Movies');s.click('Films');s.click('Glass City');s.contains('Watch with Ada')
for line in ('2024 · PG · 1 h 52 min · Lantern Pictures','Drama, Mystery','Critics 8.1 · Audience 7.6','38 min left','Directed by Ines Cole · Written by Theo Marsh'):
    s.contains(line)
s.contains('Cast');s.cast_photos('Mara Quill','Omar Reed')
assert any(n.get('content-desc')=='Mara Quill. The courier' for n in nodes())
s.shot('details-facts-film')

# A show's season list starts with the show's own facts, progress, and summary.
s.back();s.click('Series');s.open_library('Series');s.click('Orbit Nine')
s.contains('2021 · TV-PG · 1 season · 12 episodes · Northlight Studio');s.contains('3 of 12 episodes watched')
s.contains('A relay crew keeps a distant station talking to home.');s.contains('Season 1')
s.shot('details-facts-show')

# Player controls are icon buttons, each with a spoken name.
BIF='/library/parts/1102/indexes/sd';fetched=len(requests(BIF))
s.click('Season 1');s.click('Orbit Nine, S1 · E9 · The signal');s.click('Play');s.answer_resume('Resume from 0:04')
time.sleep(2);s.adb('shell','input','keyevent','127')
CONTROLS=('exo_prev','exo_rew_with_amount','exo_play_pause','exo_ffwd_with_amount','exo_next','exo_subtitle','exo_settings')
def controls():
    found={n.get('resource-id','').split(':id/')[-1]:n for n in nodes() if n.get('resource-id','').split(':id/')[-1] in CONTROLS}
    return found if len(found)==len(CONTROLS) else None
buttons=wait(controls,'player controls')
unnamed=[name for name,n in buttons.items() if not n.get('content-desc')]
assert not unnamed,('Every player control needs a spoken name',unnamed)
# The icon is the control's only visible mark: none of them carries a text label, except the
# seek interval drawn inside the rewind and fast-forward icons.
labeled=[name for name,n in buttons.items() if n.get('text') and not n.get('text').isdigit()]
assert not labeled,('Player controls are icons, not words',labeled)
assert buttons['exo_play_pause'].get('content-desc') in ('Play','Pause'),buttons['exo_play_pause'].attrib
s.shot('player-controls-icons')
assert len(requests(BIF))==fetched,'Seek thumbnails are fetched only when the viewer seeks'

# D-pad seeking on the time bar shows the frame for that time above the bar, inside the screen.
for _ in range(8):
    node=focused()
    if node is not None and node.get('resource-id','').endswith(':id/exo_progress'):break
    # The bar sits between the center controls and the bottom bar: Down from above, Up from below.
    is_below=node is not None and node.get('resource-id','').split(':id/')[-1] in ('exo_subtitle','exo_settings')
    s.adb('shell','input','keyevent','19' if is_below else '20');time.sleep(.3)
else:raise AssertionError('The time bar never took focus: '+str(focused().attrib if focused() is not None else None))
bar=bounds(next(n for n in nodes() if n.get('resource-id','').endswith(':id/exo_progress')))
# Media3 ends a D-pad scrub one second after the last press, so presses continue while the
# hierarchy is read.
presses=subprocess.Popen(['adb','-s',s.SERIAL,'shell','for i in 1 2 3 4 5 6 7 8 9 10 11 12; do input keyevent 22; sleep 0.3; done'])
try:
    preview=wait(lambda:next((n for n in nodes() if (n.get('content-desc') or '').startswith('Seek preview, ') and 'frame' in n.get('content-desc')),None),'a seek preview with a frame',limit=20)
    s.shot('player-seek-preview')
finally:presses.wait()
spoken=preview.get('content-desc');match=re.search(r'Seek preview, (\d+):(\d\d), frame (\d+) of 7$',spoken)
assert match,spoken
seconds,frame=int(match[1])*60+int(match[2]),int(match[3])
assert frame==min(7,seconds//5+1),('The frame must match the time',spoken)
x1,y1,x2,y2=bounds(preview)
assert 0<=x1<x2<=width and 0<=y1<y2<=bar[1],('The preview sits above the bar, inside the screen',(x1,y1,x2,y2),bar)
assert len(requests(BIF))==fetched+1,'The BIF is fetched once, on the first seek'
wait(lambda:not any((n.get('content-desc') or '').startswith('Seek preview') for n in nodes()),'the preview to hide')
s.back();s.back();s.back();s.back()
assert 'FATAL EXCEPTION' not in s.adb('logcat','-d','-b','crash').decode()
print(f'PASS: film and show details show facts, scores, progress, credits, and cast photos; player controls are named icon buttons; D-pad seeking shows BIF frame {frame} of 7 at {seconds} s above the bar, fetched once on first seek',flush=True)

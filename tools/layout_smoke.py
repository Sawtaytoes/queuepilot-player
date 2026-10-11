#!/usr/bin/env python3
"""Capture native TV layout at the emulator's physical resolution; emulator only."""
import smoke as s
import re,struct,sys
from pathlib import Path
assert s.adb('shell','getprop','ro.hardware').decode().strip() in ('ranchu','goldfish')
shield_scale='--density-320' in sys.argv
s.adb('shell','wm','size','1920x1080' if shield_scale else 'reset');s.adb('shell','wm','density','320' if shield_scale else 'reset')
width,height=map(int,re.findall(r'(\d+)x(\d+)',s.adb('shell','wm','size').decode())[-1])
assert (width,height) in ((1920,1080),(3840,2160))
label='1080p-320dpi' if shield_scale else ('4k' if width==3840 else '1080p')
s.adb('shell','pm','clear',s.PACKAGE);s.launch();s.contains('Your people. Your queues.')
s.click('Explore the demo');s.contains('What are we watching?')
s.adb('shell','input','keyevent','21');s.adb('shell','input','keyevent','22')
s.shot('queue-home-'+label)
png=Path('/fixtures/queue-home-'+label+'.png').read_bytes();assert struct.unpack('>II',png[16:24])==(width,height)
assert not any(n.get('class')=='android.widget.EditText' for n in s.screen().iter('node'))
for _ in range(4):
    s.adb('shell','input','swipe',str(int(width*.9)),str(int(height*.88)),str(int(width*.9)),str(int(height*.25)),'600')
    if 'After the Rain' in s.text():break
s.contains('After the Rain');s.shot('library-demo-'+label)
print(f'PASS: native {width}x{height} home and artwork browsing',flush=True)

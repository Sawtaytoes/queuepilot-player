#!/usr/bin/env python3
"""The collapsing icon rail; emulator only.

Run after display_smoke.py, signed in, at 100% Display size. Pass --density-320 for a 1080p
panel at density 320; without it the emulator's own density is used.
"""
import smoke as s
import re,sys,time

ENTRIES=['Home','Watching','History','Series','Movies','Search','Settings']
COLLAPSED,EXPANDED=76,186
assert s.adb('shell','getprop','ro.hardware').decode().strip() in ('ranchu','goldfish')
is_shield='--density-320' in sys.argv
s.adb('shell','wm','size','1920x1080' if is_shield else 'reset');s.adb('shell','wm','density','320' if is_shield else 'reset')
density=int(re.findall(r'(\d+)',s.adb('shell','wm','density').decode())[-1])/160
label='320dpi' if is_shield else 'native'

def nodes():return list(s.screen().iter('node'))
def bounds(node):return tuple(map(int,re.findall(r'-?\d+',node.get('bounds'))))
def rail(root=None):
    root=root if root is not None else s.screen()
    return next(n for n in root.iter('node') if (n.get('content-desc') or '').startswith('Navigation'))
def focused():
    return next((n for n in s.screen().iter('node') if n.get('focused')=='true'),None)
def is_focus_in_rail():
    root=s.screen()
    return any(n.get('focused')=='true' for n in rail(root).iter('node'))
def focus_becomes(desc):
    return wait(lambda:(lambda n:n is not None and n.get('content-desc')==desc)(focused()),'focus on '+desc)
def key(code,times=1):
    for _ in range(times):s.adb('shell','input','keyevent',str(code));time.sleep(.25)
def wait(check,what):
    for _ in range(20):
        found=check()
        if found:return found
        time.sleep(.3)
    raise AssertionError('Timed out waiting for '+what+'\n'+s.text())
def state(expected):
    """The rail's spoken state and its width once the 120 ms animation has finished."""
    def check():
        root=s.screen();node=rail(root);x1,_,x2,_=bounds(node)
        return (node.get('content-desc')=='Navigation, '+expected,root,x2-x1)
    return wait(lambda:(lambda r:r if r[0] else None)(check()),'the rail to be '+expected)

s.adb('shell','am','force-stop',s.PACKAGE);s.launch();s.click('Home');s.contains('What are we watching?')
if s.has_desc('Switch profile, Morgan'):s.click('Switch profile, Morgan');s.click('Ada');s.contains('What are we watching?')
key(22,2)  # leave touch mode; focus moves through the queue cards

# Collapsed while focus is in the content: icons only, every entry still named.
_,root,rail_width=state('collapsed')
assert abs(rail_width-COLLAPSED*density)<=4,(rail_width,COLLAPSED*density)
# The labels are hidden, so anything drawn inside a collapsed entry is its icon.
image=s.pixels(s.adb('exec-out','screencap','-p'))
for entry in ENTRIES:
    node=next(n for n in rail(root).iter('node') if n.get('content-desc')==entry)
    covered=s.ink(image,bounds(node))
    assert covered>0.02,(entry+' draws no icon',covered)
texts=[n.get('text') for n in rail(root).iter('node') if n.get('text')]
assert texts==['◉'],('Labels must be hidden while collapsed',texts)
s.shot('rail-collapsed-'+label)

# Moving left onto the rail lands on the selected entry, not the nearest one, and expands it.
for _ in range(6):
    key(21)
    if is_focus_in_rail():break
focus_becomes('Home')
_,root,rail_width=state('expanded')
assert abs(rail_width-EXPANDED*density)<=4,(rail_width,EXPANDED*density)
texts=[n.get('text') for n in rail(root).iter('node') if n.get('text')]
assert all(e in texts for e in ENTRIES),texts
s.shot('rail-expanded-'+label)

# The avatar stays reachable below the entries.
key(20,len(ENTRIES));focus_becomes('Switch profile, Ada')
# Right returns to the content and collapses the rail; Back from the content returns to it.
key(22);state('collapsed');assert not is_focus_in_rail()
key(4);focus_becomes('Home');state('expanded')

# Choose Settings from the rail by remote; the next visit to the rail returns to Settings.
key(20,len(ENTRIES)-1);focus_becomes('Settings');key(23)
s.contains('Display size');state('collapsed')
key(21,3);focus_becomes('Settings');state('expanded')
key(22);state('collapsed')
s.adb('shell','wm','size','reset');s.adb('shell','wm','density','reset')
assert 'FATAL EXCEPTION' not in s.adb('logcat','-d','-b','crash').decode()
print('PASS: rail is icons-only and collapsed in content, expands on the selected entry, keeps the avatar reachable, returns on Back, and every entry has an icon and a name',flush=True)

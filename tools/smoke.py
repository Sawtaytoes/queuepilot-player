#!/usr/bin/env python3
"""Exercise the real TV UI against invented Plex authorization and media fixtures."""
import subprocess,time,re,xml.etree.ElementTree as ET,urllib.request,json
from pathlib import Path
SERIAL='emulator-5554'
PACKAGE='dev.queuepilot.player.prototype'
def adb(*args):return subprocess.check_output(['adb','-s',SERIAL,*args],stderr=subprocess.STDOUT,timeout=30)
def app_file(name):
    """A file in the app's data directory, or None for a release build. run-as reads only a
    debuggable build, and the lab's emulator image does not allow adb root. Checks of a release
    build skip what they would read here and keep what they see on screen."""
    try:return adb('shell','run-as',PACKAGE,'cat',name).decode()
    except subprocess.CalledProcessError:
        if is_debuggable():raise
        print('SKIP: a release build does not let the checks read '+name,flush=True)
        return None
def is_debuggable():
    return 'DEBUGGABLE' in adb('shell','dumpsys','package',PACKAGE).decode().split('pkgFlags=')[1].split('\n')[0]
def dump():
    """The active window's UI hierarchy, from the emulator-only inspector."""
    hardware=adb('shell','getprop','ro.hardware').decode().strip()
    assert hardware in ('ranchu','goldfish'),'Inspector must never target a physical TV'
    result=adb('shell','am','instrument','-w','-r','dev.queuepilot.inspector/.Dump').decode()
    match=re.search(r'INSTRUMENTATION_RESULT: xml=(.*)',result)
    if not match: raise AssertionError('UI hierarchy unavailable: '+result)
    return ET.fromstring(match.group(1))
def screen(recovered=0):
    """The app's screen. When the TV launcher has come to the front, see return_from_launcher."""
    root=dump()
    if not window_package(root).startswith(LAUNCHER_PREFIXES):return root
    # Every attempt counts, including one skipped because the app came back on its own.
    if recovered>=3:raise AssertionError('The TV launcher was in front of the app on three recovery attempts.\n'+shown(root))
    return_from_launcher(root);return screen(recovered+1)
# The Android TV launcher can come to the front over the running app after it is stopped and
# started: a one-time promotion ("Free live TV channels", "Buy and rent movies on your TV"), or
# its own home screen with notice cards. Its tabs share names with the app's controls ("Home"),
# so a check must never tap it, and a check knows the launcher by its window's package, never by
# a label. Recovery happens only while the app's own screen and process still exist beneath the
# launcher, and must bring back that same screen and process. Anything else means the app
# stopped, and the check fails, so a crash is never hidden.
PROMOTION_BUTTONS=('Continue','Dismiss','Not now','No thanks')
LAUNCHER_PREFIXES=('com.google.android.tv','com.google.android.apps.tv')
def window_package(root):
    node=next(root.iter('node'),None)
    return node.get('package','') if node is not None else ''
def shown(root):return '\n'.join(n.get('text','') for n in root.iter('node') if n.get('text','').strip())
def focused_package():
    found=re.search(r'mCurrentFocus=Window\{\S+ \S+ ([^/\s}]+)',adb('shell','dumpsys window | grep -m1 mCurrentFocus; true').decode())
    return found.group(1) if found else ''
def focused_window():
    """The focused window as dumpsys names it, best effort: a diagnostic must never stop recovery."""
    try:return adb('shell','dumpsys window | grep -m1 mCurrentFocus; true').decode().strip()
    except (subprocess.CalledProcessError,subprocess.TimeoutExpired) as error:return f'unavailable ({type(error).__name__})'
def app_process():
    """The app's process id, or '' when it is not running."""
    return adb('shell','pidof '+PACKAGE+'; true').decode().strip()
def app_activity():
    """The identity of the app's live screen in the activity manager, or '' when it has none."""
    found=re.search(r'ActivityRecord\{(\w+) u0 '+re.escape(PACKAGE)+r'/\S*MainActivity',adb('shell','dumpsys activity activities').decode())
    return found.group(1) if found else ''
def return_from_launcher(root):
    for _ in range(6):
        # The launcher can hold focus for a moment while the app comes forward.
        if not focused_package().startswith(LAUNCHER_PREFIXES):return
        time.sleep(.5)
    root=dump()
    # Act only on a launcher window that is still in front: the app may have just come back,
    # and its own buttons share names with the promotion's ("Continue").
    if not window_package(root).startswith(LAUNCHER_PREFIXES) or not focused_package().startswith(LAUNCHER_PREFIXES):return
    before,process=app_activity(),app_process()
    if not before or not process:
        raise AssertionError('The TV launcher is in front and the app screen or its process is gone; the app stopped.\n'+shown(root))
    button=next((n for n in root.iter('node') if n.get('text') in PROMOTION_BUTTONS),None)
    first=next((n.get('text') for n in root.iter('node') if n.get('text','').strip()),'')
    print('Launcher window:',focused_window(),flush=True) # a diagnostic sample; see docs/testing.md
    if button is None:print('The TV launcher came to the front:',first,flush=True)
    elif not focused_package().startswith(LAUNCHER_PREFIXES):
        # The app came back before the tap: skip it, but the checks below still prove it is the
        # same screen and process.
        print('The app came back before the launcher promotion was answered:',first,flush=True)
    else:
        print('Dismiss the TV launcher promotion:',first,'·',button.get('text'),flush=True)
        x1,y1,x2,y2=map(int,re.findall(r'\d+',button.get('bounds')))
        adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(1)
    if focused_package()!=PACKAGE:launch() # the same intent brings the existing task forward
    for _ in range(6):
        if focused_package()==PACKAGE:break
        time.sleep(.5)
    else:raise AssertionError('The app did not return after the TV launcher came to the front.\n'+shown(dump()))
    # Android keeps a stopped screen's record after its process dies and restarts the process
    # under it, so the record alone cannot prove the app survived: the process must be the same.
    if app_activity()!=before or app_process()!=process:
        raise AssertionError('The app was recreated after the TV launcher came to the front; the original had stopped.')
def has_desc(value):return any(n.get('content-desc')==value for n in screen().iter('node'))
def text():return '\n'.join(n.get('text','') for n in screen().iter('node'))
def contains(value):
    for i in range(10):
        if value in text():return
        time.sleep(.6)
    raise AssertionError('Missing text: '+value+'\n'+text())
def click(label):
    print('Click:',label,flush=True)
    width,height=map(int,re.findall(r'(\d+)x(\d+)',adb('shell','wm','size').decode())[-1])
    for attempt in range(6):
        root=screen()
        node=next((n for n in root.iter('node') if n.get('content-desc','').casefold()==label.casefold() and n.get('enabled')!='false'),None)
        if node is None: node=next((n for n in root.iter('node') if n.get('text','').casefold()==label.casefold() and n.get('enabled')!='false'),None)
        if node is not None:
            x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
            if 0<=(y1+y2)//2<height: # a tap at a center off the screen does nothing; scroll instead
                adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.5);return
        adb('shell','input','swipe',str(int(width*.91)),str(int(height*.84)),str(int(width*.91)),str(int(height*.33)),'300');time.sleep(.3)
    raise AssertionError('Missing control: '+label+'\n'+text())
def open_library(name):
    """Tap a library card by name; the rail's entry of the same name is not a library card."""
    print('Open library:',name,flush=True)
    for _ in range(20):
        root=screen()
        rail=next((n for n in root.iter('node') if n.get('content-desc','').startswith('Navigation')),None)
        in_rail={id(n) for n in rail.iter('node')} if rail is not None else set()
        node=next((n for n in root.iter('node') if n.get('content-desc')==name and id(n) not in in_rail),None)
        if node is not None:
            x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
            adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.5);return
        time.sleep(.5)
    raise AssertionError('Missing library: '+name+'\n'+text())
def cast_photos(*names):
    """A cast card's portrait is described only after its image has been decoded and shown."""
    for _ in range(20):
        described={n.get('content-desc','') for n in screen().iter('node')}
        if all('Photo of '+name in described for name in names):return
        time.sleep(.5)
    raise AssertionError('Missing cast photos: '+', '.join(n for n in names if 'Photo of '+n not in described))
def pixels(png):
    """Decode an 8-bit RGB or RGBA screencap PNG into (width, height, rows of bytes)."""
    import struct,zlib
    assert png[:8]==b'\x89PNG\r\n\x1a\n';at=8;data=b''
    while at<len(png):
        size,kind=struct.unpack('>I4s',png[at:at+8]);body=png[at+8:at+8+size];at+=12+size
        if kind==b'IHDR':width,height,depth,color=struct.unpack('>IIBB',body[:10])
        elif kind==b'IDAT':data+=body
    assert depth==8 and color in (2,6),(depth,color)
    step=4 if color==6 else 3;stride=width*step;raw=zlib.decompress(data);rows=[];previous=bytearray(stride)
    for y in range(height):
        kind=raw[y*(stride+1)];line=bytearray(raw[y*(stride+1)+1:(y+1)*(stride+1)])
        for x in range(stride):
            a=line[x-step] if x>=step else 0;b=previous[x];c=previous[x-step] if x>=step else 0
            if kind==1:line[x]=(line[x]+a)&255
            elif kind==2:line[x]=(line[x]+b)&255
            elif kind==3:line[x]=(line[x]+(a+b)//2)&255
            elif kind==4:
                p=a+b-c;pa,pb,pc=abs(p-a),abs(p-b),abs(p-c)
                line[x]=(line[x]+(a if pa<=pb and pa<=pc else b if pb<=pc else c))&255
        rows.append(bytes(line));previous=line
    return width,height,step,rows
def ink(image,box):
    """The share of pixels inside box that differ clearly from the box's most common color."""
    from collections import Counter
    width,height,step,rows=image;x1,y1,x2,y2=box
    colors=[rows[y][x*step:x*step+3] for y in range(max(0,y1),min(height,y2)) for x in range(max(0,x1),min(width,x2))]
    if not colors:return 0
    ground=Counter(colors).most_common(1)[0][0]
    return sum(1 for c in colors if sum(abs(c[i]-ground[i]) for i in range(3))>90)/len(colors)
def shot(name):Path('/fixtures/'+name+'.png').write_bytes(adb('exec-out','screencap','-p'))
def post(path):urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:9010/'+path,method='POST'),timeout=5).read()
def launch():
    """Start the activity and wait for its first frame (am start -W); print the start time."""
    out=adb('shell','am','start','-W','-n',PACKAGE+'/dev.queuepilot.player.MainActivity','--ez','fixture','true').decode()
    print('Launch:',' '.join(re.findall(r'(?:LaunchState|TotalTime|WaitTime): \S+',out)),flush=True);time.sleep(1)
def back():adb('shell','input','keyevent','4');time.sleep(.3)
def answer_resume(label):
    """A title with a saved position asks before it plays; wait for the question, then answer."""
    contains(label);click(label)
def enter_pin(value):
    assert not any(n.get('class')=='android.widget.EditText' for n in screen().iter('node'))
    shot('profile-pin')
    for digit in value:click(digit)
    click('Continue')
def main():
    hardware=adb('shell','getprop','ro.hardware').decode().strip()
    assert hardware in ('ranchu','goldfish'),'This test must never target a physical TV'
    post('_online');adb('logcat','-b','crash','-c');adb('shell','pm','clear',PACKAGE);launch();contains('Your people. Your queues.');shot('welcome')
    click('Explore the demo');contains('What are we watching?');shot('queue-home')
    assert not any(n.get('class')=='android.widget.EditText' for n in screen().iter('node'))
    adb('shell','input','keyevent','21');adb('shell','input','keyevent','22')
    before=next(n for n in screen().iter('node') if n.get('focused')=='true').get('content-desc','')
    adb('shell','input','keyevent','22')
    after=next(n for n in screen().iter('node') if n.get('focused')=='true').get('content-desc','')
    assert before!=after,'D-pad right must move between queue cards'
    click('Ready when you are');contains('Orbit Nine');click('Episode 9');click('Mark watched')
    back();contains('Next: Orbit Nine · OVA');adb('shell','am','force-stop',PACKAGE);launch();contains('Next: Orbit Nine · OVA')
    click('Settings');click('Connect Plex');contains('ABCD');shot('plex-link')
    back();post('_claim');time.sleep(3);contains('Settings') # canceled authorization cannot save an account
    state=app_file('shared_prefs/state.xml');assert state is None or 'fixture-root' not in state
    post('_expire_next');click('Connect Plex');contains('This code has expired');click('New code');contains('ABCD');post('_claim')
    # No profile choice before Home: sign-in opens the account's own profile, because queues play
    # as the profile each queue names. The picker stays one press away on the rail avatar.
    contains('What are we watching?');contains('Demo Cinema');shot('signed-in-own-profile')
    click('Switch profile, Ada');contains("Who's watching?");shot('profile-picker')
    click('Morgan');enter_pin('1111');contains("Plex couldn't unlock that profile");click('Choose profile');click('Ada');contains('What are we watching?');contains('Demo Cinema');shot('signed-in-home')
    click('Series');open_library('Series');contains('Orbit Nine');shot('library')
    click('Orbit Nine');contains('Season 1');click('Season 1');contains('S1 · E9 · The signal');click('Orbit Nine, S1 · E9 · The signal');contains('Watch with Ada');contains('Orbit Nine');shot('title')
    click('Play');answer_resume('Resume from 0:04');time.sleep(3);adb('shell','input','keyevent','127');adb('shell','input','keyevent','19');contains('Decoded video: 1280 × 720');click('Cast');contains('Nadia Lane');contains('Omar Reed');cast_photos('Nadia Lane','Omar Reed');shot('cast');click('Close');back();contains('Watch with Ada')
    back();contains('The signal');back();contains('Season 1');back();contains('Orbit Nine')
    click('Home');click('Switch profile, Ada');click('Grace');contains('What are we watching?');contains('Demo Cinema');click('Series');open_library('Series');contains('Orbit Nine')
    post('_cloud_offline');post('_offline');click('Home');click('Switch profile, Grace');click('Ada');contains('What are we watching?');click('Series');contains('Saved listing');open_library('Series');contains('Saved listing');contains('Orbit Nine');shot('cached-library')
    post('_online');click('Home');click('Switch profile, Ada');click('Morgan');enter_pin('2468');contains('What are we watching?');contains('Demo Cinema')
    encrypted=app_file('shared_prefs/secrets.xml')
    assert encrypted is None or 'ciphertext' in encrypted and not any(s in encrypted for s in ('fixture-root','fixture-ada','fixture-grace','fixture-morgan'))
    requests=json.load(urllib.request.urlopen('http://127.0.0.1:9010/_requests'))
    assert any(r['path']=='/video/sample.mp4' and r['profile']=='ada' for r in requests)
    assert any(r['profile']=='grace' for r in requests)
    assert all(r['profile']!='unknown' for r in requests),'No Plex account token may be sent to a media server'
    crashes=adb('logcat','-d','-b','crash').decode();assert 'FATAL EXCEPTION' not in crashes,crashes
    print('PASS: queue-first UI, D-pad navigation, code expiry/cancel, sign-in straight to Home, profile PIN enforcement, server discovery, encrypted tokens, direct playback/cast, back navigation, cached profile/library access, and demo order persistence')
if __name__=='__main__':main()

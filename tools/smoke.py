#!/usr/bin/env python3
"""Exercise the real TV UI against invented Plex authorization and media fixtures."""
import subprocess,time,re,xml.etree.ElementTree as ET,urllib.request,json
from pathlib import Path
SERIAL='emulator-5554'
PACKAGE='dev.queuepilot.player.prototype'
def adb(*args):return subprocess.check_output(['adb','-s',SERIAL,*args],stderr=subprocess.STDOUT,timeout=30)
def screen():
    hardware=adb('shell','getprop','ro.hardware').decode().strip()
    assert hardware in ('ranchu','goldfish'),'Inspector must never target a physical TV'
    result=adb('shell','am','instrument','-w','-r','dev.queuepilot.inspector/.Dump').decode()
    match=re.search(r'INSTRUMENTATION_RESULT: xml=(.*)',result)
    if not match: raise AssertionError('UI hierarchy unavailable: '+result)
    return ET.fromstring(match.group(1))
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
            if y2>0 and y1<height:
                adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.5);return
        adb('shell','input','swipe',str(int(width*.91)),str(int(height*.84)),str(int(width*.91)),str(int(height*.33)),'300');time.sleep(.3)
    raise AssertionError('Missing control: '+label+'\n'+text())
def shot(name):Path('/fixtures/'+name+'.png').write_bytes(adb('exec-out','screencap','-p'))
def post(path):urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:9010/'+path,method='POST'),timeout=5).read()
def launch():adb('shell','am','start','-n',PACKAGE+'/dev.queuepilot.player.MainActivity','--ez','fixture','true');time.sleep(1)
def back():adb('shell','input','keyevent','4');time.sleep(.3)
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
    assert not 'fixture-root' in adb('shell','run-as',PACKAGE,'cat','shared_prefs/state.xml').decode()
    post('_expire_next');click('Connect Plex');contains('This code has expired');click('New code');contains('ABCD');post('_claim');contains("Who's watching?");shot('profile-picker')
    click('Morgan');enter_pin('1111');contains("Plex couldn't unlock that profile");click('Choose profile');click('Ada');contains('What are we watching?');contains('Demo Cinema');shot('signed-in-home')
    click('Series');contains('Series');click('Series');contains('Orbit Nine');shot('library')
    click('Orbit Nine');contains('Season 1');click('Season 1');contains('The signal');click('The signal');contains('Watch with Ada');shot('title')
    click('Play');time.sleep(3);adb('shell','input','keyevent','127');adb('shell','input','keyevent','19');contains('Decoded video: 1280 × 720');click('Cast');contains('Nadia Lane');shot('cast');click('Close');back();contains('Watch with Ada')
    back();contains('The signal');back();contains('Season 1');back();contains('Orbit Nine')
    click('Home');click('●  Ada');click('Grace');contains('What are we watching?');contains('Demo Cinema');click('Series');click('Series');contains('Orbit Nine')
    post('_cloud_offline');post('_offline');click('Home');click('●  Grace');click('Ada');contains('What are we watching?');click('Series');contains('Saved listing');click('Series');contains('Saved listing');contains('Orbit Nine');shot('cached-library')
    post('_online');click('Home');click('●  Ada');click('Morgan');enter_pin('2468');contains('What are we watching?');contains('Demo Cinema')
    encrypted=adb('shell','run-as',PACKAGE,'cat','shared_prefs/secrets.xml').decode()
    assert 'ciphertext' in encrypted and not any(s in encrypted for s in ('fixture-root','fixture-ada','fixture-grace','fixture-morgan'))
    requests=json.load(urllib.request.urlopen('http://127.0.0.1:9010/_requests'))
    assert any(r['path']=='/video/sample.mp4' and r['profile']=='ada' for r in requests)
    assert any(r['profile']=='grace' for r in requests)
    assert all(r['profile']!='unknown' for r in requests),'No Plex account token may be sent to a media server'
    crashes=adb('logcat','-d','-b','crash').decode();assert 'FATAL EXCEPTION' not in crashes,crashes
    print('PASS: queue-first UI, D-pad navigation, code expiry/cancel, profile PIN enforcement, server discovery, encrypted tokens, direct playback/cast, back navigation, cached profile/library access, and demo order persistence')
if __name__=='__main__':main()

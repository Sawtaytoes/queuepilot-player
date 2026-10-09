#!/usr/bin/env python3
"""Drive only the disposable emulator; do not run against household devices."""
import subprocess, time, re, xml.etree.ElementTree as ET, urllib.request, json
from pathlib import Path

def adb(*args):return subprocess.check_output(['adb',*args],stderr=subprocess.STDOUT,timeout=30)
def screen():
    adb('shell','uiautomator','dump','/sdcard/lab.xml')
    return ET.fromstring(adb('shell','cat','/sdcard/lab.xml').decode())
def click(label,starts=False):
    print('Click:',label,flush=True)
    for attempt in range(5):
        root=screen()
        node=next((n for n in root.iter('node') if (n.get('text','').casefold().startswith(label.casefold()) if starts else n.get('text','').casefold()==label.casefold())),None)
        if node is not None:
            x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
            if y2<=0 or y1>=1080:node=None
        if node is not None:
            adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.5);return
        adb('shell','input','swipe','1700','930','1700','330','300');time.sleep(.4)
    raise AssertionError('Control missing: '+label)
def contains(text):
    for attempt in range(12):
        if any(text in n.get('text','') for n in screen().iter('node')):return
        time.sleep(.5)
    raise AssertionError('Text missing: '+text)
def shot(name):Path('/fixtures/'+name+'.png').write_bytes(adb('exec-out','screencap','-p'))
def enroll(name,token):
    click('Add Plex profile (server + its own token)')
    inputs=[n for n in screen().iter('node') if n.get('class')=='android.widget.EditText']
    for node,value in zip(inputs,[name,'http://10.0.2.2:9010',token]):
        x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')));adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));adb('shell','input','text',value)
    click('Save');contains('Profile: '+name)

def main():
    adb('shell','pm','clear','dev.queuepilot.player.prototype')
    adb('shell','am','start','-n','dev.queuepilot.player.prototype/dev.queuepilot.player.MainActivity');time.sleep(2)
    urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:9010/_online',method='POST'))
    adb('shell','input','keyevent','20')
    assert any(node.get('focused')=='true' and node.get('class')=='android.widget.Button' for node in screen().iter('node'))
    enroll('Ada','fixture-ada');shot('native-home')
    click('Browse Plex library');contains('Live server · 1 items');click('Open');contains('Orbit Nine');shot('native-library')
    click('Compare 240px / 1440px artwork');contains('Received 240×360 and 1440×2160');shot('native-artwork')
    adb('shell','input','keyevent','4');contains('QueuePilot Lab')
    click('Browse Plex library');click('Open');click('Open');click('Open');click('Play directly');contains('Decoded video: 1280 × 720');click('Cast (leave video playing)');contains('Nadia Lane');shot('native-cast');click('Return to playback');contains('Decoded video: 1280 × 720');shot('native-playback')
    adb('shell','input','keyevent','4');contains('QueuePilot Lab')
    click('Episode 9',True);click('Mark demo position finished');contains('Next: OVA · after Episode 9')
    adb('shell','am','force-stop','dev.queuepilot.player.prototype');adb('shell','am','start','-n','dev.queuepilot.player.prototype/dev.queuepilot.player.MainActivity');contains('Next: OVA · after Episode 9')
    enroll('Grace','fixture-grace');contains('Next: Episode 9')
    encrypted=adb('shell','run-as','dev.queuepilot.player.prototype','cat','shared_prefs/secrets.xml').decode()
    assert 'ciphertext' in encrypted and 'fixture-ada' not in encrypted and 'fixture-grace' not in encrypted
    click('Browse Plex library');contains('Live server · 1 items')
    urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:9010/_offline',method='POST'))
    adb('shell','input','keyevent','4');click('Switch enrolled profile');click('Ada');contains('Profile: Ada');contains('Next: OVA · after Episode 9')
    click('Browse Plex library');contains('Cached listing · server request failed');shot('native-cached-listing')
    adb('shell','am','force-stop','dev.queuepilot.player.prototype');adb('shell','am','start','-n','dev.queuepilot.player.prototype/dev.queuepilot.player.MainActivity');contains('Profile: Ada')
    requests=json.load(urllib.request.urlopen('http://127.0.0.1:9010/_requests'))
    assert any(r['path']=='/video/sample.mp4' and r['profile']=='ada' for r in requests)
    assert any(r['query'].get('width')==['1440'] for r in requests)
    assert any(r['profile']=='grace' for r in requests)
    crashes=adb('logcat','-d','-b','crash').decode();assert 'FATAL EXCEPTION' not in crashes,crashes
    print('PASS: encrypted profile enrollment, scoped catalog cache, requested artwork sizes, real direct playback, cast overlay, restart, order, and profile isolation')
if __name__=='__main__':main()

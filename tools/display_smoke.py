#!/usr/bin/env python3
"""Display size reflows the UI, and the details screen clamps long text; emulator only.

Run after labels_smoke.py, while a profile is signed in. Pass --density-320 for a 1080p panel
at density 320; without it the emulator's own density is used.
"""
import smoke as s
import re,struct,sys,time,urllib.request,zlib
import xml.etree.ElementTree as ET

ACCENT=(163,207,104) # TvTheme.FOCUS_RING, categorical.4.content
assert s.adb('shell','getprop','ro.hardware').decode().strip() in ('ranchu','goldfish')
is_shield='--density-320' in sys.argv
s.adb('shell','wm','size','1920x1080' if is_shield else 'reset');s.adb('shell','wm','density','320' if is_shield else 'reset')
width,height=map(int,re.findall(r'(\d+)x(\d+)',s.adb('shell','wm','size').decode())[-1])
label='320dpi' if is_shield else 'native'

request=urllib.request.Request('http://127.0.0.1:9010/library/sections/2/all',headers={'X-Plex-Token':'fixture-ada'})
films=[v.get('title') for v in ET.fromstring(urllib.request.urlopen(request,timeout=5).read()).iter('Video')]
long_title=films[0];long_summary=next(v.get('summary') for v in ET.fromstring(urllib.request.urlopen(request,timeout=5).read()).iter('Video'))

def nodes():return list(s.screen().iter('node'))
def bounds(node):return tuple(map(int,re.findall(r'-?\d+',node.get('bounds'))))
def by_text(value):return [n for n in nodes() if n.get('text')==value]
def wait(check,what):
    for _ in range(30):
        found=check()
        if found:return found
        time.sleep(.5)
    raise AssertionError('Timed out waiting for '+what+'\n'+s.text())

def png_row(data,y):
    """One decoded row of an 8-bit RGB or RGBA screencap; no imaging library in the lab."""
    assert data[:8]==b'\x89PNG\r\n\x1a\n'
    pos=8;idat=b''
    while pos<len(data):
        size,=struct.unpack('>I',data[pos:pos+4]);kind=data[pos+4:pos+8];body=data[pos+8:pos+8+size];pos+=12+size
        if kind==b'IHDR':w,h,depth,color=struct.unpack('>IIBB',body[:10])
        elif kind==b'IDAT':idat+=body
    assert depth==8 and color in (2,6),(depth,color)
    bpp=4 if color==6 else 3;raw=zlib.decompress(idat);stride=w*bpp;prev=bytearray(stride);i=0
    for row in range(y+1):
        kind=raw[i];line=bytearray(raw[i+1:i+1+stride]);i+=1+stride
        for x in range(stride):
            a=line[x-bpp] if x>=bpp else 0;b=prev[x];c=prev[x-bpp] if x>=bpp else 0
            if kind==1:line[x]=(line[x]+a)&255
            elif kind==2:line[x]=(line[x]+b)&255
            elif kind==3:line[x]=(line[x]+(a+b)//2)&255
            elif kind==4:
                p=a+b-c;pa,pb,pc=abs(p-a),abs(p-b),abs(p-c)
                line[x]=(line[x]+(a if pa<=pb and pa<=pc else b if pb<=pc else c))&255
        prev=line
    return prev,bpp

def set_size(percent):
    s.click('Settings');wait(lambda:[n for n in nodes() if n.get('text','').startswith('Display size · ')],'Display size')
    current=next(n.get('text') for n in nodes() if n.get('text','').startswith('Display size · '))
    s.click(current);s.contains('Make everything smaller');s.click(f'{percent}%')
    wait(lambda:by_text(f'Display size · {percent}%'),f'Display size · {percent}%')

def assert_text_fits():
    """Every visible label lies inside the screen with a real height: nothing is cut off."""
    for n in nodes():
        if not n.get('text'):continue
        x1,y1,x2,y2=bounds(n)
        # A card scrolled out of a row reports an empty, clipped rectangle; it is not drawn.
        if x2<=x1 or y2<=y1 or y2<=0 or y1>=height or x2<=0 or x1>=width:continue
        assert 0<=x1<x2<=width and y2>y1,('Text outside the screen',n.get('text'),n.get('bounds'))

def first_row():
    """Open the invented Films library and count the posters in its first row."""
    s.click('Movies');s.click('Films')
    posters=wait(lambda:[n for n in nodes() if n.get('content-desc') in films] if len([n for n in nodes() if n.get('content-desc') in films])>=4 else None,'film posters')
    top=min(bounds(n)[1] for n in posters)
    return [n for n in posters if bounds(n)[1]==top]

def focus_outline_visible(percent):
    # A tap leaves touch mode on; one D-pad press each way returns to remote focus on a poster.
    s.adb('shell','input','keyevent','22');s.adb('shell','input','keyevent','21');time.sleep(.4)
    focused=wait(lambda:[n for n in nodes() if n.get('focused')=='true' and n.get('content-desc') in films],'a focused poster')[0]
    x1,y1,x2,y2=bounds(focused);middle=(y1+y2)//2
    row,bpp=png_row(s.adb('exec-out','screencap','-p'),middle)
    pixel=tuple(row[(x1+1)*bpp:(x1+1)*bpp+3])
    assert all(abs(a-b)<=16 for a,b in zip(pixel,ACCENT)),(f'No focus outline at {percent}%',pixel,focused.get('bounds'))

s.adb('shell','am','force-stop',s.PACKAGE);s.launch();s.click('Home');s.contains('What are we watching?')
if s.has_desc('Switch profile, Morgan'):s.click('Switch profile, Morgan');s.click('Ada');s.contains('What are we watching?')
if 'name="display-size"' in s.adb('shell','run-as',s.PACKAGE,'cat','shared_prefs/state.xml').decode():
    set_size(100) # an interrupted earlier run left a size behind
else:
    # Auto is the default; 55 in at 9 ft computes 95%.
    s.click('Settings');wait(lambda:by_text('Display size · Auto · 95%'),'the default Display size, Auto at 95%')

columns={}
for percent in (100,70,125):
    set_size(percent)
    if percent!=100:
        # The choice is stored and survives a restart.
        assert f'name="display-size" value="{percent}"' in s.adb('shell','run-as',s.PACKAGE,'cat','shared_prefs/state.xml').decode()
        s.adb('shell','am','force-stop',s.PACKAGE);s.launch();s.click('Settings');wait(lambda:by_text(f'Display size · {percent}%'),'the stored Display size')
    s.click('Home');s.contains('What are we watching?');assert_text_fits();s.shot(f'display-size-{percent}-home-{label}')
    row=first_row();columns[percent]=len(row);assert_text_fits();focus_outline_visible(percent);s.shot(f'display-size-{percent}-films-{label}')
    assert all(bounds(n)[2]<=width for n in row),'A poster runs off the screen'
assert columns[70]>columns[100]>columns[125],columns

# Details: a long title wraps to two lines and ends in an ellipsis; a long summary is four
# lines with More. Heights are measured against a one-line title and summary of the same size.
set_size(100);first_row()
s.click('Glass City');s.contains('Watch with Ada')
short_title=bounds(by_text('Glass City')[-1]);short_summary=bounds(by_text('A courier crosses a city built from glass.')[0])
assert not by_text('More'),'More appears only for a summary that is cut off'
s.back();s.click(long_title);s.contains('Watch with Ada');wait(lambda:by_text('More'),'More')
title=bounds(by_text(long_title)[-1]);summary=bounds(by_text(long_summary)[0]);play=bounds(by_text('Play')[0])
title_lines=(title[3]-title[1])/(short_title[3]-short_title[1]);summary_lines=(summary[3]-summary[1])/(short_summary[3]-short_summary[1])
assert 1.7<title_lines<2.3,('The title must be two lines',title_lines)
assert 3.5<summary_lines<4.5,('The summary must be four lines',summary_lines)
assert title[2]<=width and summary[2]<=width and summary[3]<=play[1] and play[3]<=height,(title,summary,play)
assert_text_fits();s.shot(f'details-long-{label}')
# More opens a side panel, its own window, with the whole summary unclamped.
s.click('More');wait(lambda:by_text('Close') and by_text(long_summary),'the full summary panel')
full=bounds(by_text(long_summary)[0]);assert full[3]-full[1]>summary[3]-summary[1],('The panel must show more than four lines',full,summary)
s.shot(f'details-more-{label}');s.click('Close')
s.back();s.click('Settings')
s.adb('shell','wm','size','reset');s.adb('shell','wm','density','reset')
assert 'FATAL EXCEPTION' not in s.adb('logcat','-d','-b','crash').decode()
print(f'PASS: Display size {columns} columns at 100/70/125%, persisted, focus outline visible at each size, text inside the screen, and details clamp a long title to two lines and a summary to four with More',flush=True)

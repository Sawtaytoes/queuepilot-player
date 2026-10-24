#!/usr/bin/env python3
"""Every queue without a profile choice, the TV PIN lock, and now-playing metadata; emulator only.

Run after queue_history_smoke.py, signed in with the fixture QueuePilot server connected. The
fixture's queues each play as a named profile (fixture_server.PLAYS_AS) and one is locked on the
TV with the invented PIN 1357. Every account, queue and title is invented.
"""
import smoke as s
import json,re,time,urllib.request

FIXTURE='http://127.0.0.1:9010'
assert s.adb('shell','getprop','ro.hardware').decode().strip() in ('ranchu','goldfish')

def get(path):return json.load(urllib.request.urlopen(FIXTURE+path,timeout=5))
def nodes():return list(s.screen().iter('node'))
def texts():return [n.get('text','') for n in nodes()]
def wait(check,what,tries=30):
    for _ in range(tries):
        found=check()
        if found:return found
        time.sleep(.5)
    raise AssertionError('Timed out waiting for '+what+'\n'+'\n'.join(t for t in texts() if t))
def wait_text(value):return wait(lambda:value in texts(),value)
def requests_after(mark):return get('/_requests')[mark:]
def pin(digits):
    for digit in digits:s.click(digit)
    s.click('Unlock')
def to_profile(name):
    s.click('Home');s.contains('What are we watching?')
    current=next((n.get('content-desc') for n in nodes() if (n.get('content-desc') or '').startswith('Switch profile, ')),'')
    if current!='Switch profile, '+name:
        s.click(current);s.contains("Who's watching?");s.click(name);s.contains('What are we watching?')
def playing(what):
    """Waits for the player, then pauses it so its controls and status stay on screen."""
    time.sleep(4);s.adb('shell','input','keyevent','127')
    wait(lambda:'Decoded video: 1280 × 720' in s.text() or None,what,40)
def queue_card(title,audience):
    return next(n.get('content-desc') for n in nodes() if (n.get('content-desc') or '').startswith(f'{title}. {audience}. ') and n.get('content-desc').endswith('. Play queue'))

s.post('_online');s.post('_native_reset')
s.adb('shell','am','force-stop',s.PACKAGE);s.launch()

# 1. A queue that plays as another profile starts with no profile question. The viewer has
# chosen Grace; the queue plays as Ada, so its items stream with Ada's grant from the session.
to_profile('Grace');s.click('Watching');wait_text('Your watching queues')
if 'Clear' in texts():s.click('Clear')
card=queue_card('Movies & Shows','Ada');assert card.endswith('. Plays as Ada. Play queue'),card
mark=len(get('/_requests'));s.click(card)
playing('queue playback')
assert 'Play as' not in s.text() and 'Switch and play' not in s.text()
session=[c for c in get('/_native_calls') if c['path']=='/api/native/sessions'][-1]
assert session['proof']=='fixture-user-grace' and len(session['device'])>=8,session
media=[r for r in requests_after(mark) if r['path'] in ('/library/metadata/102','/video/sample.mp4')]
assert media and all(r['profile']=='ada' for r in media),('A queue streams as its own profile',media)

# 2. Other devices read the playing item from the media session: the one title line carries the
# show, number and episode title, and the poster was fetched with the playing grant.
def session_dump():return s.adb('shell','dumpsys','media_session').decode(errors='replace')
dump=wait(lambda:(lambda d:d if 'Orbit Nine · S1 E9 · The signal' in d else None)(session_dump()),'the media session title')
line=next(l.strip() for l in dump.splitlines() if 'Orbit Nine · S1 E9 · The signal' in l)
print('Media session:',line,flush=True)
size=re.search(r'size=(\d+)',line)
assert size and int(size.group(1))>=6,('Title, display title, subtitle, artist, album, type and artwork',line)
posters=[r for r in requests_after(mark) if r['path']=='/photo/:/transcode' and r['query'].get('width')==['400']]
assert posters and posters[-1]['profile']=='ada' and posters[-1]['query'].get('url')==['/art/show-orbit.jpg'],('The show poster, fetched with the queue grant',posters)
s.back();wait_text('Your watching queues')

# 3. A single title outside a queue keeps the profile the viewer chose: Grace's token.
mark=len(get('/_requests'))
s.click('Series');s.open_library('Series');s.contains('Orbit Nine');s.click('Orbit Nine');s.click('Season 1')
s.click('Orbit Nine, S1 · E9 · The signal');s.contains('Watch with Grace');s.click('Play')
playing('library playback')
media=[r for r in requests_after(mark) if r['path'] in ('/library/metadata/102','/video/sample.mp4')]
assert media and all(r['profile']=='grace' for r in media),('A library title plays as the chosen profile',media)
s.shot('library-title-chosen-profile')
s.back();s.contains('Watch with Grace');s.click('Home');s.contains('What are we watching?')

# 4. A locked queue asks for the TV PIN. Wrong PINs count down, the fifth waits, the right PIN
# plays the queue as its own profile, and the unlock rides on later requests.
s.click('Watching');wait_text('Your watching queues')
locked=queue_card('Movies','Ada, Grace');assert locked.endswith('. Locked. Plays as Morgan. Play queue'),locked
s.click(locked);wait_text('Enter the TV PIN');s.shot('tv-pin-pad')
assert any(n.get('text')=='○  ○  ○  ○' for n in nodes())
pin('0000');wait_text('That PIN is not correct. 4 tries left.');s.shot('tv-pin-wrong')
for left in ('3 tries left.','2 tries left.','1 try left.'):pin('0000');wait_text('That PIN is not correct. '+left)
pin('0000');wait_text('Too many wrong PINs. Wait 60 seconds, then try again.');s.shot('tv-pin-locked-out')
pin('1357');wait(lambda:any(t.startswith('Too many wrong PINs. Wait ') for t in texts()),'the wait still applies')
s.post('_unlock_reset')
mark=len(get('/_requests'));pin('1357')
playing('locked queue playback')
started=[c for c in get('/_native_calls') if c['path']=='/api/native/sessions'][-1]
assert started['hasUnlock'] and started['device']==session['device'],started
media=[r for r in requests_after(mark) if r['path'] in ('/library/metadata/102','/video/sample.mp4')]
assert media and all(r['profile']=='morgan' for r in media),media
s.back();wait_text('Your watching queues')

# The full queue of the unlocked queue opens while the app stays in the foreground.
s.click('Full queue. Movies. Ada, Grace');wait_text('North of Tomorrow');s.shot('fullqueue-unlocked')
s.back();wait_text('Your watching queues')

# 5. The unlock ends when the app leaves the foreground: the PIN is asked again.
s.adb('shell','input','keyevent','3');time.sleep(1.5);s.launch()
s.click('Watching');wait_text('Your watching queues')
s.click('Full queue. Movies. Ada, Grace');wait_text('Enter the TV PIN')
s.click('Cancel');wait_text('This queue is locked on the TV. Enter the TV PIN to open it.')
s.shot('fullqueue-locked')
s.back();wait_text('Your watching queues')

to_profile('Ada')
assert 'FATAL EXCEPTION' not in s.adb('logcat','-d','-b','crash').decode()
print('PASS: a queue that plays as another profile starts with no profile question and streams with its own grant, a library title keeps the chosen profile, the media session names the show and episode with a poster, the TV PIN counts down wrong tries, waits after five, unlocks with the right PIN, and is asked again after the app leaves the foreground',flush=True)

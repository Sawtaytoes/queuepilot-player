#!/usr/bin/env python3
"""Title screens: extras by kind, Play trailer, the return after a title or extra ends, Go to show,
season and collection, and marking a show or season watched; emulator only.

Run after library_smoke.py, signed in as the fixture account. Every title, extra and collection is
invented (tools/fixture_server.py)."""
import smoke as s
import json,re,time,urllib.request

FIXTURE='http://127.0.0.1:9010/'
HEADINGS=['Trailers','Behind the scenes','Deleted scenes','Featurettes','Interviews','Scenes','Shorts','Other']
def get(path):return json.load(urllib.request.urlopen(FIXTURE+path,timeout=5))
def requests(path):return [r for r in get('_requests') if r['path']==path]
def timelines(key):return [r for r in requests('/:/timeline') if r['query'].get('ratingKey')==[key]]
def nodes():return list(s.screen().iter('node'))
def texts():return [n.get('text','') for n in nodes()]
def focused():return next((n for n in nodes() if n.get('focused')=='true'),None)
def key(code,times=1):
    for _ in range(times):s.adb('shell','input','keyevent',str(code));time.sleep(.35)
def wait(check,what,tries=40,pause=.5):
    for _ in range(tries):
        found=check()
        if found:return found
        time.sleep(pause)
    raise AssertionError('Timed out waiting for '+what+'\n'+'\n'.join(n.get('content-desc') or n.get('text','') for n in s.screen().iter('node') if n.get('content-desc') or n.get('text')))
def focus_is(check,what,tries=40):
    return wait(lambda:(lambda n:n is not None and check(n.get('content-desc') or n.get('text') or ''))(focused()),'focus on '+what,tries)
# A button's label is its text; a card's is its content description.
def shown(label):return any(label in (n.get('content-desc',''),n.get('text','')) for n in nodes())
def settle():
    """A library's Recently Added row arrives above its grid; wait for it before a tap, so the
    grid has stopped moving."""
    wait(lambda:any((n.get('content-desc') or '').startswith('Recently added, ') for n in nodes()),'the Recently Added row')
def on_player():return any(n.get('resource-id','').endswith(':id/exo_play_pause') for n in nodes())

assert s.adb('shell','getprop','ro.hardware').decode().strip() in ('ranchu','goldfish')
s.post('_online');s.post('_native_reset')
s.adb('shell','wm','size','reset');s.adb('shell','wm','density','reset')
s.adb('shell','am','force-stop',s.PACKAGE);s.launch();s.click('Home');s.contains('What are we watching?')
if shown('Switch profile, Morgan'):s.click('Switch profile, Morgan');s.click('Ada');s.contains('What are we watching?')
events=len(get('_native_events'))

# 1. Extras in one row per kind, in a fixed order, with Play trailer beside Play.
s.click('Movies');s.open_library('Films');settle();s.click('Glass City');s.contains('Watch with Ada')
wait(lambda:shown('Play trailer'),'Play trailer')
seen=[]
for _ in range(14):
    for t in texts():
        if t in HEADINGS and t not in seen:seen.append(t)
    key(20)
assert seen==['Trailers','Behind the scenes','Deleted scenes','Featurettes','Interviews','Other'],seen
s.shot('title-extras-rows')

# 2. Play trailer plays the first trailer and Back returns to the same title, on Play trailer.
key(19,14);s.click('Play trailer');wait(on_player,'the player');time.sleep(3)
# The player's controls hide after a few seconds, so the title screen's text is what is awaited.
for _ in range(4):
    if 'Watch with Ada' in s.text():break
    s.back();time.sleep(1.5)
s.contains('Watch with Ada');focus_is(lambda d:d=='Play trailer','Play trailer')

# 3. An extra that plays to its end returns to the title with focus on that extra.
s.click('Interview, Ines Cole on light');wait(on_player,'the player')
wait(lambda:'Watch with Ada' in s.text(),'the title screen after the extra ended',tries=60,pause=1)
focus_is(lambda d:d=='Interview, Ines Cole on light','the extra that ended')
extra_reports=[r for k in ('3021','3022','3023','3024','3025','3026','3027','3028') for r in timelines(k)]
assert not extra_reports,('An extra is never reported to Plex',extra_reports)
assert not any(r['path'] in ('/:/scrobble','/:/unscrobble') for r in get('_requests')),'An extra is never scrobbled'
assert len(get('_native_events'))==events,'An extra never touches a queue'

# 3b. Two extras with one name: Back returns to the one that played, not the first of the name.
def twins():return [n for n in nodes() if n.get('content-desc')=='Trailer, Official trailer']
wait(lambda:len(twins())==2,'two trailers with one name')
def is_second():
    f=focused();t=twins()
    return f is not None and len(t)==2 and f.get('bounds')==t[1].get('bounds')
key(19,14)
for _ in range(12):
    f=focused()
    if f is not None and f.get('content-desc','').startswith('Trailer, '):break
    key(20)
for _ in range(4):
    if is_second():break
    key(22)
assert is_second(),'D-pad reaches the second trailer of the same name'
key(23);wait(on_player,'the player');time.sleep(2)
for _ in range(4):
    if 'Watch with Ada' in s.text():break
    s.back();time.sleep(1.5)
wait(is_second,'focus back on the second trailer of the same name')
assert not [r for r in timelines('3028')],'The second trailer is never reported to Plex'
assert not any(r['path'] in ('/:/scrobble','/:/unscrobble') for r in get('_requests')),'An extra is never scrobbled'
assert len(get('_native_events'))==events,'An extra never touches a queue'

# 4. Go to collection with two collections asks which one; the collection opens in its order.
key(19,14);s.click('Go to collection');s.contains('Glass City is in 2 collections.')
s.contains('Glass Saga');s.click('City Stories')
wait(lambda:shown('Signal Fire'),'the City Stories collection')
assert requests('/library/collections/802/children'),'The collection opens through its own listing'
s.shot('title-collection');s.back();s.contains('Watch with Ada')

# 5. A movie that plays to its end returns to its title screen, with focus on its extras, even
# when focus was last on another button (Go to collection, then Back) before Play.
def play_to_end(title):
    s.click('Go to collection');wait(lambda:shown('Signal Fire'),'the City Stories collection');s.back()
    focus_is(lambda d:d.startswith('Go to collection'),'Go to collection')
    s.click('Play');wait(on_player,'the player')
    wait(lambda:'Watch with Ada' in s.text(),'the title screen after '+title+' ended',tries=60,pause=1)
s.back();settle();s.click('Paper Moons');s.contains('Watch with Ada');wait(lambda:shown('Featurette, Making paper moons'),'the extras')
play_to_end('Paper Moons')
focus_is(lambda d:d=='Featurette, Making paper moons','the extras')
assert any(r['query'].get('state')==['stopped'] for r in timelines('303')),'The ended movie still reports a stopped timeline'
s.shot('title-after-end')
# A title without extras lands on Play.
s.post('_no_extras');s.back();settle();s.click('Paper Moons');s.contains('Watch with Ada')
play_to_end('Paper Moons without extras')
focus_is(lambda d:d=='Play','Play');assert not shown('Featurette, Making paper moons')
s.post('_online')

# 6. A show's header: one collection opens directly; Mark watched asks first, then marks the show.
s.back();s.back();s.click('Series');s.open_library('Series');settle()
s.click('Orbit Nine');s.contains('3 of 12 episodes watched')
# Play trailer arrives with the show's extras and joins the front of the buttons; wait for it, so
# the buttons stop moving before a tap.
wait(lambda:'Play trailer' in texts(),"the show's Play trailer")
s.click('Go to collection');wait(lambda:'Relay Station Tales' in texts(),'the one collection')
assert requests('/library/collections/803/children'),'The collection opens through its own listing'
s.back()
s.contains('3 of 12 episodes watched')
s.click('Mark watched');s.contains('Mark all 12 episodes of Orbit Nine for Ada. 3 of 12 are watched now.')
s.click('Cancel');time.sleep(1)
assert not requests('/:/scrobble'),'Cancel changes nothing'
s.click('Mark watched');s.click('Watched');wait(lambda:'Watched' in texts(),'the show marked watched')
marks=requests('/:/scrobble');assert marks and marks[-1]['query'].get('key')==['100'] and marks[-1]['profile']=='ada',marks
# The panel opened again on the same screen counts the new state.
s.click('Mark watched');s.contains('12 of 12 are watched now.')
s.click('Unwatched');wait(lambda:'Unwatched' in texts(),'the show marked unwatched')
marks=requests('/:/unscrobble');assert marks and marks[-1]['query'].get('key')==['100'] and marks[-1]['profile']=='ada',marks
s.shot('title-show-header')
# Ada's marks are Ada's: Grace still sees the show's own count.
s.click('Switch profile, Ada');s.click('Grace');s.contains('What are we watching?')
s.click('Series');s.open_library('Series');settle();s.click('Orbit Nine');s.contains('3 of 12 episodes watched')
s.click('Switch profile, Grace');s.click('Ada');s.contains('What are we watching?')
s.click('Series');s.open_library('Series');settle();s.click('Orbit Nine');s.contains('Unwatched')
wait(lambda:'Play trailer' in texts(),"the show's Play trailer")

# 7. A season marks only itself.
s.click('Season 1');s.click('Mark watched');s.contains('Mark the 1 episode of Season 1 for Ada.');s.click('Watched')
wait(lambda:any(r['query'].get('key')==['101'] for r in requests('/:/scrobble')),'the season scrobble')

# 8. An episode leads to its season (looked up: the listing lacks it) and to its show.
s.click('Orbit Nine, S1 · E9 · The signal');s.contains('Watch with Ada')
s.click('Go to season');wait(lambda:shown('Orbit Nine, S1 · E9 · The signal') and 'Season 1' in texts(),'the season')
s.back();focus_is(lambda d:d=='Go to season','Go to season')
s.click('Go to show');wait(lambda:'Orbit Nine' in texts() and shown('Season 1'),'the show')
s.back();s.back();s.back();s.back()
s.post('_online')
assert 'FATAL EXCEPTION' not in s.adb('logcat','-d','-b','crash').decode()
print('PASS: extras show one row per kind with Play trailer; an extra returns to its title unreported; a finished movie lands on its extras; Go to collection, show and season open the right listings; show and season watched marks ask first and use the chosen profile',flush=True)

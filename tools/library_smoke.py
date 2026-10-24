#!/usr/bin/env python3
"""Library browsing: infinite scroll, sorts, the letter bar, search, and Add to queue.

Emulator only. Run after queue_history_smoke.py, signed in as Ada with the fixture QueuePilot
server connected. The Archive library (section 3) is a few hundred invented films; every
expected order is read from the fixture itself.
"""
import smoke as s
import json,re,time,urllib.request
import xml.etree.ElementTree as ET

FIXTURE='http://127.0.0.1:9010'
assert s.adb('shell','getprop','ro.hardware').decode().strip() in ('ranchu','goldfish')
s.adb('shell','wm','size','reset');s.adb('shell','wm','density','reset')
width,height=map(int,re.findall(r'(\d+)x(\d+)',s.adb('shell','wm','size').decode())[-1])

def get(path):return json.load(urllib.request.urlopen(FIXTURE+path,timeout=5))
def post(path):s.post(path)
def archive(sort):
    request=urllib.request.Request(FIXTURE+'/library/sections/3/all?sort='+sort,headers={'X-Plex-Token':'fixture-ada'})
    return [v.get('title') for v in ET.fromstring(urllib.request.urlopen(request,timeout=5).read()).iter('Video')]
def letters():
    request=urllib.request.Request(FIXTURE+'/library/sections/3/firstCharacter',headers={'X-Plex-Token':'fixture-ada'})
    return [(d.get('key'),int(d.get('size'))) for d in ET.fromstring(urllib.request.urlopen(request,timeout=5).read()).iter('Directory')]
def nodes():return list(s.screen().iter('node'))
def texts():return [n.get('text','') for n in nodes()]
def descs():return [n.get('content-desc','') for n in nodes()]
def bounds(node):return tuple(map(int,re.findall(r'-?\d+',node.get('bounds'))))
def key(code,times=1):
    for _ in range(times):s.adb('shell','input','keyevent',str(code));time.sleep(.3)
def wait(check,what,tries=30):
    for _ in range(tries):
        found=check()
        if found:return found
        time.sleep(.5)
    raise AssertionError('Timed out waiting for '+what+'\n'+'\n'.join(t for t in texts() if t))
def wait_text(value):return wait(lambda:value in texts(),value)
def wait_desc(value):return wait(lambda:value in descs(),value)
def focused():return next((n for n in nodes() if n.get('focused')=='true'),None)
def focus_is(desc):return wait(lambda:(lambda n:n is not None and n.get('content-desc')==desc)(focused()),'focus on '+desc)
def requests(path):return [r for r in get('/_requests') if r['path']==path]
def first_poster(titles):
    """The poster drawn first: the top-left one among the expected titles."""
    found=[n for n in nodes() if n.get('content-desc') in titles and bounds(n)[3]>bounds(n)[1]]
    return min(found,key=lambda n:(bounds(n)[1],bounds(n)[0])).get('content-desc')
def open_archive():
    s.click('Movies');s.open_library('Archive')

TITLES=archive('titleSort');TOTAL=len(TITLES)
post('_native_reset')
s.adb('shell','am','force-stop',s.PACKAGE);s.launch();s.click('Home');s.contains('What are we watching?')
if s.has_desc('Switch profile, Morgan'):s.click('Switch profile, Morgan');s.click('Ada');s.contains('What are we watching?')

# 1. Title order by default, with the count and the letter bar.
open_archive();wait_text(f'{TOTAL} titles · Sorted by Title')
if not s.has_desc('Sort, Title'):  # an interrupted earlier run left another sort behind
    s.click(next(d for d in descs() if d.startswith('Sort, ')));s.click('Title');wait_text(f'{TOTAL} titles · Sorted by Title')
wait_desc(TITLES[0]);assert first_poster(TITLES)==TITLES[0],first_poster(TITLES)
index=letters();bar=[d for d in descs() if d.startswith('Jump to ')]
assert bar==['Jump to '+k for k,_ in index],bar
assert 'Jump to X' not in bar,'A letter with no titles is not offered'
for node in [n for n in nodes() if n.get('content-desc','').startswith('Jump to ')]:
    x1,y1,x2,y2=bounds(node);assert 0<=y1<y2<=height and x2<=width,('The letter bar runs off the screen',node.get('content-desc'),node.get('bounds'))
s.shot('library-title-sort')

# 2. Infinite scroll: Down through the rows; the next page arrives before focus reaches it.
s.click('Jump to #');focus_is(TITLES[0])  # the bar's first entry focuses the first title
top=bounds(next(n for n in nodes() if n.get('content-desc')==TITLES[0]))[1]
columns=len([n for n in nodes() if n.get('content-desc') in TITLES and bounds(n)[1]==top])
assert columns>=3,columns
rows=60//columns+1
for row in range(1,rows+1):
    key(20);focus_is(TITLES[row*columns])
starts=sorted({int(r['query'].get('X-Plex-Container-Start',['-1'])[0]) for r in requests('/library/sections/3/all')})
assert 0 in starts and any(x>0 for x in starts),starts
assert not any(t in ('Next page','Previous page','Load more') for t in texts()),'No paging buttons'
focused_node=focused();x1,y1,x2,y2=bounds(focused_node);assert 0<=y1 and y2<=height,('The focused poster stays on screen',bounds(focused_node))
s.shot('library-infinite-scroll')

# 3. Jump to a letter: the window starts at its first title, and Up loads the titles before it.
offsets={};total=0
for k,size in index:offsets[k]=total;total+=size
m=offsets['M'];s.click('Jump to M');focus_is(TITLES[m])
wait_desc(TITLES[m-1])  # the page above arrived
node=next(n for n in nodes() if n.get('content-desc')==TITLES[m]);x1,y1,x2,y2=bounds(node)
assert 0<=y1 and y2<=height,('The jumped-to title stays on screen while the page above arrives',bounds(node))
s.shot('library-jump-m')
key(19);above=focused().get('content-desc');assert above in TITLES[max(0,m-2*columns):m],above
# Back from a title deep in the library returns to that title.
target=TITLES[offsets['S']];s.click('Jump to S');focus_is(target)
key(23);s.contains('Watch with Ada');s.back();focus_is(target)

# 4. Add to queue: every Picks queue, whoever it plays as; the Rules queue is explained, and a
# locked queue is listed with its lock (adding to it asks for the TV PIN).
s.click(target);s.contains('Watch with Ada');s.click('Add to queue')
wait_desc('Add to Movies & Shows. Ada');wait_desc('Add to Movies & Shows · 2. Ada, Grace')
wait_desc('Add to Movies. Grace');wait_desc('Add to Movies. Ada, Grace. Locked')
shown=texts();assert 'Movies · Ada' not in shown,shown
wait_text("Rules queues choose their own titles, so they can't take a title you add.")
assert not any('another profile' in t or 'Switch profile' in t for t in shown),shown
s.shot('library-add-to-queue')
s.click('Add to Movies & Shows. Ada');wait_text('Added to queue');wait_text(target+' is now at the top of Movies & Shows.')
s.shot('library-added');s.click('Close')
s.click('Add to queue');s.click('Add to Movies & Shows. Ada');wait_text('Already in this queue');s.click('Close')
adds=get('/_queue_adds');key_of=adds[0]['ratingKey']
assert [a['setId'] for a in adds]==['ada-watch','ada-watch'] and [a['added'] for a in adds]==[True,False],adds
assert all(a['plexServer']=='fixture-server' and a['ratingKey']==key_of for a in adds),adds
s.back()

# 5. Sorts: each one is the server's order, the letter bar is for title order only, and the
# choice is kept for the library across a restart.
open_archive();wait_text(f'{TOTAL} titles · Sorted by Title')  # from the top, where Sort is
for label,param in (('Recently added','addedAt:desc'),('Release date','originallyAvailableAt:desc'),('Rating','audienceRating:desc'),('Last watched','lastViewedAt:desc')):
    s.click(next(d for d in descs() if d.startswith('Sort, ')));wait_text('Sort Archive')
    if label=='Recently added':s.shot('library-sort-panel')
    s.click(label);wait_text(f'{TOTAL} titles · Sorted by {label}')
    expected=archive(param);wait_desc(expected[0]);assert first_poster(expected)==expected[0],(label,first_poster(expected),expected[0])
    assert not any(d.startswith('Jump to ') for d in descs()),'The letter bar is for title order only'
    assert any(r['query'].get('sort')==[param] for r in requests('/library/sections/3/all')),param
    if label=='Recently added':
        s.shot('library-recently-added')
        s.adb('shell','am','force-stop',s.PACKAGE);s.launch();s.click('Home');open_archive()
        wait_text(f'{TOTAL} titles · Sorted by Recently added')
s.click('Sort, Last watched');s.click('Title');wait_text(f'{TOTAL} titles · Sorted by Title');wait_desc('Jump to A')

# 6. Search from the rail: Movies, Shows, and Episodes groups; a result opens its title screen.
s.click('Search');wait_desc('Search titles')
field=next(n for n in nodes() if n.get('content-desc')=='Search titles' or n.get('class')=='android.widget.EditText')
if field.get('focused')!='true':s.click('Search titles')
s.adb('shell','input','text','signal')
expected_movies=[t for t in TITLES if 'signal' in t.casefold()]
wait_text(f'{len(expected_movies)+2} results for “signal”')
groups=[n.get('text') for n in nodes() if n.get('text') in ('Movies','Shows','Episodes')]
assert 'Movies' in groups and 'Episodes' in groups and 'Shows' not in groups,groups
wait_desc('Signal Fire');wait_desc('Orbit Nine, S1 · E9 · The signal')
key(66);time.sleep(1)  # Enter searches and closes the keyboard
s.shot('library-search')
search=[r['query'] for r in requests('/hubs/search')];assert search[-1]['query']==['signal'] and 'sectionId' not in search[-1],search[-1]
# An episode is added as its show.
s.click('Orbit Nine, S1 · E9 · The signal');s.contains('Watch with Ada')
s.click('Add the show Orbit Nine to a queue');wait_text('Orbit Nine · the whole show')
s.click('Add to Movies & Shows · 2. Ada, Grace');wait_text('Added to queue');s.click('Close')
assert get('/_queue_adds')[-1]=={'setId':'friends-watch','ratingKey':'100','plexServer':'fixture-server','added':True},get('/_queue_adds')[-1]
# Back returns to the same results.
s.back();wait_desc('Orbit Nine, S1 · E9 · The signal');assert 'signal' in texts(),texts()

# 7. Search within one library, from its header.
open_archive();wait_text(f'{TOTAL} titles · Sorted by Title');s.click('Search Archive');wait_desc('Search titles')
field=next(n for n in nodes() if n.get('class')=='android.widget.EditText')
if field.get('focused')!='true':s.click('Search titles')
s.adb('shell','input','text','amber')
key(66)
amber=[t for t in TITLES if 'amber' in t.casefold()];wait_text(f'{len(amber)} results for “amber”')
assert requests('/hubs/search')[-1]['query'].get('sectionId')==['3']
s.back();s.back()

# 8. The letter bar fits a 1080p panel at density 320 and the largest Display size.
s.adb('shell','wm','size','1920x1080');s.adb('shell','wm','density','320')
s.adb('shell','am','force-stop',s.PACKAGE);s.launch();s.click('Home');open_archive();wait_desc('Jump to Z')
for node in [n for n in nodes() if n.get('content-desc','').startswith('Jump to ')]:
    x1,y1,x2,y2=bounds(node);assert 0<=y1<y2<=1080 and x2<=1920,('The letter bar runs off the screen at density 320',node.get('content-desc'),node.get('bounds'))
s.shot('library-title-sort-320dpi')
s.adb('shell','wm','size','reset');s.adb('shell','wm','density','reset')
assert 'FATAL EXCEPTION' not in s.adb('logcat','-d','-b','crash').decode()
print(f'PASS: {TOTAL}-title library in title order with {columns} columns, infinite scroll in both directions with no paging buttons, letter jump and Back restoration, five sorts remembered across a restart, add to queue for every Picks queue with the Rules note, a locked queue, and a duplicate, an episode added as its show, search grouped by type from the rail and within a library',flush=True)

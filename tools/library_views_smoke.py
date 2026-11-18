#!/usr/bin/env python3
"""Library views: Recently Added rows, Folders view with nested folders, and All Movies and Shows
with library chips, sorts, letters, search, and the Include in All option; emulator only.

Run after title_smoke.py, signed in as the fixture account. Every library is invented
(tools/fixture_server.py): Series, Films, Archive, and Demos, which has a folder tree."""
import smoke as s
import json,re,time,urllib.request
import xml.etree.ElementTree as ET

FIXTURE='http://127.0.0.1:9010'
def get(path):return json.load(urllib.request.urlopen(FIXTURE+path,timeout=5))
def section(key):
    request=urllib.request.Request(FIXTURE+f'/library/sections/{key}/all',headers={'X-Plex-Token':'fixture-ada'})
    root=ET.fromstring(urllib.request.urlopen(request,timeout=5).read())
    return [v.get('title') for v in list(root.iter('Video'))+list(root.iter('Directory'))]
def newest():
    """The most recently added title of the libraries in All, and its library's name."""
    found=[]
    for key,name in (('1','Series'),('2','Films'),('3','Archive')):
        # The paged listing, as the app reads it, carries each title's addedAt.
        request=urllib.request.Request(FIXTURE+f'/library/sections/{key}/all?X-Plex-Container-Start=0',headers={'X-Plex-Token':'fixture-ada'})
        root=ET.fromstring(urllib.request.urlopen(request,timeout=5).read())
        found+=[(int(v.get('addedAt') or 0),v.get('title'),name) for v in list(root.iter('Video'))+list(root.iter('Directory'))]
    found.sort(reverse=True)
    assert found[0][0]>found[1][0],'The fixture has one newest title'
    return found[0][1]+', in '+found[0][2]
def nodes():return list(s.screen().iter('node'))
def texts():return [n.get('text','') for n in nodes()]
def descs():return [n.get('content-desc','') for n in nodes()]
def key(code,times=1):
    for _ in range(times):s.adb('shell','input','keyevent',str(code));time.sleep(.35)
def wait(check,what,tries=40):
    for _ in range(tries):
        found=check()
        if found:return found
        time.sleep(.5)
    raise AssertionError('Timed out waiting for '+what+'\n'+'\n'.join(n.get('content-desc') or n.get('text','') for n in s.screen().iter('node') if n.get('content-desc') or n.get('text')))
def wait_text(value):return wait(lambda:value in texts(),value)
def wait_desc(value):return wait(lambda:value in descs(),value)
def requests(path):return [r for r in get('/_requests') if r['path']==path]
def to_top():
    """Scrolls the screen back to its top, where the library's tools are."""
    width,height=map(int,re.findall(r'(\d+)x(\d+)',s.adb('shell','wm','size').decode())[-1])
    for _ in range(4):
        s.adb('shell','input','swipe',str(width//2),str(int(height*.3)),str(width//2),str(int(height*.85)),'250');time.sleep(.3)
def is_content_focused():
    """The rail does not hold focus: focus is right of it, or, after a tap (touch mode), nowhere."""
    all_nodes=nodes()
    rail=next((n for n in all_nodes if n.get('content-desc','').startswith('Navigation, ')),None)
    focused=next((n for n in all_nodes if n.get('focused')=='true'),None)
    if rail is None:return False
    if focused is None:return True
    rail_right=int(re.findall(r'\d+',rail.get('bounds'))[2])
    return int(re.findall(r'\d+',focused.get('bounds'))[0])>=rail_right
def tools_button():
    """The library's Sort or Options button, once the screen has settled: a new screen can
    briefly give the rail focus, and the rail widens over the tools until focus moves on."""
    def steady():
        if 'Navigation, collapsed' not in descs() or not is_content_focused():return None
        first=[(n.get('content-desc'),n.get('bounds')) for n in nodes() if n.get('content-desc','').startswith(('Sort, ','Options, '))]
        time.sleep(.5)
        again=[(n.get('content-desc'),n.get('bounds')) for n in nodes() if n.get('content-desc','').startswith(('Sort, ','Options, '))]
        return first[0][0] if first and first==again and 'Navigation, collapsed' in descs() else None
    return wait(steady,'the library tools, settled')
def options(label):
    # A letter jump can leave the tools above the screen; a viewer scrolls up to them.
    to_top()
    s.click(tools_button());s.click(label)

assert s.adb('shell','getprop','ro.hardware').decode().strip() in ('ranchu','goldfish')
s.post('_online');s.adb('shell','wm','size','reset');s.adb('shell','wm','density','reset')
s.adb('shell','am','force-stop',s.PACKAGE);s.launch();s.click('Home');s.contains('What are we watching?')
if s.has_desc('Switch profile, Morgan'):s.click('Switch profile, Morgan');s.click('Ada');s.contains('What are we watching?')
SERIES,FILMS,ARCHIVE,DEMOS=section('1'),section('2'),section('3'),section('4')

# 1. Recently Added: newest films first; a show library groups new episodes by show.
s.click('Movies');s.open_library('Films');wait_text('Recently Added')
wait_desc('Recently added, Blue Hour')
# The screen starts on the row, at its top: the library's tools stay on screen.
wait(lambda:(lambda n:n is not None and n.get('content-desc','')=='Recently added, Blue Hour')(next((n for n in nodes() if n.get('focused')=='true'),None)),'focus on the first Recently Added card')
time.sleep(1);tools=next(n for n in nodes() if n.get('content-desc','').startswith('Sort, '))
assert int(re.findall(r'\d+',tools.get('bounds'))[1])>0,('The Sort button stays on screen',tools.get('bounds'))
assert any(r['query'].get('type')==['1'] and r['query'].get('sort')==['addedAt:desc'] for r in requests('/library/sections/2/all')),'Recently added films'
s.shot('library-recently-added-movies')
# Leaving by the rail with the remote never makes the rail the library's place: back in Films,
# focus is on its first Recently Added card again, not on the rail.
def focused_node():return next((n for n in nodes() if n.get('focused')=='true'),None)
for _ in range(8):
    key(21)
    if focused_node() is not None and not is_content_focused():break
else:raise AssertionError('Left from the first card never reached the rail')
# Up to the rail's first entry, Home, then down to Watching, which opens through navigate() (Home
# itself clears the history and remembers nothing). Center opens it.
def rail_focus():
    f=focused_node();return '' if f is None else f.get('content-desc') or f.get('text') or ''
for _ in range(10):
    if rail_focus()=='Home':break
    key(19)
else:raise AssertionError('Up on the rail never reached Home: '+rail_focus())
key(20);assert rail_focus()=='Watching',('Down from Home is Watching',rail_focus())
key(23);wait(lambda:any(d.startswith('Full queue.') for d in descs()),'the Watching screen')
s.click('Movies');s.open_library('Films')
wait(lambda:(lambda n:n is not None and n.get('content-desc','')=='Recently added, Blue Hour')(focused_node()),'focus on the first Recently Added card after leaving by the rail')
s.click('Series');s.open_library('Series');wait_text('Recently Added')
wait_desc('Recently added, Orbit Nine, 3 new episodes');wait_desc('Recently added, Harbor Lights, 1 new episode')
s.shot('library-recently-added-shows')
s.click('Recently added, Orbit Nine, 3 new episodes');wait_desc('Season 1');s.back()
# Not inside a show, a season, or a search.
s.click('Orbit Nine');wait_desc('Season 1');assert 'Recently Added' not in texts(),'Only on the library screen itself'
s.back()

# 2. Folders view for Demos: nested folders, and Back walks up one folder at a time.
s.click('Movies');s.open_library('Demos');wait(lambda:any(d.startswith('Sort, ') for d in descs()),'the Sort button')
options('View as · Titles');wait_desc('Options, Folders');wait_desc('Audio');wait_desc('Video')
assert not any(d.startswith('Jump to ') for d in descs()),'No letter bar in Folders'
assert requests('/library/sections/4/folder'),'The folder listing is read'
s.shot('library-folders-root')
s.click('Audio');wait_desc('Surround');wait_desc('Bass Sweep')
s.click('Surround');wait_desc('Channel Check')
s.back();wait_desc('Bass Sweep');s.back();wait_desc('Audio');wait_desc('Options, Folders')
# The choice is kept for the library across a restart.
s.adb('shell','am','force-stop',s.PACKAGE);s.launch();s.click('Home');s.click('Movies');s.open_library('Demos');wait_desc('Options, Folders')
s.click(tools_button());wait_text('In All Movies and Shows · Not included');s.back()

# 3. All Movies and Shows: every Titles library, tagged with its library; Demos stays out.
s.click('Movies');s.open_library('All Movies and Shows')
total=len(SERIES)+len(FILMS)+len(ARCHIVE)
wait_text(f'{total} titles · Sorted by Title')
wait_desc('Jump to A')
wait(lambda:any(d.endswith(', in Archive') for d in descs()),'an Archive chip')
assert not any(d.endswith(', in Demos') for d in descs()),'A Folders library stays out of All'
for chip in ('Series','Films','Archive'):
    s.click('Jump to '+{'Series':'H','Films':'G','Archive':'B'}[chip])
    wait(lambda:any(d.endswith(', in '+chip) for d in descs()),chip+' chip')
s.shot('library-all-title')
s.click('Jump to G');wait_desc('Glass City, in Films')
# After a letter jump, D-pad Up walks back through the earlier rows to the library's tools: Up
# lands on Sort or on Search beside it, and Left from Search is Sort.
def focused_desc():
    f=next((n for n in nodes() if n.get('focused')=='true'),None)
    return '' if f is None else f.get('content-desc') or f.get('text') or ''
# The jump focuses a title card of the merged grid ("<title>, in <library>"); the walk starts there.
start=wait(lambda:(lambda d:d if ', in ' in d else None)(focused_desc()),'focus on the jumped-to title')
trail=[start]
for _ in range(120):
    key(19);trail.append(focused_desc())
    if trail[-1].startswith('Sort, ') or trail[-1]=='Search All Movies and Shows':break
else:raise AssertionError('D-pad Up never reached the tools after a letter jump: '+' > '.join(dict.fromkeys(trail)))
if trail[-1]=='Search All Movies and Shows':key(21)
assert focused_desc().startswith('Sort, '),('D-pad Left from Search is not Sort',focused_desc())
# Recently added: the newest title of every included library comes first, whichever library.
options('Recently added');wait_text(f'{total} titles · Sorted by Recently added')
wait_desc(newest());assert not any(d.startswith('Jump to ') for d in descs())
options('Title');wait_text(f'{total} titles · Sorted by Title')

# 4. Search within All: every included library, not Demos.
def search(query):
    field=next(n for n in nodes() if n.get('content-desc')=='Search titles' or n.get('class')=='android.widget.EditText')
    if field.get('focused')!='true':s.click('Search titles')
    s.adb('shell','input','keyevent','KEYCODE_MOVE_END')
    for _ in range(12):s.adb('shell','input','keyevent','67')
    s.adb('shell','input','text',query);key(66);time.sleep(1)
s.click('Search All Movies and Shows');wait_text('Titles in All Movies and Shows')
known=len(requests('/hubs/search'))
search('Orbit');wait(lambda:any(d.startswith('Orbit Nine') for d in descs()),'Orbit Nine in search')
sections={tuple(r['query'].get('sectionId',[])) for r in requests('/hubs/search')[known:]}
assert sections=={('1',),('2',),('3',)},('One search per included library, and not Demos',sections)
s.shot('library-all-search')
search('Color');wait(lambda:any(t.startswith('No titles match') for t in texts()),'no Demos result')
s.back();s.back()

# 5. Including Demos by hand adds it to All.
s.click('Movies');s.open_library('Demos');options('In All Movies and Shows · Not included');wait_desc('Options, Folders')
s.click('Movies');s.open_library('All Movies and Shows');wait_text(f'{total+len(DEMOS)} titles · Sorted by Title')
s.click('Jump to C');wait_desc('Color Bars, in Demos');s.shot('library-all-with-demos')
# Back to the defaults for the next check.
s.click('Movies');s.open_library('Demos');options('In All Movies and Shows · Included');wait_desc('Options, Folders')
options('View as · Folders');wait(lambda:any(d.startswith('Sort, ') for d in descs()),'Titles view')
# The library came back by the rail, then changed its view; focus is on the library, not the rail.
wait(is_content_focused,'focus on the library after View as')
s.click(tools_button());wait_text('In All Movies and Shows · Included');s.back()
s.back()
assert 'FATAL EXCEPTION' not in s.adb('logcat','-d','-b','crash').decode()
print(f'PASS: Recently Added rows for films and grouped shows; Demos in Folders with nested folders and Back up one level; All Movies and Shows merges {total} titles with library chips, sorts, letters and search, and takes Demos only when included',flush=True)

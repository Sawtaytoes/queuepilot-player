#!/usr/bin/env python3
"""The full queue (list and artwork grid), Start here, watched marks, and the watch history.

Emulator only. Run after details_smoke.py, signed in as Ada with the fixture QueuePilot server
connected (native_smoke.py connects it). Every queue, item and sitting is invented.
"""
import smoke as s
import json,re,time,urllib.request

FIXTURE='http://127.0.0.1:9010'
assert s.adb('shell','getprop','ro.hardware').decode().strip() in ('ranchu','goldfish')
s.adb('shell','wm','size','reset');s.adb('shell','wm','density','reset')
width,height=map(int,re.findall(r'(\d+)x(\d+)',s.adb('shell','wm','size').decode())[-1])

def get(path):return json.load(urllib.request.urlopen(FIXTURE+path,timeout=5))
def nodes():return list(s.screen().iter('node'))
def texts():return [n.get('text','') for n in nodes()]
def descs():return [n.get('content-desc','') for n in nodes()]
def bounds(node):return tuple(map(int,re.findall(r'-?\d+',node.get('bounds'))))
def key(code,times=1):
    for _ in range(times):s.adb('shell','input','keyevent',str(code));time.sleep(.25)
def wait(check,what,tries=30):
    for _ in range(tries):
        found=check()
        if found:return found
        time.sleep(.5)
    raise AssertionError('Timed out waiting for '+what+'\n'+'\n'.join(t for t in texts() if t))
def wait_text(value):return wait(lambda:value in texts(),value)
def wait_gone(value):return wait(lambda:value not in texts(),'no '+value)
def wait_desc(value):return wait(lambda:value in descs(),value)
def focused():return next((n for n in nodes() if n.get('focused')=='true'),None)
def order(*labels):
    """The labels appear in this order in the drawn screen, whatever is scrolled into view."""
    found=texts();at=[found.index(label) for label in labels]
    assert at==sorted(at),('Out of order',labels,at)
def set_size(percent):
    s.click('Settings');wait(lambda:any(t.startswith('Display size · ') for t in texts()),'Display size')
    current=next(t for t in texts() if t.startswith('Display size · '))
    if current!=f'Display size · {percent}%':
        s.click(current);s.contains('Make everything smaller');s.click(f'{percent}%')
        wait_text(f'Display size · {percent}%')
def scroll_to(prefix):
    """Moves D-pad focus down until the card whose spoken name starts with prefix has it; the
    page scrolls the focused card into view, as it does for a viewer with a remote."""
    for _ in range(10):
        node=focused()
        if node is not None and node.get('content-desc','').startswith(prefix):
            x1,y1,x2,y2=bounds(node)
            if 0<=y1<y2<=height:return node
        key(20);time.sleep(.3)
    s.shot('scroll-failure')
    for n in nodes():
        if re.match(r'\d+\. ',n.get('content-desc','')):print('card',n.get('content-desc'),n.get('bounds'),n.get('focused'),flush=True)
    raise AssertionError('Could not focus '+prefix)
def first_row(prefix):
    """The cards on the same line as the first card whose spoken name starts with prefix."""
    cards=[n for n in nodes() if re.match(r'\d+\. ',n.get('content-desc',''))]
    first=next(n for n in cards if n.get('content-desc').startswith(prefix))
    return [n for n in cards if bounds(n)[1]==bounds(first)[1] and bounds(n)[3]>bounds(n)[1]]

s.post('_online');s.post('_native_reset')
s.adb('shell','am','force-stop',s.PACKAGE);s.launch();s.click('Home');s.contains('What are we watching?')
if s.has_desc('Switch profile, Morgan'):s.click('Switch profile, Morgan');s.click('Ada');s.contains('What are we watching?')
set_size(100)
s.click('Watching');wait_text('Your watching queues')
if 'Clear' in texts():s.click('Clear')
wait_text('Plays as Grace')
assert not any('Switch' in t for t in texts()),'No profile switch in front of a queue'

# Every queue card carries a Full queue action that names the queue and its audience.
for name in ('Full queue. Movies & Shows. Ada','Full queue. Movies & Shows · 2. Ada, Grace','Full queue. Movies. Ada'):wait_desc(name)

# 1A, a list, because the queue is mostly episodes. It opens at the next item with the watched
# items folded into one row, marks tonight's lineup, and draws its end after endPosition.
s.click('Full queue. Movies & Shows. Ada')
wait_text('Show 3 watched items');wait_text('Orbit Nine · Season 1')
assert 'QUEUE · ADA' in texts(),texts()
wait(lambda:any(t.startswith('10 items · 3 watched · about ') for t in texts()),'the summary line')
for chip in ('Watched · 3','Tonight · 4','Later · 3'):wait_text(chip)
assert 'Drift' not in texts(),'Watched items start folded'
wait_text('Next · 10 min left');wait_text('Special');wait_text('Section 41:10 to 58:30');wait_text('Not watched')
wait_text('Entry 1 of 3');wait_text('Entry 2 of 3')
order("TONIGHT'S LINEUP · STARTS AT YOUR NEXT ITEM",'The signal','A day between stars','Beyond the orbit','The return',"Tonight's lineup ends here",'Fog warning','The river crossing')
assert not any('Plex' in t for t in texts()),'Source-neutral copy'
start=focused();assert start is not None and start.get('content-desc','').startswith('4. Orbit Nine, S1 · E9 · The signal. Next'),start.get('content-desc') if start is not None else None
s.shot('fullqueue-list')
s.click('Show 3 watched items');wait_text('Drift');wait_text('Hide 3 watched items')
assert texts().count('Watched')==3,texts()
order('The long night','Drift','Old friends','The signal')
s.shot('fullqueue-list-watched')

# An item from a Plex-history entry starts here but cannot be marked: its watched state is the
# media server's, and the panel says so instead of offering it.
s.click('8. Harbor Lights, S2 · E4 · Fog warning. Not watched')
wait_text('Start here, then continue');wait_text('Play only this item')
assert 'Mark watched' not in texts(),texts()
wait(lambda:any("kept by its media server" in t for t in texts()),'the reason it cannot be marked')
s.shot('fullqueue-panel-provider');s.click('Close');wait_gone('Play only this item')

# A queue-owned item is marked watched and unwatched through the server.
s.click('6. Orbit Nine, S1 · E10 · Beyond the orbit. Not watched')
wait_text('Mark watched');s.shot('fullqueue-panel');s.click('Mark watched')
wait_desc('6. Orbit Nine, S1 · E10 · Beyond the orbit. Watched')
assert get('/_queue_marks')==[{'setId':'ada-watch','ratingKey':'2010','isWatched':True}],get('/_queue_marks')
s.click('6. Orbit Nine, S1 · E10 · Beyond the orbit. Watched');s.click('Mark unwatched')
wait_desc('6. Orbit Nine, S1 · E10 · Beyond the orbit. Not watched')
assert get('/_queue_marks')[-1]['isWatched'] is False

# Play only this item: the session starts at the chosen item, and Back returns to the queue.
s.click('7. Harbor Lights, S2 · E3 · The return. Not watched');s.click('Play only this item')
wait(lambda:get('/_native_starts'),'a chosen start')
assert get('/_native_starts')[-1]=={'setId':'ada-watch','start':{'entryKey':'harbor-lights-s2','ratingKey':'103','mode':'only'}},get('/_native_starts')
time.sleep(3);key(127);wait_text('S2 · E3 · The return');wait_text('Harbor Lights')
s.back();wait(lambda:'Hide 3 watched items' in texts() or 'Show 3 watched items' in texts(),'the full queue again')
returned=wait(focused,'focus');assert returned.get('content-desc','').startswith('7. Harbor Lights, S2 · E3 · The return'),returned.get('content-desc')

# Start here, then continue; the player's Full queue action leaves playback for the queue.
s.click('4. Orbit Nine, S1 · E9 · The signal. Next · 10 min left');s.click('Start here, then continue')
wait(lambda:len(get('/_native_starts'))==2,'the second chosen start')
assert get('/_native_starts')[-1]['start']=={'entryKey':'orbit-nine-s1','ratingKey':'102','mode':'continue'}
time.sleep(3);key(127);wait_text('S1 · E9 · The signal');s.shot('player-full-queue-action')
s.click('Full queue');wait_text('Orbit Nine · Season 1');wait_text("Tonight's lineup ends here")
wait(lambda:any(e['state']=='stopped' for e in get('/_native_events')),'a stopped event from leaving playback')
s.back();wait_text('Your watching queues')

# 1B, an artwork grid, because the queue is mostly movies. Watched cards stay, dimmed; the grid
# gains columns at a smaller Display size and loses them at a larger one. It is ONE wrapping
# grid: the seven one-film entries after the lineup share rows, with no heading each, and the
# next item is marked by its badge, not by a line that breaks the row.
columns={}
for percent in (100,125):
    if percent!=100:set_size(percent);s.click('Watching');wait_text('Your watching queues')
    s.click('Full queue. Movies & Shows · 2. Ada, Grace');wait_text('Friday films')
    wait_text('Next · 38 min left')
    assert 'Show 2 watched items' not in texts() and texts().count('Watched')==2,'The grid keeps watched cards in place'
    assert "TONIGHT'S LINEUP · STARTS AT YOUR NEXT ITEM" not in texts(),'The next marker must not break a grid row'
    assert not any(t.startswith('Lantern Pictures · ') for t in texts()),'A one-item entry has no heading of its own'
    order('Paper Moons','Glass City','Signal Fire',"Tonight's lineup ends here",'North of Tomorrow')
    # Down from the next item (Glass City, third column) lands on the poster below it in the
    # flowing grid: Small Hours, on the same row as North of Tomorrow at both sizes.
    scroll_to('7. Small Hours');row=first_row('5. North of Tomorrow')
    columns[percent]=len(row)
    # Several posters from different entries share the row: the one-per-row defect is gone.
    assert len(row)>=3,('One poster per row',percent,[n.get('content-desc') for n in row])
    assert all(0<=bounds(n)[0] and bounds(n)[2]<=width for n in row),'A card runs off the screen'
    s.shot(f'fullqueue-grid-{percent}')
    s.back();wait_text('Your watching queues')
assert columns[100]>columns[125],columns
set_size(100);s.click('Watching');wait_text('Your watching queues')

# A Rules queue has no stored order: its lineup only, no Start here, and the reason is shown.
s.click('Full queue. Movies. Ada');wait_text('A Rules queue has no stored order, so this is its current lineup.')
wait(lambda:any('always starts from the top' in t for t in texts()),'the Rules reason')
s.click('1. Salt and Copper. Next')
wait_text('Close');assert 'Start here, then continue' not in texts() and 'Mark watched' not in texts(),texts()
s.shot('fullqueue-rules');s.click('Close');s.back();wait_text('Your watching queues')

# A queue that plays as another profile opens with no profile switch: the TV is a household
# device, and the server reads the queue as the profile it names. Its four one-film entries
# share one row.
s.click('Full queue. Movies. Grace');wait_text('Glass City');wait_text('Paper Moons')
assert not any('Switch' in t or 'plays as' in t for t in texts()),texts()
row=first_row('1. Glass City');assert len(row)==4,[n.get('content-desc') for n in row]
call=[c for c in get('/_native_calls') if c['path']=='/api/native/queues/movies-grace/full'][-1]
assert call['proof'] in ('fixture-root','fixture-user-ada') and len(call['device'])>=8,call
s.shot('fullqueue-other-profile');s.back();wait_text('Your watching queues')

# History (2B): queue, then sitting, then what was watched. It is reached from the rail.
s.click('History');wait_text('3 sittings in 2 queues · Last 30 days')
assert get('/_history_requests')[-1]=={'days':30}
wait_text('Every queue · Watch history'.upper())
wait_text('1 locked queue is not shown · Enter PIN')
spans=[t for t in texts() if re.match(r'^(TODAY|YESTERDAY|[A-Z]+DAY, [A-Z]+ \d+) · (ABOUT )?\d+:\d\d [AP]M( TO \d+:\d\d [AP]M)?$',t)]
assert len(spans)==3,[t for t in texts() if ' · ' in t]
assert sum('ABOUT ' in t for t in spans)==1,'A play recorded before exact times says about'
wait_text('Orbit Nine · S1 · E9 · The signal · 62%');wait_text('Orbit Nine · S1 · E7 · Drift · Recorded by the queue')
wait_text('Paper Moons');wait(lambda:[t for t in texts() if re.match(r'^1 sitting · last watched [A-Z][a-z]+day, [A-Z][a-z]+ \d+$',t)],'a weekday named in its own case');wait(lambda:any(t.endswith('times are approximate') for t in texts()),'the approximate footer')
order('Movies & Shows','Orbit Nine · S1 · E9 · The signal · 62%','Movies & Shows · 2','Paper Moons')
assert not any('Plex' in t for t in texts()),'Source-neutral copy'
s.shot('history')
sitting=next(n for n in nodes() if n.get('content-desc','').startswith('Movies & Shows. ') and '62%' in n.get('content-desc',''))
x1,y1,x2,y2=bounds(sitting);s.adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.5)
wait_text('Continue the queue');wait_text('Play S1 · E9 · The signal');wait_text('Play S1 · E8 · Old friends');wait_text('Open the full queue')
s.shot('history-panel');s.click('Open the full queue');wait_text('Orbit Nine · Season 1')
s.back();wait_text('3 sittings in 2 queues · Last 30 days')
s.click('Period, Last 30 days');s.click('Last 7 days');wait_text('3 sittings in 2 queues · Last 7 days')
assert get('/_history_requests')[-1]=={'days':7}
s.click('Home');s.contains('What are we watching?')
assert 'FATAL EXCEPTION' not in s.adb('logcat','-d','-b','crash').decode()
print(f'PASS: full queue list and grid chosen by the server ({columns} grid columns at 100/125%), lineup start and end, folded watched items, Start here (only and continue), watched marks with the Plex-history and Rules refusals, the player Full queue action, and history by queue and sitting with approximate times and a period choice',flush=True)

#!/usr/bin/env python3
"""Home and the player name shows, queues and sources; emulator only.

Run after markers_smoke.py, which leaves the second queue part-way through its second item.
"""
import smoke as s
import json,re,time,urllib.request

def nodes():return list(s.screen().iter('node'))
def texts():return [n.get('text','') for n in nodes()]
def top(label,is_last=False):
    matches=[n for n in nodes() if n.get('text')==label]
    node=matches[-1] if matches and is_last else next(iter(matches),None)
    assert node is not None,('Missing text',label,texts())
    return int(re.findall(r'\d+',node.get('bounds'))[1])
def wait_text(label):
    for _ in range(30):
        if label in texts():return
        time.sleep(.5)
    raise AssertionError('Missing text: '+label+'\n'+'\n'.join(texts()))

assert s.adb('shell','getprop','ro.hardware').decode().strip() in ('ranchu','goldfish')
s.post('_online')
s.adb('shell','am','force-stop',s.PACKAGE);s.launch();s.click('Home')
if s.has_desc('Switch profile, Morgan'):s.click('Switch profile, Morgan');s.click('Ada');s.contains('What are we watching?')

# Queues come first on Home. A people filter never hides queues silently: it says how many it
# shows and Clear is one press. Every queue is then listed in one group, in the server's order.
# Nobody switches profile to use one: each card says whom it plays as, as information only.
everything=json.load(urllib.request.urlopen('http://127.0.0.1:9010/api/native/queues?people='))['queues']
people=s.app_file('shared_prefs/state.xml')
if people is None:  # a release build: a saved choice shows as the filter notice instead
    time.sleep(2)
    if any(t.startswith('Showing ') for t in texts()):s.click('Clear')
    people=''
selected=sorted(re.findall(r'<string>([a-z]+)</string>',people.split('name="queue-people"')[1].split('</set>')[0])) if 'name="queue-people"' in people else []
if selected:
    shown=json.load(urllib.request.urlopen('http://127.0.0.1:9010/api/native/queues?people='+','.join(selected)))['queues']
    wait_text('Showing %d of %d queues'%(len(shown),len(everything)));s.shot('home-queues-filtered')
    s.click('Clear')
wait_text('Plays as Ada');wait_text('Plays as Grace');wait_text('Plays as Morgan')
assert not any('Switch' in t for t in texts()),'No profile switch in front of a queue'
assert not any(t.startswith('Showing ') for t in texts()),'No filter notice without a filter'
for queue in everything:wait_text(queue['title'])
# Card eyebrows are set in capitals, like every other eyebrow; a locked queue says so there.
wait_text('PICKS · LOCKED')
assert sum(1 for n in nodes() if n.get('content-desc','').endswith('. Locked. Plays as Morgan. Play queue'))==1
movies=[n.get('content-desc') for n in nodes() if (n.get('content-desc') or '').startswith('Movies. ')]
assert len(movies)==sum(q['title']=='Movies' for q in everything) and len(set(movies))==len(movies),movies
assert 'Ready when you are' not in texts() and 'Watch together' not in texts()
assert top('Plays as Ada',True)<top('Continue your story'),texts()
s.shot('home-queues-all')

# The saved queue continues first and names its queue, show and episode; Plex's own
# on-deck item follows and says that it comes from Plex.
for _ in range(2):s.adb('shell','input','swipe','1200','800','1200','450','300');time.sleep(.3)
wait_text('Harbor Lights');wait_text('S2 · E3 · The return');wait_text('Resume queue')
wait_text('DEMO CINEMA · CONTINUE WATCHING');wait_text('S1 · E9 · The signal')
queue_title='Movies & Shows · 2'
assert top(queue_title,True)<top('Harbor Lights')<top('DEMO CINEMA · CONTINUE WATCHING')<top('S1 · E9 · The signal'),texts()
assert 'CONTINUE WATCHING' not in texts() and not any('PLEX' in x for x in texts()),'The continue card names its server, not the provider'
s.shot('home-continuation')

# Resuming continues the queue on the item it was on, and the player names the show.
s.click('Resume queue');time.sleep(4);s.adb('shell','input','keyevent','127')
wait_text('Harbor Lights');wait_text('S2 · E3 · The return');s.shot('player-overlay')
events=json.load(urllib.request.urlopen('http://127.0.0.1:9010/_native_events'))
assert any(e['index']==1 and e['state']=='paused' for e in events),events[-3:]
s.back();s.contains('What are we watching?')
assert 'FATAL EXCEPTION' not in s.adb('logcat','-d','-b','crash').decode()
print('PASS: Home lists every queue first, in one group with its audience, whom it plays as, and its lock, says when a people filter hides queues and clears it in one press, continues the saved queue first with its show and episode, names the server on its on-deck card with the show, and the player names the show',flush=True)

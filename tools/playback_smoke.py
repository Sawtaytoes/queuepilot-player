#!/usr/bin/env python3
"""Plex's audio and subtitle choices, the resume question, chapters, skip amounts, frame-rate
matching and the playback options panel; emulator only.

Run after library_smoke.py, with the fixture QueuePilot server still connected: its queue that
plays as Grace proves a choice is saved for the queue's own profile. layout_smoke.py, which
clears the app's data, follows it."""
import smoke as s
import json,re,time,urllib.request

FIXTURE='http://127.0.0.1:9010/'
def fetch(path):return json.load(urllib.request.urlopen(FIXTURE+path))
def saves():return fetch('_stream_saves')
def timelines():return [r for r in fetch('_requests') if r['path']=='/:/timeline' and r['query'].get('ratingKey')==['102']]
def wait_for(predicate,label,limit=30):
    for _ in range(limit*4):
        if predicate():return
        time.sleep(.25)
    raise AssertionError('Timed out waiting for '+label)
def key(value):s.adb('shell','input','keyevent',str(value));time.sleep(.3)
def nodes():return list(s.screen().iter('node'))
def bounds(node):return tuple(map(int,re.findall(r'\d+',node.get('bounds'))))
def focused():return next((n for n in nodes() if n.get('focused')=='true'),None)
def selected():return [n.get('content-desc') for n in nodes() if n.get('selected')=='true' and n.get('content-desc')]
def by_id(suffix):return next(n for n in nodes() if n.get('resource-id','').endswith('/'+suffix))
def gear(item):
    """Opens the player's options from its bottom-bar control, then one of its entries."""
    key(19);box=bounds(by_id('exo_settings'))
    s.adb('shell','input','tap',str((box[0]+box[2])//2),str((box[1]+box[3])//2));s.contains('Playback speed')
    s.click(item)
def choose(fragment):
    node=next(n for n in nodes() if fragment in n.get('content-desc',''))
    s.click(node.get('content-desc'))
def open_episode():
    s.click('Home');s.click('Series');s.open_library('Series');s.click('Orbit Nine');s.click('Season 1')
    s.click('Orbit Nine, S1 · E9 · The signal');s.click('Play')
def paused_at():
    known=len(timelines());key(127)
    wait_for(lambda:any(r['query'].get('state')==['paused'] for r in timelines()[known:]),'paused timeline')
    return int([r for r in timelines()[known:] if r['query'].get('state')==['paused']][-1]['query']['time'][0])
def settings_row(prefix):
    return next(n.get('content-desc') for n in nodes() if n.get('content-desc','').startswith(prefix))

assert s.adb('shell','getprop','ro.hardware').decode().strip() in ('ranchu','goldfish')
s.post('_online');s.adb('shell','am','force-stop',s.PACKAGE);s.adb('logcat','-c');s.launch();s.click('Home')
if not s.has_desc('Switch profile, Ada'):
    s.click(next(n.get('content-desc') for n in nodes() if n.get('content-desc','').startswith('Switch profile, ')));s.click('Ada')
s.contains('What are we watching?')

# Playback options in Settings: the defaults, a changed skip amount, and the caption style.
s.click('Settings');s.click('Playback');s.contains('Skip amounts apply')
for row in ('Skip forward, under 1 hour · 10 seconds','Skip forward, 1 hour or longer · 30 seconds','Skip back · 10 seconds','Match frame rate · On','Subtitle size and background'):
    assert s.has_desc(row),row
s.shot('playback-settings')
s.click('Skip back · 10 seconds');assert selected()==['10 seconds'],selected();s.click('15 seconds')
s.contains('Skip back · 15 seconds');s.click('Subtitle size and background');s.contains("Android's caption style")
if s.has_desc('Open caption settings'):
    s.click('Open caption settings');time.sleep(2)
    top=s.adb('shell','dumpsys','activity','activities').decode()
    assert re.search(r'mResumedActivity.*(settings|Settings)',top),'Caption settings did not open'
    s.back();time.sleep(1);s.contains('Playback')
else:s.back()
s.back()

# 1. Plex's selected streams are played, and the resume question offers both answers.
s.post('_plex_tracks/selected');open_episode()
s.contains('Resume from 0:04');s.contains('Start from the beginning')
f=focused();assert f is not None and f.get('content-desc')=='Resume from 0:04',f.attrib if f is not None else None
s.shot('resume-prompt')
known=len(timelines());s.click('Start from the beginning')
wait_for(lambda:any(r['query'].get('state')==['playing'] for r in timelines()[known:]),'playing timeline')
first=[r for r in timelines()[known:] if r['query'].get('state')==['playing']][0]
assert int(first['query']['time'][0])<3000,first
key(127);time.sleep(1)
# A 30-second episode skips forward 10 seconds; Back uses the stored 15.
key(19);assert by_id('exo_ffwd_with_amount').get('text')=='10',by_id('exo_ffwd_with_amount').attrib
assert by_id('exo_rew_with_amount').get('text')=='15',by_id('exo_rew_with_amount').attrib
gear('Audio');assert len(selected())==1 and 'Commentary' in selected()[0],selected()
# Confirming the track that is already playing saves nothing.
choose('Commentary');time.sleep(2);assert not saves(),saves()
gear('Audio');choose('Original stereo')
wait_for(lambda:any(x['query'].get('audioStreamID')==['9001'] for x in saves()),'saved audio choice')
gear('Subtitles');assert len(selected())==1 and 'Signs and songs' in selected()[0],selected();s.shot('plex-selected-subtitles')
s.click('Off')
wait_for(lambda:any(x['query'].get('subtitleStreamID')==['0'] for x in saves()),'saved subtitles off')
assert all(x['profile']=='ada' and x['query'].get('allParts')==['1'] for x in saves()),saves()
logs=s.adb('logcat','-d','-s','QueuePilotFrameRate').decode()
assert 'frameRate=30.0 on=true' in logs,logs

# 2. Chapters: anchored to their control, with Plex's chapter images; choosing one seeks.
key(19);button=bounds(next(n for n in nodes() if n.get('text')=='Chapters'))
s.click('Chapters');s.contains('0:10 · The relay')
wait_for(lambda:all(s.has_desc('Picture of '+n) for n in ('Opening','The relay','Closing')),'chapter pictures',15)
panel=bounds(nodes()[0]);assert panel[1]>=button[3],('Chapters open below their top control',panel,button)
s.shot('player-chapters');s.click('0:20 · Closing');key(126);time.sleep(1.5)
assert paused_at()>=20000
s.back();s.contains('Watch with Ada')

# 3. The saved choice comes back from Plex, and Resume continues at the saved point.
s.click('Play');s.answer_resume('Resume from 0:04');time.sleep(2);key(127)
gear('Audio');assert len(selected())==1 and 'Original stereo' in selected()[0],selected();s.back()
gear('Subtitles');assert selected()==['Off'],selected();s.back();s.back();s.contains('Watch with Ada')

# 4. A subtitle file beside the media, selected by Plex, is loaded and played.
s.post('_plex_tracks/external');s.click('Play');s.answer_resume('Start from the beginning');time.sleep(2);key(127)
gear('Subtitles');assert len(selected())==1 and 'Sidecar' in selected()[0],selected();s.shot('plex-external-subtitles');s.back()
assert any(r['path']=='/library/streams/9005' and r['profile']=='ada' for r in fetch('_requests'))
s.back();s.contains('Watch with Ada')

# 4b. An extra plays its own file's tracks: the trailer on the show's screen never loads the
# episode's subtitle file that just played.
def sidecars():return len([r for r in fetch('_requests') if r['path']=='/library/streams/9005'])
s.back();s.back();wait_for(lambda:'Play trailer' in s.text(),'the show with Play trailer')
before=sidecars();s.click('Play trailer')
wait_for(lambda:any(n.get('resource-id','').endswith(':id/exo_play_pause') for n in nodes()),'the trailer')
time.sleep(3);assert sidecars()==before,'The trailer loaded the episode subtitle file'
for _ in range(4):
    if 'Play trailer' in s.text():break
    s.back();time.sleep(.5)
s.click('Season 1');s.click('Orbit Nine, S1 · E9 · The signal');s.contains('Watch with Ada')

# 5. Plex selected nothing: the account's languages decide. Japanese audio is foreign to an
# English account, so English subtitles show, and the full ones rather than the forced.
s.post('_plex_tracks/account');s.click('Play');s.answer_resume('Start from the beginning');time.sleep(2);key(127)
gear('Audio');assert len(selected())==1 and 'Original stereo' in selected()[0],selected();s.back()
gear('Subtitles');assert len(selected())==1 and 'Full subtitles' in selected()[0],selected();s.back()

# 6. Playback settings from the player: frame-rate matching off clears the display-mode request.
s.adb('logcat','-c');gear('Playback settings');s.click('Match frame rate · On');s.click('Off');s.contains('Match frame rate · Off')
wait_for(lambda:'on=false preferredMode=0' in s.adb('logcat','-d','-s','QueuePilotFrameRate').decode(),'frame-rate request cleared')
s.click('Match frame rate · Off');s.click('On');s.contains('Match frame rate · On');s.back()
s.back();s.contains('Watch with Ada')

# 7. Queues. When Plex selects nothing, the queue profile's own languages, sent with the plan,
# decide: Ada's give full English subtitles for Japanese audio; Grace's, whose audio language is
# Japanese, give only the forced signs. Ada's settings never apply to Grace's queue.
def play_queue(who):
    s.post('_native_reset');s.click('Home');s.click('Watching');s.contains('Your watching queues')
    if any(n.get('text')=='Clear' for n in nodes()):s.click('Clear')
    s.contains('Plays as '+who)
    queue=next((n.get('content-desc') for n in nodes() if 'Plays as '+who+'.' in n.get('content-desc','') and 'Locked' not in n.get('content-desc','') and n.get('content-desc','').endswith('Play queue')),None)
    assert queue,'No queue plays as '+who
    s.click(queue);time.sleep(3);key(127)
s.post('_plex_tracks/account')
play_queue('Ada');gear('Subtitles');assert len(selected())==1 and 'Full subtitles' in selected()[0],selected();s.back()
s.back();s.contains('Your watching queues')
play_queue('Grace');gear('Subtitles');assert len(selected())==1 and 'Signs and songs' in selected()[0],selected();s.back()
s.back();s.contains('Your watching queues')
# In a queue that plays as Grace, a choice is saved with the queue profile's grant.
s.post('_plex_tracks/selected');play_queue('Grace')
gear('Audio');assert 'Commentary' in selected()[0],selected();choose('Original stereo')
wait_for(lambda:any(x['profile']=='grace' and x['query'].get('audioStreamID')==['9001'] for x in saves()),'queue profile save')
assert all(x['profile']=='grace' for x in saves()),saves()
s.back();s.contains('Your watching queues')

# Restore the stored skip amount for the checks that follow.
s.click('Settings');s.click('Playback');s.click('Skip back · 15 seconds');s.click('10 seconds');s.contains('Skip back · 10 seconds');s.back()
assert 'FATAL EXCEPTION' not in s.adb('logcat','-d','-b','crash').decode()
s.post('_online');s.post('_native_reset')
print('PASS: Plex-selected audio and subtitles, choices saved per profile (library as Ada, queue as Grace), a sidecar subtitle that a trailer played next never loads, account languages when Plex selects nothing, the resume question, chapters with pictures, length-based skip amounts, frame-rate requests, and playback options from Settings and the player',flush=True)

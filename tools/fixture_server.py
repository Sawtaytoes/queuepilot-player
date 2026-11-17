#!/usr/bin/env python3
"""Local Plex-shaped fixture. All identities and tokens are invented."""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlparse, parse_qs
import json, os, time
ROOT = Path(os.environ.get('FIXTURE_ROOT','/fixtures'))
requests = []
offline = False
cloud_offline = False
pins = {}
next_pin = 0
expire_next = False
native_events=[]
native_sessions={}
# Like the engine, a new session for a queue starts where its previous session stopped.
native_resume={}
SHOW_FIELDS={'102':'grandparentTitle="Orbit Nine" parentIndex="1" parentTitle="Season 1" grandparentThumb="/art/show-orbit.jpg"','103':'grandparentTitle="Harbor Lights" parentIndex="2" parentTitle="Season 2" grandparentThumb="/art/show-harbor.jpg"'}
native_offline=False
video_fixture='sample.mp4'
markers=False
# Plex's stream metadata for tracks.mkv: '' (none sent), 'selected' (Plex marks Commentary and
# Signs and songs), 'external' (Plex marks a subtitle file beside the media), or 'account' (Plex
# marks nothing, so the account's language settings decide).
plex_tracks=''
# What Plex marks selected; PUT /library/parts/1102 changes it, as the real server does.
stream_choice={}
stream_saves=[]
# The account's plex.tv language settings: English audio when available, English subtitles
# whenever the audio is in another language.
ACCOUNT_PROFILE={'autoSelectAudio':True,'defaultAudioLanguage':'en','defaultSubtitleLanguage':'en','autoSelectSubtitle':1}
# Each queue profile's plex.tv language settings, as the QueuePilot server sends them with a plan.
# Grace's audio language is Japanese, so Japanese audio needs only her forced English subtitles.
PROFILE_LANGUAGES={'Ada':ACCOUNT_PROFILE,'Grace':{'autoSelectAudio':True,'defaultAudioLanguage':'ja','defaultSubtitleLanguage':'en','autoSelectSubtitle':1}}
STREAM_CHOICES={'selected':{'audio':9002,'subtitle':9004},'external':{'audio':9001,'subtitle':9005},'account':{}}
EXTERNAL_SRT='1\n00:00:00,000 --> 00:00:29,000\nSidecar line\n'
# Chapters for the 30-second clip, each with a chapter image.
CHAPTERS=''.join(f'<Chapter id="{80+i}" index="{i+1}" tag="{tag}" startTimeOffset="{start}" endTimeOffset="{end}" thumb="/library/media/1102/chapterImages/{i+1}"/>' for i,(tag,start,end) in enumerate([('Opening',0,10000),('The relay',10000,20000),('Closing',20000,30000)]))
def plex_media():
    """The episode's Media element: plain for sample.mp4, with Plex streams for tracks.mkv."""
    if not plex_tracks:return '<Media><Part id="1102" indexes="sd" key="/video/sample.mp4"/></Media>'
    def sel(kind,ident):return ' selected="1"' if stream_choice.get(kind)==ident else ''
    streams=('<Stream id="9000" streamType="1" index="0" codec="h264" frameRate="30.000"/>'
        f'<Stream id="9001" streamType="2" index="1" codec="aac" languageCode="jpn" title="Original stereo" default="1"{sel("audio",9001)}/>'
        f'<Stream id="9002" streamType="2" index="2" codec="aac" languageCode="jpn" title="Commentary"{sel("audio",9002)}/>'
        f'<Stream id="9003" streamType="3" index="3" codec="srt" languageCode="eng" title="Full subtitles"{sel("subtitle",9003)}/>'
        f'<Stream id="9004" streamType="3" index="4" codec="srt" languageCode="eng" title="Signs and songs" forced="1"{sel("subtitle",9004)}/>')
    if plex_tracks=='external':streams+=f'<Stream id="9005" streamType="3" codec="srt" languageCode="eng" title="Sidecar" key="/library/streams/9005"{sel("subtitle",9005)}/>'
    return f'<Media container="mkv" videoCodec="h264" audioCodec="aac"><Part id="1102" indexes="sd" container="mkv" key="/video/sample.mp4">{streams}</Part></Media>'
# Invented films. One has a long title and summary, so the details screen must clamp both;
# twelve fill several rows, so a smaller Display size visibly gains columns.
LONG_TITLE='The Extraordinarily Long Voyage of the Lantern Keeper and the Seven Lighthouses Beyond the Northern Sea, Told in Twelve Winters by the Last Keeper of the Northern Light and Her Crew of Two, and of the Ice That Came After'
LONG_SUMMARY=' '.join(['A retired lantern keeper sails north to relight seven lighthouses that went dark in the same winter.']*9+['The final lighthouse waits beyond the ice.'])
FILMS=[(301,LONG_TITLE,LONG_SUMMARY),(302,'Glass City','A courier crosses a city built from glass.')]+[(300+i,t,'An invented film for the browsing fixture.') for i,t in enumerate(['Paper Moons','The Quiet Orchard','Signal Fire','North of Tomorrow','Salt and Copper','Small Hours','Kite Season','The Lantern Road','Low Tide','Blue Hour'],3)]
# Full metadata for one film, as /library/metadata/<key> returns it: the facts, genres and
# credits the title screen shows, and a resume point partway through.
GLASS_CITY=('<MediaContainer><Video ratingKey="302" type="movie" title="Glass City" year="2024" contentRating="PG" studio="Lantern Pictures" rating="8.1" audienceRating="7.6" duration="6720000" viewOffset="4440000" summary="A courier crosses a city built from glass." thumb="/art/poster.jpg">'
    '<Media videoResolution="1080" audioChannels="6"><Part id="1302" key="/video/sample.mp4"/></Media>'
    '<Genre tag="Drama"/><Genre tag="Mystery"/><Director tag="Ines Cole"/><Writer tag="Theo Marsh"/>'
    '<Role tag="Mara Quill" role="The courier" thumb="/art/nadia.jpg"/><Role tag="Omar Reed" role="The glazier" thumb="/art/omar.jpg"/></Video></MediaContainer>')
# Seek thumbnails for the 30-second clip: a Roku BIF with a frame every five seconds. Each
# frame is a different fixture JPEG, so the preview shows which frame was chosen.
def bif():
    import struct
    names=['low','nadia','omar','ada','grace','low','nadia']
    frames=[(ROOT/(n+'.jpg')).read_bytes() for n in names]
    header=bytes([0x89,0x42,0x49,0x46,0x0d,0x0a,0x1a,0x0a])+struct.pack('<III',0,len(frames),5000)+bytes(44)
    offset=64+(len(frames)+1)*8;index=b''
    for i,frame in enumerate(frames):index+=struct.pack('<II',i,offset);offset+=len(frame)
    index+=struct.pack('<II',0xffffffff,offset)
    return header+index+b''.join(frames)
def films():
    from xml.sax.saxutils import quoteattr
    return '<MediaContainer>'+''.join(f'<Video ratingKey="{key}" type="movie" title={quoteattr(title)} summary={quoteattr(summary)} year="2024" thumb="/art/poster.jpg"/>' for key,title,summary in FILMS)+'</MediaContainer>'
# The Archive library (section 3): a few hundred invented films across the alphabet, with
# added, release, rating and last-watched values in orders unlike the title order, so every
# sort, every page and every letter jump has a different answer. No letter X; four titles
# start with a digit and file under "#".
ARCHIVE_WORDS={'A':'Amber','B':'Bright','C':'Copper','D':'Distant','E':'Emerald','F':'Frozen','G':'Golden','H':'Hollow','I':'Iron','J':'Jade','K':'Kindred','L':'Lunar','M':'Midnight','N':'Northern','O':'Open','P':'Painted','Q':'Quiet','R':'Rusted','S':'Silver','T':'Tidal','U':'Upper','V':'Velvet','W':'Winter','Y':'Yellow','Z':'Zero'}
ARCHIVE_NOUNS=['Harbor','Signal','Garden','Station','River','Lantern','Orchard','Canyon','Meadow','Bridge','Comet','Island','Forest','Tower','Valley','Engine','Atlas','Beacon']
def _archive():
    titles=['3 Rivers','7 Bridges','9 Lanterns','12 Comets']
    for letter,word in ARCHIVE_WORDS.items():
        titles+=[f'{word} {noun}' for noun in ARCHIVE_NOUNS[:(ord(letter)*7)%13+6]]
    films=[]
    for i,title in enumerate(titles):
        films.append({'key':str(5000+i),'title':title,'addedAt':1700000000+((i*37)%len(titles))*3600,
            'originallyAvailableAt':f'{1980+(i*11)%45}-{(i%12)+1:02d}-{(i%27)+1:02d}','audienceRating':f'{((i*13)%90)/10+1:.1f}',
            'lastViewedAt':1750000000+i*600 if i%5==0 else None})
    return films
ARCHIVE=_archive()
def title_sort(title):return title[4:] if title.startswith('The ') else title
def first_character(title):
    c=title_sort(title)[:1].upper()
    return c if c.isalpha() else '#'
SORTS={'titleSort':lambda f:(title_sort(f['title']).casefold(),False),'addedAt:desc':lambda f:(f['addedAt'],True),
    'originallyAvailableAt:desc':lambda f:(f['originallyAvailableAt'],True),'audienceRating:desc':lambda f:(float(f['audienceRating']),True),
    'lastViewedAt:desc':lambda f:(f['lastViewedAt'] or 0,True)}
def sorted_items(items,sort):
    if not sort:return items
    key=SORTS[sort];reverse=key(items[0])[1] if items else False
    return sorted(items,key=lambda f:key(f)[0],reverse=reverse)
def film_xml(f):
    from xml.sax.saxutils import quoteattr
    extra=''.join(f' {k}="{f[k]}"' for k in ('addedAt','originallyAvailableAt','audienceRating','lastViewedAt') if f.get(k))
    return f'<Video ratingKey="{f["key"]}" type="movie" title={quoteattr(f["title"])} summary={quoteattr(f.get("summary","An invented film for the browsing fixture."))} year="2024" thumb="/art/poster.jpg"{extra}/>'
SECTION_SHOWS=[{'key':'100','title':'Orbit Nine'},{'key':'200','title':'Harbor Lights'}]
def show_xml(f):return f'<Directory ratingKey="{f["key"]}" key="/library/metadata/{f["key"]}/children" type="show" title="{f["title"]}" thumb="/art/poster.jpg"/>'
def section_items(section):
    if section=='1':return SECTION_SHOWS,show_xml
    if section=='2':return [{'key':str(k),'title':t,'summary':d} for k,t,d in FILMS],film_xml
    return ARCHIVE,film_xml
def section_page(section,query):
    """A section listing honoring sort, X-Plex-Container-Start and -Size, with totalSize."""
    items,xml=section_items(section);ordered=sorted_items(items,query.get('sort',[''])[0])
    start=int(query.get('X-Plex-Container-Start',['0'])[0]);size=int(query.get('X-Plex-Container-Size',[str(len(ordered))])[0])
    page=ordered[start:start+size]
    return f'<MediaContainer size="{len(page)}" totalSize="{len(ordered)}" offset="{start}">'+''.join(xml(f) for f in page)+'</MediaContainer>'
def first_characters(section):
    items,_=section_items(section);counts={}
    for f in items:counts[first_character(f['title'])]=counts.get(first_character(f['title']),0)+1
    keys=sorted(counts,key=lambda c:(c!='#',c))
    return '<MediaContainer>'+''.join(f'<Directory key="{c}" title="{c}" size="{counts[c]}"/>' for c in keys)+'</MediaContainer>'
EPISODES=[('102','The signal','100',SHOW_FIELDS['102'],'9'),('103','The return','200',SHOW_FIELDS['103'],'3')]
def search_hubs(query,section):
    """Plex's /hubs/search: the matching titles grouped into Movie, Show and Episode hubs."""
    q=query.casefold();sections=[section] if section else ['1','2','3']
    movies=[f for s in sections if s in ('2','3') for f in section_items(s)[0] if q in f['title'].casefold()]
    shows=[f for f in SECTION_SHOWS if '1' in sections and q in f['title'].casefold()]
    episodes=[e for e in EPISODES if '1' in sections and q in e[1].casefold()]
    hubs=''
    if movies:hubs+=f'<Hub type="movie" hubIdentifier="movie" title="Movies" size="{len(movies)}">'+''.join(film_xml(f) for f in movies[:30])+'</Hub>'
    if shows:hubs+=f'<Hub type="show" hubIdentifier="show" title="Shows" size="{len(shows)}">'+''.join(show_xml(f) for f in shows)+'</Hub>'
    if episodes:hubs+=f'<Hub type="episode" hubIdentifier="episode" title="Episodes" size="{len(episodes)}">'+''.join(f'<Video ratingKey="{k}" grandparentRatingKey="{show}" type="episode" index="{index}" title="{t}" thumb="/art/poster.jpg" {fields}/>' for k,t,show,fields,index in episodes)+'</Hub>'
    return '<MediaContainer>'+hubs+'</MediaContainer>'
# Titles a queue may hold, for the native add-to-queue route: movies and shows only.
def addable_title(key):
    if key in ('100','200'):return 'show'
    if key in [str(k) for k,_,_ in FILMS] or any(f['key']==key for f in ARCHIVE):return 'movie'
    if key in ('102','103'):return 'episode'
    return None
queue_adds=[]
# Intro, mid-credits and final credits for the 30-second clip, in Plex's millisecond offsets.
MARKERS='<Marker type="intro" startTimeOffset="6000" endTimeOffset="14000"/><Marker type="credits" startTimeOffset="18000" endTimeOffset="21000"/><Marker type="credits" final="1" startTimeOffset="25000" endTimeOffset="30000"/>'
# Reserved example domain: the guest has no route to it, so only the server transcoder can serve it.
EXTERNAL_PORTRAIT='https://images.example.com/portraits/omar.jpg'
# The full queue, history and watched-mark routes (QueuePilot's /api/native). Invented queues:
# an episode queue (list layout), a movie queue (grid layout) and a Rules queue (lineup only).
def _episode(position,key,show,season,episode,title,state='unwatched',offset=0,lineup=None,next_=False,owned=True,special=False,duration=1440000):
    return {'position':position,'ratingKey':str(key),'serverId':'fixture-server','plexServer':None,'title':title,'type':'episode','show':show,'season':season,'episode':episode,'isSpecial':special,'durationMs':duration,'state':state,'offsetMs':offset,'section':None,'lineupIndex':lineup,'isNext':next_,'canMarkWatched':owned,'artwork':{'thumb':'/art/poster.jpg','parentThumb':None,'grandparentThumb':'/art/poster.jpg','art':None}}
def _film(position,key,state='unwatched',offset=0,lineup=None,next_=False,owned=True,section=None):
    title=next(t for k,t,_ in FILMS if k==key)
    return {'position':position,'ratingKey':str(key),'serverId':'fixture-server','plexServer':None,'title':title,'type':'movie','show':None,'season':None,'episode':None,'isSpecial':False,'durationMs':6720000,'state':state,'offsetMs':offset,'section':section,'lineupIndex':lineup,'isNext':next_,'canMarkWatched':owned,'artwork':{'thumb':'/art/poster.jpg','parentThumb':None,'grandparentThumb':None,'art':None}}
def _entry(key,title,kind,history,items,lane='pool'):
    return {'entryKey':key,'title':title,'type':kind,'lane':lane,'watchHistory':history,'isShuffled':False,'isUnresolved':False,'items':items}
def full_queues():
    wild=_film(9,312,section={'startMs':2470000,'endMs':3510000});wild.update(title='The river crossing',durationMs=3600000)
    return {
        'ada-watch':{'setId':'ada-watch','kind':'picks','layout':'list','isWholeQueue':True,'isLineupShuffled':False,'lineup':{'count':4,'endPosition':6},'isTruncated':False,'entries':[
            _entry('orbit-nine-s1','Orbit Nine · Season 1','show','queue',[
                _episode(0,2006,'Orbit Nine',1,6,'The long night','watched'),_episode(1,2007,'Orbit Nine',1,7,'Drift','watched'),_episode(2,2008,'Orbit Nine',1,8,'Old friends','watched'),
                _episode(3,102,'Orbit Nine',1,9,'The signal','in_progress',840000,0,True),_episode(4,2100,'Orbit Nine',0,1,'A day between stars',lineup=1,special=True,duration=1080000),
                _episode(5,2010,'Orbit Nine',1,10,'Beyond the orbit',lineup=2)]),
            _entry('harbor-lights-s2','Harbor Lights · Season 2','show','provider',[
                _episode(6,103,'Harbor Lights',2,3,'The return',lineup=3,owned=False,duration=2760000),_episode(7,2204,'Harbor Lights',2,4,'Fog warning',owned=False,duration=2760000),_episode(8,2205,'Harbor Lights',2,5,'Lights out',owned=False,duration=2760000)]),
            _entry('wild-current','Wild Current · The river crossing','movie','queue',[wild])]},
        'friends-watch':{'setId':'friends-watch','kind':'picks','layout':'grid','isWholeQueue':True,'isLineupShuffled':False,'lineup':{'count':2,'endPosition':3},'isTruncated':False,'entries':[
            _entry('friday-films','Friday films','collection','queue',[_film(0,303,'watched'),_film(1,304,'watched'),_film(2,302,'in_progress',4440000,0,True),_film(3,305,lineup=1)]),
            # Seven one-film entries. One poster per row was the defect: each one-item entry drew
            # its own heading and its own row. They must share rows with each other now.
            *[_entry(f'lantern-{k}','Lantern Pictures · '+next(t for f,t,_ in FILMS if f==k),'movie','queue',[_film(4+i,k)]) for i,k in enumerate([306,307,308,309,310,311,301])]]},
        'movies-grace':{'setId':'movies-grace','kind':'picks','layout':'grid','isWholeQueue':True,'isLineupShuffled':False,'lineup':{'count':4,'endPosition':3},'isTruncated':False,'entries':[
            _entry(f'grace-{k}','Grace picks · '+next(t for f,t,_ in FILMS if f==k),'movie','queue',[_film(i,k,lineup=i,next_=i==0)]) for i,k in enumerate([302,303,304,305])]},
        'movies-family':{'setId':'movies-family','kind':'picks','layout':'grid','isWholeQueue':True,'isLineupShuffled':False,'lineup':{'count':1,'endPosition':0},'isTruncated':False,'entries':[
            _entry(f'family-{k}','Family night · '+next(t for f,t,_ in FILMS if f==k),'movie','queue',[_film(i,k,lineup=0 if i==0 else None,next_=i==0)]) for i,k in enumerate([306,307,308])]},
        'movies-ada':{'setId':'movies-ada','kind':'rules','layout':'grid','isWholeQueue':False,'isLineupShuffled':True,'lineup':{'count':3,'endPosition':2},'isTruncated':False,'entries':[
            _entry(None,'Movies','lineup','provider',[_film(0,307,lineup=0,next_=True,owned=False),_film(1,308,lineup=1,owned=False),_film(2,309,lineup=2,owned=False)])]},
    }
queue_marks={}
native_starts=[]
history_requests=[]
def full_queue(set_id):
    full=full_queues().get(set_id)
    if not full:return None
    for entry in full['entries']:
        for item in entry['items']:
            mark=queue_marks.get((set_id,item['ratingKey']))
            if mark is not None:item.update(state='watched' if mark else 'unwatched',offsetMs=0)
    return full
def history(days):
    now=int(time.time()*1000);hour=3600000
    def item(set_id,entry,key,title,show,season,episode,kind,start,end,state,position,duration,source='native-tv',approximate=False):
        return {'setId':set_id,'entryKey':entry,'ratingKey':str(key),'plexServer':None,'title':title,'show':show,'season':season,'episode':episode,'type':kind,'startedAt':start,'endedAt':end,'state':state,'positionMs':position,'durationMs':duration,'source':source,'isTimeApproximate':approximate}
    queues=[
        {'setId':'ada-watch','title':'Movies & Shows','lastWatchedAt':now-2*hour,'sittings':[
            {'startedAt':now-3*hour,'endedAt':now-2*hour,'items':[item('ada-watch','orbit-nine-s1',2008,'Old friends','Orbit Nine',1,8,'episode',now-3*hour,now-3*hour+1440000,'finished',1440000,1440000),item('ada-watch','orbit-nine-s1',102,'The signal','Orbit Nine',1,9,'episode',now-3*hour+1500000,now-2*hour,'partial',893000,1440000)]},
            {'startedAt':now-27*hour,'endedAt':now-26*hour,'items':[item('ada-watch','orbit-nine-s1',2006,'The long night','Orbit Nine',1,6,'episode',now-27*hour,now-27*hour+1440000,'finished',1440000,1440000),item('ada-watch','orbit-nine-s1',2007,'Drift','Orbit Nine',1,7,'episode',now-27*hour+1500000,now-26*hour,'finished',1440000,1440000,'queue-ledger')]}]},
        {'setId':'friends-watch','title':'Movies & Shows · 2','lastWatchedAt':now-50*hour,'sittings':[
            {'startedAt':now-52*hour,'endedAt':now-50*hour,'items':[item('friends-watch','friday-films',303,'Paper Moons',None,None,None,'movie',now-52*hour,now-50*hour,'finished',6720000,6720000,approximate=True)]}]}]
    since=now-days*86400000
    for queue in queues:queue['sittings']=[x for x in queue['sittings'] if x['startedAt']>=since]
    return {'profile':{'uuid':'ada','name':'Ada'},'sittingGapMinutes':75,'since':since,'queues':[q for q in queues if q['sittings']]}
# THE TV IS A HOUSEHOLD DEVICE. Any profile token of the fixture's Plex Home may use every queue;
# a queue plays as the profile it names (PLAYS_AS), whoever is signed in. Anything else is 403.
HOUSEHOLD=('fixture-root','fixture-user-ada','fixture-user-grace','fixture-user-morgan')
PLAYS_AS={'ada-watch':'Ada','movies-grace':'Grace','friends-watch':'Ada','movies-ada':'Ada','movies-family':'Morgan'}
def is_native_profile(handler):return handler.headers.get('X-Plex-Profile-Token') in HOUSEHOLD
# "Locked on TV": the household TV PIN opens it. Unlocks are bound to the device that asked.
TV_PIN='1357';LOCKED={'movies-family'}
unlocks={};unlock_state={'wrong':0,'until':0.0};native_calls=[]
def is_unlocked(handler):
    token=handler.headers.get('X-QueuePilot-Unlock','')
    return bool(token) and unlocks.get(token)==handler.headers.get('X-QueuePilot-Device','')
def refuse_locked(handler,set_id):
    if set_id in LOCKED and not is_unlocked(handler):
        handler.respond(json.dumps({'error':'This queue is locked. Enter the TV PIN to open it.','isLocked':True}),'application/json',423);return True
    return False
def note_call(handler,path):
    native_calls.append({'path':path,'proof':handler.headers.get('X-Plex-Profile-Token',''),'device':handler.headers.get('X-QueuePilot-Device',''),'hasUnlock':bool(handler.headers.get('X-QueuePilot-Unlock',''))})
def grants(set_id):
    # The queue profile's server-scoped grant, as the real server hands it with a new session.
    return [{'serverId':'fixture-server','baseUrl':'http://10.0.2.2:9010','token':'fixture-'+PLAYS_AS.get(set_id,'Ada').lower()}]

users = [{'id':1,'uuid':'ada','title':'Ada','protected':False,'admin':True,'thumb':'/avatars/ada.jpg'}, {'id':2,'uuid':'grace','title':'Grace','protected':False,'thumb':'/avatars/grace.jpg'}, {'id':3,'uuid':'morgan','title':'Morgan','protected':True,'thumb':'/avatars/nadia.jpg'}]

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def respond(self, data, mime='application/xml', code=200):
        if isinstance(data,str): data=data.encode()
        self.send_response(code); self.send_header('Content-Type',mime); self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
    def do_POST(self):
        global offline, cloud_offline, next_pin, expire_next, native_offline, video_fixture, markers, plex_tracks, stream_choice
        path=urlparse(self.path).path
        if path=='/_offline': offline=True; self.respond('ok');return
        if path=='/_ass': video_fixture='styled.mkv';self.respond('ok');return
        if path=='/_tracks': video_fixture='tracks.mkv';self.respond('ok');return
        if path=='/_unnamed_tracks': video_fixture='tracks.mp4';self.respond('ok');return
        if path=='/_markers': markers=True;self.respond('ok');return
        if path.startswith('/_plex_tracks/'):
            plex_tracks=path.split('/')[-1];stream_choice=dict(STREAM_CHOICES[plex_tracks]);video_fixture='tracks.mkv';stream_saves.clear();self.respond('ok');return
        if path=='/_online': plex_tracks='';stream_choice.clear();video_fixture='sample.mp4';markers=False;offline=False; cloud_offline=False; native_offline=False;self.respond('ok');return
        if path=='/_native_reset':native_events.clear();native_sessions.clear();native_resume.clear();queue_marks.clear();native_starts.clear();history_requests.clear();queue_adds.clear();unlocks.clear();native_calls.clear();unlock_state.update(wrong=0,until=0.0);self.respond('ok');return
        if path=='/_unlock_reset':unlocks.clear();unlock_state.update(wrong=0,until=0.0);self.respond('ok');return
        if path=='/_native_offline':native_offline=True;self.respond('ok');return
        if path=='/_cloud_offline': cloud_offline=True;self.respond('ok');return
        if path=='/_expire_next': expire_next=True;self.respond('ok');return
        if path=='/_claim':
            if pins: pins[max(pins)]['claimed']=True
            self.respond('ok');return
        body=self.rfile.read(int(self.headers.get('Content-Length','0'))).decode()
        if path.startswith('/api/native/'):
            if native_offline:self.respond(json.dumps({'error':'QueuePilot is unavailable'}),'application/json',503);return
            value=json.loads(body or '{}')
            note_call(self,path)
            if path=='/api/native/unlock':
                if not is_native_profile(self):self.respond(json.dumps({'error':'This TV is not signed in to this Plex Home'}),'application/json',403);return
                device=self.headers.get('X-QueuePilot-Device','')
                if len(device)<8:self.respond(json.dumps({'error':"Send X-QueuePilot-Device with this TV's identifier"}),'application/json',400);return
                now=time.monotonic()
                if now<unlock_state['until']:self.respond(json.dumps({'error':'Too many wrong PINs. Wait, then try again.','retryAfterSeconds':int(unlock_state['until']-now)+1}),'application/json',429);return
                if value.get('pin')!=TV_PIN:
                    unlock_state['wrong']+=1
                    if unlock_state['wrong']>=5:
                        unlock_state.update(wrong=0,until=now+60)
                        self.respond(json.dumps({'error':'Too many wrong PINs. Wait, then try again.','retryAfterSeconds':60}),'application/json',429);return
                    left=5-unlock_state['wrong']
                    self.respond(json.dumps({'error':f'That PIN is not correct. {left} tries left.','attemptsLeft':left}),'application/json',403);return
                unlock_state['wrong']=0;token='fixture-unlock-'+str(len(unlocks)+1);unlocks[token]=device
                self.respond(json.dumps({'unlockToken':token,'expiresAt':int(time.time()*1000)+4*3600000}),'application/json');return
            if path.startswith('/api/native/queues/') and path.endswith('/items'):
                if not is_native_profile(self):self.respond(json.dumps({'error':'This TV is not signed in to this Plex Home'}),'application/json',403);return
                set_id=path.split('/')[4]
                if refuse_locked(self,set_id):return
                if set_id=='movies-ada':self.respond(json.dumps({'error':'A Rules queue chooses its own titles. Add this to a Picks queue.'}),'application/json',409);return
                if set_id not in PLAYS_AS:self.respond(json.dumps({'error':'Unknown queue'}),'application/json',404);return
                key=str(value.get('ratingKey','')).strip();kind=addable_title(key)
                if kind is None:self.respond(json.dumps({'error':'That title is not available to this profile'}),'application/json',404);return
                if kind=='episode':self.respond(json.dumps({'error':'Only a movie or a show can be added to a queue'}),'application/json',400);return
                duplicate=any(a['setId']==set_id and a['ratingKey']==key for a in queue_adds)
                queue_adds.append({'setId':set_id,'ratingKey':key,'plexServer':value.get('plexServer'),'added':not duplicate})
                self.respond(json.dumps({'added':not duplicate,'key':'rk:'+key,**({'duplicateOf':'rk:'+key} if duplicate else {}),'title':key}),'application/json');return
            if path.startswith('/api/native/queues/') and path.endswith('/watched'):
                if not is_native_profile(self):self.respond(json.dumps({'error':'This TV is not signed in to this Plex Home'}),'application/json',403);return
                set_id=path.split('/')[4]
                if refuse_locked(self,set_id):return
                full=full_queue(set_id)
                if not full:self.respond(json.dumps({'error':'Unknown queue'}),'application/json',404);return
                entry=next((e for e in full['entries'] if e['entryKey']==value.get('entryKey')),None)
                if not entry or not any(i['ratingKey']==value.get('ratingKey') for i in entry['items']):self.respond(json.dumps({'error':'Unknown item'}),'application/json',404);return
                if entry['watchHistory']!='queue':self.respond(json.dumps({'error':'This entry keeps its watched state in Plex'}),'application/json',409);return
                queue_marks[(set_id,value['ratingKey'])]=bool(value.get('isWatched'))
                self.respond(json.dumps({'entryKey':value['entryKey'],'ratingKey':value['ratingKey'],'state':'watched' if value.get('isWatched') else 'unwatched'}),'application/json');return
            if path=='/api/native/sessions':
                if not is_native_profile(self):self.respond(json.dumps({'error':'This TV is not signed in to this Plex Home'}),'application/json',403);return
                if refuse_locked(self,value.get('setId')):return
                plays_as=PLAYS_AS.get(value.get('setId'),'Ada')
                start=value.get('start')
                if start is not None:
                    full=full_queue(value['setId'])
                    if not full or full['kind']!='picks':self.respond(json.dumps({'error':'Only a Picks queue starts from a chosen item'}),'application/json',409);return
                    entry=next((e for e in full['entries'] if e['entryKey']==start.get('entryKey')),None)
                    picked=next((i for i in (entry or {}).get('items',[]) if i['ratingKey']==start.get('ratingKey')),None)
                    if picked is None:self.respond(json.dumps({'error':'That item is not in this entry'}),'application/json',404);return
                    native_starts.append({'setId':value['setId'],'start':start})
                    chosen=[picked]+([i for i in entry['items'] if i['position']>picked['position'] and i['state']!='watched'] if start.get('mode')=='continue' else [])
                    ident='native-fixture-'+str(len(native_sessions)+1)
                    plan={'id':ident,'sessionKey':'fixture-session-key','setId':value['setId'],'profileUuid':plays_as.lower(),'profileName':plays_as,'languages':PROFILE_LANGUAGES.get(plays_as),'homeServerId':'fixture-server','grants':grants(value['setId']),'sequence':0,'cursor':0,'expiresAt':int(time.time()*1000)+86400000,'refill':False,'target':len(chosen),'handed':len(chosen),
                        'items':[{'ratingKey':i['ratingKey'],'positionKey':f"{n}:home:{i['ratingKey']}",'resumeMs':0,'type':i['type'],'title':i['title'],'show':i['show'],'season':i['season'],'episode':i['episode']} for n,i in enumerate(chosen)]}
                    native_sessions[ident]=plan;self.respond(json.dumps(plan),'application/json');return
                ident='native-fixture-'+str(len(native_sessions)+1)
                plan={'id':ident,'sessionKey':'fixture-session-key','setId':value['setId'],'profileUuid':plays_as.lower(),'profileName':plays_as,'languages':PROFILE_LANGUAGES.get(plays_as),'homeServerId':'fixture-server','grants':grants(value['setId']),'sequence':0,'cursor':0,'expiresAt':int(time.time()*1000)+86400000,'refill':False,'target':2,'handed':2,'items':[{'ratingKey':'102','positionKey':'0:home:102','resumeMs':4000,'type':'episode','title':'The signal','show':'Orbit Nine','season':1,'episode':9},{'ratingKey':'103','positionKey':'1:home:103','resumeMs':0,'type':'episode','title':'The return','show':'Harbor Lights','season':2,'episode':3}]}
                plan['cursor']=native_resume.get(value['setId'],0)
                native_sessions[ident]=plan;self.respond(json.dumps(plan),'application/json');return
            ident=path.split('/')[4];plan=native_sessions.get(ident)
            if not plan or self.headers.get('X-Native-Session-Key')!='fixture-session-key':self.respond('{}','application/json',403);return
            if path.endswith('/continue'):
                if plan['cursor']==len(plan['items']):self.respond(json.dumps({'complete':True}),'application/json');return
                self.respond(json.dumps({'error':'The current lineup has not finished'}),'application/json',409);return
            if path.endswith('/events'):
                if any(e['id']==value['id'] for e in native_events):self.respond(json.dumps({'ok':True,'duplicate':True,'cursor':plan['cursor']}),'application/json');return
                if value['sequence']<=plan['sequence'] or value['index']!=plan['cursor']:
                    self.respond(json.dumps({'error':'Stale or out-of-order event'}),'application/json',409);return
                native_events.append(value);plan['sequence']=value['sequence']
                if value['state'] in ('ended','skip','section-end'):plan['cursor']+=1
                native_resume[plan['setId']]=plan['cursor'] if plan['cursor']<len(plan['items']) else 0
                self.respond(json.dumps({'ok':True,'cursor':plan['cursor']}),'application/json');return
        if path=='/api/v2/pins':
            if cloud_offline:self.respond('{}','application/json',503);return
            assert parse_qs(body).get('strong')==['false']
            next_pin+=1
            lifetime=1 if expire_next else 900;expire_next=False
            pin={'id':next_pin,'code':'ABCD','expiresIn':lifetime,'client':self.headers.get('X-Plex-Client-Identifier'),'claimed':False,'created':time.monotonic()}
            pins[next_pin]=pin;self.respond(json.dumps({k:pin[k] for k in ['id','code','expiresIn']}),'application/json');return
        if path.startswith('/api/v2/home/users/') and path.endswith('/switch'):
            if cloud_offline:self.respond('{}','application/json',503);return
            if self.headers.get('X-Plex-Token')!='fixture-root':self.respond('{}','application/json',401);return
            user=path.split('/')[-2]
            if user=='morgan' and parse_qs(body).get('pin')!=['2468']:self.respond('{}','application/json',401);return
            if user not in ['ada','grace','morgan']:self.respond('{}','application/json',404);return
            self.respond(json.dumps({'authToken':'fixture-user-'+user}),'application/json');return
        self.respond('missing',code=404)
    def do_GET(self):
        parsed=urlparse(self.path); path=parsed.path
        if path=='/_native_events':self.respond(json.dumps(native_events),'application/json');return
        if path=='/_stream_saves':self.respond(json.dumps(stream_saves),'application/json');return
        if path=='/_native_starts':self.respond(json.dumps(native_starts),'application/json');return
        if path=='/_native_calls':self.respond(json.dumps(native_calls),'application/json');return
        if path=='/_history_requests':self.respond(json.dumps(history_requests),'application/json');return
        if path=='/_queue_adds':self.respond(json.dumps(queue_adds),'application/json');return
        if path=='/_queue_marks':self.respond(json.dumps([{'setId':k[0],'ratingKey':k[1],'isWatched':v} for k,v in queue_marks.items()]),'application/json');return
        if path.startswith('/api/native/queues/') and path.endswith('/full'):
            if native_offline:self.respond(json.dumps({'error':'QueuePilot is unavailable'}),'application/json',503);return
            note_call(self,path)
            if not is_native_profile(self):self.respond(json.dumps({'error':'This TV is not signed in to this Plex Home'}),'application/json',403);return
            if refuse_locked(self,path.split('/')[4]):return
            full=full_queue(path.split('/')[4])
            if not full:self.respond(json.dumps({'error':'Unknown queue'}),'application/json',404);return
            self.respond(json.dumps(full),'application/json');return
        if path=='/api/native/history':
            if native_offline:self.respond(json.dumps({'error':'QueuePilot is unavailable'}),'application/json',503);return
            note_call(self,path)
            if not is_native_profile(self):self.respond(json.dumps({'error':'This TV is not signed in to this Plex Home'}),'application/json',403);return
            days=max(1,min(365,int(parse_qs(parsed.query).get('days',['30'])[0])))
            # Every queue's history; a locked queue without an unlock is left out and counted.
            found=history(days);found['hiddenLockedQueues']=0 if is_unlocked(self) else len(LOCKED)
            history_requests.append({'days':days});self.respond(json.dumps(found),'application/json');return
        if path.startswith('/avatars/') or (path.startswith('/api/native/people/') and path.endswith('/avatar')):
            name=path.split('/')[-2] if path.endswith('/avatar') else path.split('/')[-1].removesuffix('.jpg')
            self.respond((ROOT/(name+'.jpg')).read_bytes(),'image/jpeg');return
        if path=='/api/native/queues':
            if native_offline:self.respond(json.dumps({'error':'QueuePilot is unavailable'}),'application/json',503);return
            selected=set(parse_qs(parsed.query).get('people',[''])[0].split(','))- {''}
            # Titles repeat, as they do on a real server; people and the Plex profile tell them apart.
            names={'ada':'Ada','grace':'Grace'}
            queues=[{'id':'ada-watch','title':'Movies & Shows','kind':'picks','people':['ada']},{'id':'movies-grace','title':'Movies','kind':'picks','people':['grace']},{'id':'friends-watch','title':'Movies & Shows · 2','kind':'picks','people':['ada','grace']},{'id':'movies-ada','title':'Movies','kind':'rules','people':['ada']},{'id':'movies-family','title':'Movies','kind':'picks','people':['ada','grace']}]
            for q in queues:q.update(audience=', '.join(names[p] for p in q['people']),playsAs=PLAYS_AS[q['id']],isLocked=q['id'] in LOCKED)
            if selected:queues=[q for q in queues if selected.issubset(set(q['people'])) and 'ada' in selected]
            self.respond(json.dumps({'queues':queues,'people':[{'id':'ada','displayName':'Ada'},{'id':'grace','displayName':'Grace'}]}),'application/json');return
        if path.startswith('/api/v2/'):
            if cloud_offline:self.respond('{}','application/json',503);return
            token=self.headers.get('X-Plex-Token','')
            if path.startswith('/api/v2/pins/'):
                pin=pins.get(int(path.split('/')[-1]))
                if not pin or time.monotonic()-pin['created']>pin['expiresIn']:self.respond('{}','application/json',410);return
                if self.headers.get('X-Plex-Client-Identifier')!=pin['client']:self.respond('{}','application/json',403);return
                self.respond(json.dumps({'authToken':'fixture-root' if pin['claimed'] else None}),'application/json');return
            if path=='/api/v2/user' and token=='fixture-root':self.respond(json.dumps({'title':'Ada','id':1,'profile':ACCOUNT_PROFILE}),'application/json');return
            if path=='/api/v2/user' and token.startswith('fixture-user-'):self.respond(json.dumps({'title':token.removeprefix('fixture-user-').title(),'profile':ACCOUNT_PROFILE}),'application/json');return
            if path=='/api/v2/home/users' and token=='fixture-root':self.respond(json.dumps({'users':users}),'application/json');return
            if path=='/api/v2/resources' and token.startswith('fixture-user-'):
                user=token.removeprefix('fixture-user-')
                self.respond(json.dumps([{'name':'Demo Cinema','clientIdentifier':'fixture-server','provides':'server','accessToken':'fixture-'+user,'connections':[{'uri':'http://203.0.113.5:32400','local':False,'relay':False},{'uri':'https://relay.example.com','local':False,'relay':True},{'uri':'http://10.0.2.2:9010','local':True,'relay':False}]}]),'application/json');return
            self.respond('{}','application/json',401);return
        if path=='/_requests': self.respond(json.dumps(requests),'application/json');return
        if path=='/api/sets': self.respond(json.dumps({'sets':[{'id':'demo-watching','label':'Ada · Watching','activity':'watching'}]}),'application/json');return
        token=self.headers.get('X-Plex-Token','')
        requests.append({'path':path,'query':parse_qs(parsed.query),'range':self.headers.get('Range',''),'profile':'ada' if token=='fixture-ada' else 'grace' if token=='fixture-grace' else 'morgan' if token=='fixture-morgan' else 'unknown'})
        if token not in ('fixture-ada','fixture-grace','fixture-morgan'):self.respond('denied',code=401);return
        if offline:self.respond('unavailable',code=503);return
        if path=='/:/timeline':self.respond('<MediaContainer/>');return
        if path=='/library/onDeck':
            self.respond(f'<MediaContainer><Video ratingKey="102" type="episode" index="9" title="The signal" thumb="/art/poster.jpg" {SHOW_FIELDS["102"]}/><Video ratingKey="103" type="episode" index="3" title="The return" thumb="/art/poster.jpg" {SHOW_FIELDS["103"]}/></MediaContainer>');return
        if path=='/library/sections':
            self.respond('<MediaContainer><Directory key="1" type="show" title="Series"/><Directory key="2" type="movie" title="Films"/><Directory key="3" type="movie" title="Archive"/></MediaContainer>');return
        query=parse_qs(parsed.query)
        if path=='/library/sections/2/all' and not query:
            self.respond(films());return
        if path in ('/library/sections/1/all','/library/sections/2/all','/library/sections/3/all'):
            self.respond(section_page(path.split('/')[3],query));return
        if path in ('/library/sections/1/firstCharacter','/library/sections/2/firstCharacter','/library/sections/3/firstCharacter'):
            self.respond(first_characters(path.split('/')[3]));return
        if path=='/hubs/search':
            self.respond(search_hubs(query.get('query',[''])[0],query.get('sectionId',[''])[0]));return
        if path=='/library/metadata/100/children':
            self.respond('<MediaContainer><Directory ratingKey="101" type="season" title="Season 1" thumb="/art/poster.jpg"/></MediaContainer>');return
        if path=='/library/metadata/101/children':
            self.respond(f'<MediaContainer><Video ratingKey="102" grandparentRatingKey="100" type="episode" index="9" title="The signal" thumb="/art/poster.jpg" {SHOW_FIELDS["102"]}/></MediaContainer>');return
        if path=='/library/metadata/200/children':
            self.respond('<MediaContainer><Directory ratingKey="201" type="season" title="Season 2" thumb="/art/poster.jpg"/></MediaContainer>');return
        if path=='/library/metadata/201/children':
            self.respond(f'<MediaContainer><Video ratingKey="103" grandparentRatingKey="200" type="episode" index="3" title="The return" thumb="/art/poster.jpg" {SHOW_FIELDS["103"]}/></MediaContainer>');return
        if path in ('/library/metadata/100','/library/metadata/200'):
            # Like many anime libraries, the show holds the cast and its episodes carry no Role.
            # One portrait is a server path; the other is an external HTTPS portrait host.
            self.respond(f'<MediaContainer><Directory ratingKey="{path.split("/")[-1]}" type="show" title="{"Orbit Nine" if path.endswith("100") else "Harbor Lights"}" year="2021" contentRating="TV-PG" studio="Northlight Studio" childCount="1" leafCount="12" viewedLeafCount="3" summary="A relay crew keeps a distant station talking to home." thumb="/art/poster.jpg"><Genre tag="Science Fiction"/><Genre tag="Drama"/><Role tag="Nadia Lane" role="Captain Mira" thumb="/art/nadia.jpg"/><Role tag="Omar Reed" role="Sol" thumb="{EXTERNAL_PORTRAIT}"/></Directory></MediaContainer>');return
        if path in ('/library/metadata/101','/library/metadata/201'):
            self.respond(f'<MediaContainer><Directory ratingKey="{path.split("/")[-1]}" parentRatingKey="{"100" if path.endswith("101") else "200"}" type="season" title="Season {1 if path.endswith("101") else 2}" thumb="/art/poster.jpg"/></MediaContainer>');return
        if path=='/library/metadata/103':
            self.respond(f'<MediaContainer><Video ratingKey="103" parentRatingKey="201" grandparentRatingKey="200" {SHOW_FIELDS["103"]} type="episode" index="3" title="The return" duration="30000" thumb="/art/poster.jpg"><Media><Part id="1103" indexes="sd" key="/video/sample.mp4"/></Media><Role tag="Nadia Lane" role="Captain Mira (flashback)" thumb="/art/nadia.jpg"/></Video></MediaContainer>');return
        if path=='/library/metadata/102':
            self.respond(f'<MediaContainer><Video ratingKey="102" parentRatingKey="101" grandparentRatingKey="100" {SHOW_FIELDS["102"]} type="episode" index="9" title="The signal" duration="{240000 if video_fixture.endswith("styled.mkv") else 30000}" viewOffset="{60000 if video_fixture.endswith("styled.mkv") else 4000}" thumb="/art/poster.jpg">{plex_media()}<Director tag="Lena Hart"/>{MARKERS if markers and parse_qs(parsed.query).get("includeMarkers")==["1"] else ""}{CHAPTERS if plex_tracks and parse_qs(parsed.query).get("includeChapters")==["1"] else ""}</Video></MediaContainer>');return
        if path=='/library/streams/9005' and plex_tracks=='external':
            self.respond(EXTERNAL_SRT,'application/x-subrip');return
        if path=='/library/metadata/302':
            self.respond(GLASS_CITY);return
        if path in ('/library/parts/1102/indexes/sd','/library/parts/1103/indexes/sd'):
            self.respond(bif(),'application/octet-stream');return
        if path=='/photo/:/transcode':
            width=int(parse_qs(parsed.query).get('width',['240'])[0]);art=parse_qs(parsed.query).get('url',[''])[0];name=art.split('/')[-1] if art in ('/art/nadia.jpg','/art/omar.jpg',EXTERNAL_PORTRAIT) else ('high.jpg' if width>240 else 'low.jpg');data=(ROOT/name).read_bytes();self.respond(data,'image/jpeg');return
        if path=='/video/sample.mp4':
            data=(ROOT/video_fixture).read_bytes();start=0;end=len(data)-1;range_header=self.headers.get('Range','')
            if range_header.startswith('bytes='):
                values=range_header[6:].split('-');start=int(values[0]);end=int(values[1]) if values[1] else end
            self.send_response(206 if range_header else 200);self.send_header('Content-Type','video/x-matroska' if video_fixture.endswith('.mkv') else 'video/mp4');self.send_header('Accept-Ranges','bytes');self.send_header('Content-Length',str(end-start+1));
            if range_header:self.send_header('Content-Range',f'bytes {start}-{end}/{len(data)}')
            self.end_headers()
            try:self.wfile.write(data[start:end+1])
            except (BrokenPipeError,ConnectionResetError):pass
            return
        self.respond('missing',code=404)
def do_PUT(self):
    """PUT /library/parts/<id>: Plex saves the profile's audio and subtitle choice for the part."""
    parsed=urlparse(self.path);path=parsed.path;query=parse_qs(parsed.query);token=self.headers.get('X-Plex-Token','')
    profile={'fixture-ada':'ada','fixture-grace':'grace','fixture-morgan':'morgan'}.get(token,'unknown')
    if profile=='unknown':self.respond('denied',code=401);return
    if path!='/library/parts/1102':self.respond('missing',code=404);return
    stream_saves.append({'path':path,'query':query,'profile':profile})
    if 'audioStreamID' in query:stream_choice['audio']=int(query['audioStreamID'][0])
    if 'subtitleStreamID' in query:stream_choice['subtitle']=int(query['subtitleStreamID'][0])
    self.respond('')
Handler.do_PUT=do_PUT
if __name__=='__main__':ThreadingHTTPServer(('0.0.0.0',9010),Handler).serve_forever()

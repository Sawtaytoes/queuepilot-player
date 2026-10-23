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
            _entry('lantern-pictures','Lantern Pictures','collection','queue',[_film(4+i,k) for i,k in enumerate([306,307,308,309,310,311,301])])]},
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
def is_native_profile(handler):return handler.headers.get('X-Plex-Profile-Token') in ('fixture-user-ada','fixture-root')

users = [{'id':1,'uuid':'ada','title':'Ada','protected':False,'admin':True,'thumb':'/avatars/ada.jpg'}, {'id':2,'uuid':'grace','title':'Grace','protected':False,'thumb':'/avatars/grace.jpg'}, {'id':3,'uuid':'morgan','title':'Morgan','protected':True,'thumb':'/avatars/nadia.jpg'}]

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def respond(self, data, mime='application/xml', code=200):
        if isinstance(data,str): data=data.encode()
        self.send_response(code); self.send_header('Content-Type',mime); self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
    def do_POST(self):
        global offline, cloud_offline, next_pin, expire_next, native_offline, video_fixture, markers
        path=urlparse(self.path).path
        if path=='/_offline': offline=True; self.respond('ok');return
        if path=='/_ass': video_fixture='styled.mkv';self.respond('ok');return
        if path=='/_tracks': video_fixture='tracks.mkv';self.respond('ok');return
        if path=='/_unnamed_tracks': video_fixture='tracks.mp4';self.respond('ok');return
        if path=='/_markers': markers=True;self.respond('ok');return
        if path=='/_online': video_fixture='sample.mp4';markers=False;offline=False; cloud_offline=False; native_offline=False;self.respond('ok');return
        if path=='/_native_reset':native_events.clear();native_sessions.clear();native_resume.clear();queue_marks.clear();native_starts.clear();history_requests.clear();self.respond('ok');return
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
            if path.startswith('/api/native/queues/') and path.endswith('/watched'):
                if not is_native_profile(self):self.respond(json.dumps({'error':'Switch to the queue profile'}),'application/json',403);return
                set_id=path.split('/')[4];full=full_queue(set_id)
                if not full:self.respond(json.dumps({'error':'Unknown queue'}),'application/json',404);return
                entry=next((e for e in full['entries'] if e['entryKey']==value.get('entryKey')),None)
                if not entry or not any(i['ratingKey']==value.get('ratingKey') for i in entry['items']):self.respond(json.dumps({'error':'Unknown item'}),'application/json',404);return
                if entry['watchHistory']!='queue':self.respond(json.dumps({'error':'This entry keeps its watched state in Plex'}),'application/json',409);return
                queue_marks[(set_id,value['ratingKey'])]=bool(value.get('isWatched'))
                self.respond(json.dumps({'entryKey':value['entryKey'],'ratingKey':value['ratingKey'],'state':'watched' if value.get('isWatched') else 'unwatched'}),'application/json');return
            if path=='/api/native/sessions':
                if self.headers.get('X-Plex-Profile-Token') not in ('fixture-user-ada','fixture-root'):self.respond(json.dumps({'error':'Switch to the queue profile'}),'application/json',403);return
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
                    plan={'id':ident,'sessionKey':'fixture-session-key','setId':value['setId'],'profileUuid':'ada','profileName':'Ada','homeServerId':'fixture-server','sequence':0,'cursor':0,'expiresAt':int(time.time()*1000)+86400000,'refill':False,'target':len(chosen),'handed':len(chosen),
                        'items':[{'ratingKey':i['ratingKey'],'positionKey':f"{n}:home:{i['ratingKey']}",'resumeMs':0,'type':i['type'],'title':i['title'],'show':i['show'],'season':i['season'],'episode':i['episode']} for n,i in enumerate(chosen)]}
                    native_sessions[ident]=plan;self.respond(json.dumps(plan),'application/json');return
                ident='native-fixture-'+str(len(native_sessions)+1)
                plan={'id':ident,'sessionKey':'fixture-session-key','setId':value['setId'],'profileUuid':'ada','profileName':'Ada','homeServerId':'fixture-server','sequence':0,'cursor':0,'expiresAt':int(time.time()*1000)+86400000,'refill':False,'target':2,'handed':2,'items':[{'ratingKey':'102','positionKey':'0:home:102','resumeMs':4000,'type':'episode','title':'The signal','show':'Orbit Nine','season':1,'episode':9},{'ratingKey':'103','positionKey':'1:home:103','resumeMs':0,'type':'episode','title':'The return','show':'Harbor Lights','season':2,'episode':3}]}
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
        if path=='/_native_starts':self.respond(json.dumps(native_starts),'application/json');return
        if path=='/_history_requests':self.respond(json.dumps(history_requests),'application/json');return
        if path=='/_queue_marks':self.respond(json.dumps([{'setId':k[0],'ratingKey':k[1],'isWatched':v} for k,v in queue_marks.items()]),'application/json');return
        if path.startswith('/api/native/queues/') and path.endswith('/full'):
            if native_offline:self.respond(json.dumps({'error':'QueuePilot is unavailable'}),'application/json',503);return
            if not is_native_profile(self):self.respond(json.dumps({'error':'Switch to the queue profile'}),'application/json',403);return
            full=full_queue(path.split('/')[4])
            if not full:self.respond(json.dumps({'error':'This queue plays as another profile'}),'application/json',403);return
            self.respond(json.dumps(full),'application/json');return
        if path=='/api/native/history':
            if native_offline:self.respond(json.dumps({'error':'QueuePilot is unavailable'}),'application/json',503);return
            if not is_native_profile(self):self.respond(json.dumps({'error':'Switch to the queue profile'}),'application/json',403);return
            days=max(1,min(365,int(parse_qs(parsed.query).get('days',['30'])[0])))
            history_requests.append({'days':days});self.respond(json.dumps(history(days)),'application/json');return
        if path.startswith('/avatars/') or (path.startswith('/api/native/people/') and path.endswith('/avatar')):
            name=path.split('/')[-2] if path.endswith('/avatar') else path.split('/')[-1].removesuffix('.jpg')
            self.respond((ROOT/(name+'.jpg')).read_bytes(),'image/jpeg');return
        if path=='/api/native/queues':
            if native_offline:self.respond(json.dumps({'error':'QueuePilot is unavailable'}),'application/json',503);return
            selected=set(parse_qs(parsed.query).get('people',[''])[0].split(','))- {''}
            # Titles repeat, as they do on a real server; people and the Plex profile tell them apart.
            queues=[{'id':'ada-watch','title':'Movies & Shows','kind':'picks','people':['ada'],'requiredProfile':'Ada'},{'id':'movies-grace','title':'Movies','kind':'picks','people':['grace'],'requiredProfile':'Grace'},{'id':'friends-watch','title':'Movies & Shows · 2','kind':'picks','people':['ada','grace'],'requiredProfile':'Ada'},{'id':'movies-ada','title':'Movies','kind':'rules','people':['ada'],'requiredProfile':'Ada'},{'id':'movies-family','title':'Movies','kind':'picks','people':['ada','grace'],'requiredProfile':'Morgan'}]
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
            if path=='/api/v2/user' and token=='fixture-root':self.respond(json.dumps({'title':'Ada','id':1}),'application/json');return
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
            self.respond('<MediaContainer><Directory key="1" type="show" title="Series"/><Directory key="2" type="movie" title="Films"/></MediaContainer>');return
        if path=='/library/sections/2/all':
            self.respond(films());return
        if path=='/library/sections/1/all':
            self.respond('<MediaContainer><Directory ratingKey="100" key="/library/metadata/100/children" type="show" title="Orbit Nine" thumb="/art/poster.jpg"/><Directory ratingKey="200" key="/library/metadata/200/children" type="show" title="Harbor Lights" thumb="/art/poster.jpg"/></MediaContainer>');return
        if path=='/library/metadata/100/children':
            self.respond('<MediaContainer><Directory ratingKey="101" type="season" title="Season 1" thumb="/art/poster.jpg"/></MediaContainer>');return
        if path=='/library/metadata/101/children':
            self.respond(f'<MediaContainer><Video ratingKey="102" type="episode" index="9" title="The signal" thumb="/art/poster.jpg" {SHOW_FIELDS["102"]}/></MediaContainer>');return
        if path=='/library/metadata/200/children':
            self.respond('<MediaContainer><Directory ratingKey="201" type="season" title="Season 2" thumb="/art/poster.jpg"/></MediaContainer>');return
        if path=='/library/metadata/201/children':
            self.respond(f'<MediaContainer><Video ratingKey="103" type="episode" index="3" title="The return" thumb="/art/poster.jpg" {SHOW_FIELDS["103"]}/></MediaContainer>');return
        if path in ('/library/metadata/100','/library/metadata/200'):
            # Like many anime libraries, the show holds the cast and its episodes carry no Role.
            # One portrait is a server path; the other is an external HTTPS portrait host.
            self.respond(f'<MediaContainer><Directory ratingKey="{path.split("/")[-1]}" type="show" title="{"Orbit Nine" if path.endswith("100") else "Harbor Lights"}" year="2021" contentRating="TV-PG" studio="Northlight Studio" childCount="1" leafCount="12" viewedLeafCount="3" summary="A relay crew keeps a distant station talking to home." thumb="/art/poster.jpg"><Genre tag="Science Fiction"/><Genre tag="Drama"/><Role tag="Nadia Lane" role="Captain Mira" thumb="/art/nadia.jpg"/><Role tag="Omar Reed" role="Sol" thumb="{EXTERNAL_PORTRAIT}"/></Directory></MediaContainer>');return
        if path in ('/library/metadata/101','/library/metadata/201'):
            self.respond(f'<MediaContainer><Directory ratingKey="{path.split("/")[-1]}" parentRatingKey="{"100" if path.endswith("101") else "200"}" type="season" title="Season {1 if path.endswith("101") else 2}" thumb="/art/poster.jpg"/></MediaContainer>');return
        if path=='/library/metadata/103':
            self.respond(f'<MediaContainer><Video ratingKey="103" parentRatingKey="201" grandparentRatingKey="200" {SHOW_FIELDS["103"]} type="episode" index="3" title="The return" duration="30000" thumb="/art/poster.jpg"><Media><Part id="1103" indexes="sd" key="/video/sample.mp4"/></Media><Role tag="Nadia Lane" role="Captain Mira (flashback)" thumb="/art/nadia.jpg"/></Video></MediaContainer>');return
        if path=='/library/metadata/102':
            self.respond(f'<MediaContainer><Video ratingKey="102" parentRatingKey="101" grandparentRatingKey="100" {SHOW_FIELDS["102"]} type="episode" index="9" title="The signal" duration="30000" viewOffset="{60000 if video_fixture.endswith("styled.mkv") else 4000}" thumb="/art/poster.jpg"><Media><Part id="1102" indexes="sd" key="/video/sample.mp4"/></Media><Director tag="Lena Hart"/>{MARKERS if markers and parse_qs(parsed.query).get("includeMarkers")==["1"] else ""}</Video></MediaContainer>');return
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
if __name__=='__main__':ThreadingHTTPServer(('0.0.0.0',9010),Handler).serve_forever()

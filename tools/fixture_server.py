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
users = [{'id': 1, 'uuid':'ada', 'title':'Ada', 'protected':False}, {'id':2, 'uuid':'grace', 'title':'Grace', 'protected':False}, {'id':3, 'uuid':'morgan', 'title':'Morgan', 'protected':True}]

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def respond(self, data, mime='application/xml', code=200):
        if isinstance(data,str): data=data.encode()
        self.send_response(code); self.send_header('Content-Type',mime); self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
    def do_POST(self):
        global offline, cloud_offline, next_pin, expire_next
        path=urlparse(self.path).path
        if path=='/_offline': offline=True; self.respond('ok');return
        if path=='/_online': offline=False; cloud_offline=False; self.respond('ok');return
        if path=='/_cloud_offline': cloud_offline=True;self.respond('ok');return
        if path=='/_expire_next': expire_next=True;self.respond('ok');return
        if path=='/_claim':
            if pins: pins[max(pins)]['claimed']=True
            self.respond('ok');return
        body=self.rfile.read(int(self.headers.get('Content-Length','0'))).decode()
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
        if path=='/library/onDeck':
            self.respond('<MediaContainer><Video ratingKey="102" type="episode" index="9" title="The signal" thumb="/art/poster.jpg"/></MediaContainer>');return
        if path=='/library/sections':
            self.respond('<MediaContainer><Directory key="1" type="show" title="Series"/></MediaContainer>');return
        if path=='/library/sections/1/all':
            self.respond('<MediaContainer><Directory ratingKey="100" key="/library/metadata/100/children" type="show" title="Orbit Nine" thumb="/art/poster.jpg"/></MediaContainer>');return
        if path=='/library/metadata/100/children':
            self.respond('<MediaContainer><Directory ratingKey="101" type="season" title="Season 1" thumb="/art/poster.jpg"/></MediaContainer>');return
        if path=='/library/metadata/101/children':
            self.respond('<MediaContainer><Video ratingKey="102" type="episode" index="9" title="The signal" thumb="/art/poster.jpg"/></MediaContainer>');return
        if path=='/library/metadata/102':
            self.respond('<MediaContainer><Video ratingKey="102" type="episode" index="9" title="The signal" thumb="/art/poster.jpg"><Media><Part key="/video/sample.mp4"/></Media><Role tag="Nadia Lane" role="Captain Mira"/><Role tag="Omar Reed" role="Sol"/></Video></MediaContainer>');return
        if path=='/photo/:/transcode':
            width=int(parse_qs(parsed.query).get('width',['240'])[0]); data=(ROOT/('high.jpg' if width>240 else 'low.jpg')).read_bytes();self.respond(data,'image/jpeg');return
        if path=='/video/sample.mp4':
            data=(ROOT/'sample.mp4').read_bytes();start=0;end=len(data)-1;range_header=self.headers.get('Range','')
            if range_header.startswith('bytes='):
                values=range_header[6:].split('-');start=int(values[0]);end=int(values[1]) if values[1] else end
            self.send_response(206 if range_header else 200);self.send_header('Content-Type','video/mp4');self.send_header('Accept-Ranges','bytes');self.send_header('Content-Length',str(end-start+1));
            if range_header:self.send_header('Content-Range',f'bytes {start}-{end}/{len(data)}')
            self.end_headers()
            try:self.wfile.write(data[start:end+1])
            except (BrokenPipeError,ConnectionResetError):pass
            return
        self.respond('missing',code=404)
if __name__=='__main__':ThreadingHTTPServer(('0.0.0.0',9010),Handler).serve_forever()

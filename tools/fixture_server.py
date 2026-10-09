#!/usr/bin/env python3
"""Local Plex-shaped fixture. All identities and tokens are invented."""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlparse, parse_qs
import json, os
ROOT = Path(os.environ.get('FIXTURE_ROOT','/fixtures'))
requests = []
offline = False
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def respond(self, data, mime='application/xml', code=200):
        if isinstance(data,str): data=data.encode()
        self.send_response(code); self.send_header('Content-Type',mime); self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
    def do_POST(self):
        global offline
        offline = self.path == '/_offline'; self.respond('ok','text/plain')
    def do_GET(self):
        parsed=urlparse(self.path); path=parsed.path
        if path=='/_requests': self.respond(json.dumps(requests),'application/json');return
        if path=='/api/sets': self.respond(json.dumps({'sets':[{'id':'demo-watching','label':'Ada · Watching','activity':'watching'}]}),'application/json');return
        token=self.headers.get('X-Plex-Token','')
        requests.append({'path':path,'query':parse_qs(parsed.query),'range':self.headers.get('Range',''),'profile':'ada' if token=='fixture-ada' else 'grace' if token=='fixture-grace' else 'unknown'})
        if token not in ('fixture-ada','fixture-grace'):self.respond('denied',code=401);return
        if offline:self.respond('unavailable',code=503);return
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

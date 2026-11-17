#!/usr/bin/env python3
"""Performance probe: start time, frame timing, memory, and StrictMode findings.

Emulator only, run through `queuepilot-test run`, which holds the shared emulator's lock and
restores the person's session afterward:

  queuepilot-test run --source /source/.worktrees/<branch> --label '<branch> perf' -- \\
    python3 -u /source/.worktrees/<branch>/tools/perf_probe.py \\
      [--apk <label>=<path to an APK> ...] [--strict] [--quick]

Each --apk is installed over the app in turn and measured with the same steps; with none, the
APK the run installed is measured as "candidate". --strict adds a pass with StrictMode on
(DebugChecks.java), which logs main-thread disk and network work, and collects it.

The emulator renders in software on the host CPU, so its frame times are not a TV's. Compare
builds with each other on the same run, and use the results to find main-thread stalls; use
a TV's own `dumpsys gfxinfo` for absolute numbers. Results go to stdout and to
/fixtures/perf-<label>.json.
"""
import argparse,json,re,statistics,subprocess,sys,time
import smoke as s

KEYS={'up':19,'down':20,'left':21,'right':22,'enter':23,'back':4,'home':3}
COMPONENT=s.PACKAGE+'/dev.queuepilot.player.MainActivity'

def key(name,times=1,pause=.35):
    for _ in range(times):s.adb('shell','input','keyevent',str(KEYS[name]));time.sleep(pause)

def start(extra=()):
    """am start -W: LaunchState, TotalTime and WaitTime in milliseconds."""
    out=s.adb('shell','am','start','-W','-n',COMPONENT,'--ez','fixture','true',*extra).decode()
    found=dict(re.findall(r'^(LaunchState|TotalTime|WaitTime): (\S+)',out,re.M))
    return {'state':found.get('LaunchState','?'),'total':int(found.get('TotalTime',-1)),'wait':int(found.get('WaitTime',-1))}

def wait_backgrounded(seconds=15):
    """Home has taken effect: the app's activity is no longer the resumed one."""
    for _ in range(seconds*2):
        resumed=[l for l in s.adb('shell','dumpsys','activity','activities').decode(errors='replace').splitlines() if 'mResumedActivity' in l or 'ResumedActivity:' in l]
        if resumed and not any(s.PACKAGE in l for l in resumed):return
        time.sleep(.5)
    raise AssertionError('The app did not leave the screen after Home')

def gfx_reset():s.adb('shell','dumpsys','gfxinfo',s.PACKAGE,'reset')

def gfx():
    """The frame summary since the last reset, plus per-frame times from framestats."""
    out=s.adb('shell','dumpsys','gfxinfo',s.PACKAGE,'framestats').decode()
    result={}
    for name,pattern in (('frames',r'Total frames rendered: (\d+)'),('janky',r'Janky frames: (\d+)'),
                         ('p50',r'^50th percentile: (\d+)ms'),('p90',r'^90th percentile: (\d+)ms'),
                         ('p95',r'^95th percentile: (\d+)ms'),('p99',r'^99th percentile: (\d+)ms'),
                         ('slowUi',r'Number Slow UI thread: (\d+)'),('slowBitmap',r'Number Slow bitmap uploads: (\d+)'),
                         ('slowDraw',r'Number Slow issue draw commands: (\d+)'),('missedVsync',r'Number Missed Vsync: (\d+)')):
        match=re.search(pattern,out,re.M)
        result[name]=int(match.group(1)) if match else None
    # framestats rows: Flags,IntendedVsync,Vsync,...,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,...,FrameCompleted
    header=None;totals=[];ui=[]
    for line in out.splitlines():
        if line.startswith('Flags,IntendedVsync'):header=line.rstrip(',').split(',');continue
        if header and re.match(r'^\d+,\d+',line):
            row=dict(zip(header,map(int,line.rstrip(',').split(','))))
            if row['Flags']!=0:continue
            totals.append((row['FrameCompleted']-row['IntendedVsync'])/1e6)
            ui.append((row['SyncQueued']-row['HandleInputStart'])/1e6 if row.get('SyncQueued') else 0)
        elif header and not line.strip():header=None
    if totals:
        totals.sort();ui.sort()
        result['frameMsMedian']=round(statistics.median(totals),1)
        result['frameMsP95']=round(totals[int(len(totals)*.95)-1 if len(totals)>1 else 0],1)
        result['uiThreadMsMedian']=round(statistics.median(ui),1)
        result['uiThreadMsP95']=round(ui[int(len(ui)*.95)-1 if len(ui)>1 else 0],1)
        result['over16ms']=sum(1 for t in totals if t>16.7)
    return result

def meminfo():
    out=s.adb('shell','dumpsys','meminfo',s.PACKAGE).decode()
    def kb(pattern):
        match=re.search(pattern,out,re.M);return int(match.group(1)) if match else None
    return {'totalPssKb':kb(r'TOTAL PSS:\s+(\d+)'),'javaHeapKb':kb(r'^\s*Java Heap:\s+(\d+)'),
            'nativeHeapKb':kb(r'^\s*Native Heap:\s+(\d+)'),'graphicsKb':kb(r'^\s*Graphics:\s+(\d+)'),
            'views':kb(r'Views:\s+(\d+)')}

def wait_text(value,tries=30):
    for _ in range(tries):
        if value in s.text():return
        time.sleep(.5)
    raise AssertionError('Missing text: '+value+'\n'+s.text())

def wait_for_network(seconds=120):
    """A restored snapshot brings the guest's network up 20 s or more later; the Plex link screen
    does not retry, so sign in only once the fixture server answers."""
    for _ in range(seconds):
        if subprocess.run(['adb','-s',s.SERIAL,'shell','ping','-c','1','-W','1','10.0.2.2'],capture_output=True).returncode==0:return
        time.sleep(1)
    raise AssertionError('The emulator has no network')

def sign_in():
    """A fresh fixture account signed in as Ada, with the fixture QueuePilot connected."""
    wait_for_network()
    s.post('_online');s.adb('shell','pm','clear',s.PACKAGE);s.launch();wait_text('Your people. Your queues.')
    s.click('Explore the demo');wait_text('What are we watching?')
    s.click('Settings');s.click('Connect Plex');wait_text('ABCD');s.post('_claim');wait_text('What are we watching?')
    if s.has_desc('Switch profile, Morgan'):s.click('Switch profile, Morgan');s.click('Ada');wait_text('What are we watching?')
    s.click('Watching')
    if 'Connect QueuePilot' in s.text():
        s.click('Connect QueuePilot')
        field=next(n for n in s.screen().iter('node') if n.get('class')=='android.widget.EditText')
        x1,y1,x2,y2=map(int,re.findall(r'\d+',field.get('bounds')))
        s.adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));s.adb('shell','input','text','http://10.0.2.2:9010')
        s.adb('shell','input','keyevent','4');s.click('Connect');time.sleep(2)
    s.click('Home');wait_text('What are we watching?');time.sleep(3)

def ensure_inspector():
    """Install the emulator-only UI inspector, as tv-checks.sh does, when it is missing."""
    if b'dev.queuepilot.inspector' in s.adb('shell','pm','list','packages','dev.queuepilot.inspector'):return
    import os,subprocess
    source=os.environ['QUEUEPILOT_TEST_SOURCE'];fixtures=os.environ['FIXTURE_ROOT']
    subprocess.check_call(['bash',source+'/tools/ui-inspector/build.sh',fixtures+'/inspector-build'])
    s.adb('install','-r',fixtures+'/inspector-build/inspector.apk')

def measure(label,quick):
    result={'label':label}
    sign_in()
    # Start time. Cold: the process is gone. Hot: the activity was only in the background.
    cold=[];hot=[]
    for _ in range(2 if quick else 4):
        s.adb('shell','am','force-stop',s.PACKAGE);time.sleep(1.5);cold.append(start());time.sleep(4)
    for _ in range(2 if quick else 4):
        key('home',pause=.5);wait_backgrounded();hot.append(start());time.sleep(2)
    result['coldStart']=cold;result['hotStart']=hot
    # A start that found the app still in front reports LaunchState UNKNOWN and no time.
    cold_ok=[r['total'] for r in cold[1:] or cold if r['state']=='COLD'];hot_ok=[r['total'] for r in hot if r['state']=='HOT']
    result['coldStartMsMedian']=statistics.median(cold_ok) if cold_ok else None
    result['hotStartMsMedian']=statistics.median(hot_ok) if hot_ok else None
    # Home: move focus across the queue cards and down through the rows.
    s.adb('shell','am','force-stop',s.PACKAGE);s.launch();wait_text('What are we watching?');time.sleep(4)
    result['homeMemory']=meminfo()
    gfx_reset()
    key('right',4);key('left',4);key('down',4);key('up',4)
    result['homeFrames']=gfx()
    # A library grid: the Archive's few hundred films, down 20 rows, then 30 more.
    s.click('Movies');s.open_library('Archive');wait_text('titles · Sorted by Title');time.sleep(3)
    s.click('Jump to #');time.sleep(2)
    gfx_reset()
    key('down',20,pause=.4)
    result['gridFrames']=gfx()
    time.sleep(3);result['gridMemory20Rows']=meminfo()
    if not quick:
        key('down',30,pause=.4);time.sleep(4);result['gridMemory50Rows']=meminfo()
    # The title screen: open the focused film and move between its actions and cast.
    key('enter');wait_text('Watch with Ada');time.sleep(3)
    gfx_reset()
    key('down',3);key('right',3);key('left',3);key('up',3)
    result['titleFrames']=gfx();result['titleMemory']=meminfo()
    # Back to the grid: the artwork is drawn again.
    gfx_reset();key('back',pause=2.5);result['backToGridFrames']=gfx()
    key('back',pause=1.5)
    crashes=s.adb('logcat','-d','-b','crash').decode()
    result['crashed']='FATAL EXCEPTION' in crashes and s.PACKAGE in crashes
    return result

def strict_pass():
    """Launch with StrictMode, walk Home, a grid, a title and a few seconds of playback."""
    s.adb('logcat','-c')
    s.adb('shell','am','force-stop',s.PACKAGE);start(('--ez','strict-mode','true'));wait_text('What are we watching?');time.sleep(4)
    key('right',3);key('down',3)
    s.click('Movies');s.open_library('Archive');wait_text('titles · Sorted by Title');time.sleep(3)
    s.click('Jump to #');key('down',6,pause=.4);key('enter');wait_text('Watch with Ada');time.sleep(2)
    s.click('Play');time.sleep(8);key('back',pause=2);key('back',pause=2)
    log=s.adb('logcat','-d','-v','brief','StrictMode:D','*:S').decode(errors='replace')
    with open('/fixtures/perf-strictmode.txt','w') as out:out.write(log)
    # Each violation is one log line naming it, followed by one log line per stack frame.
    violations={};current=None;frames=[]
    def close():
        if current:
            where=next((f for f in frames if f.startswith('dev.queuepilot.')),'(no app frame)')
            key=current+' at '+where;violations[key]=violations.get(key,0)+1
    for line in log.splitlines():
        head=re.search(r'StrictMode policy violation[^:]*: (?:android\.os\.strictmode\.)?(\w+)',line)
        if head:close();current=head.group(1);frames=[];continue
        frame=re.search(r'\bat ([\w$.]+)\(',line)
        if frame and current:frames.append(frame.group(1))
    close()
    return violations

def main():
    hardware=s.adb('shell','getprop','ro.hardware').decode().strip()
    assert hardware in ('ranchu','goldfish'),'The probe must never target a physical TV'
    parser=argparse.ArgumentParser()
    parser.add_argument('--apk',action='append',default=[],help='label=path, measured in order')
    parser.add_argument('--strict',action='store_true');parser.add_argument('--quick',action='store_true')
    parser.add_argument('--strict-only',action='store_true',help='sign in and run only the StrictMode pass')
    args=parser.parse_args()
    s.adb('shell','wm','size','reset');s.adb('shell','wm','density','reset');s.adb('logcat','-b','crash','-c')
    ensure_inspector()
    if args.strict_only:
        sign_in();found=strict_pass();print(json.dumps(found,indent=1))
        print('PASS: StrictMode pass finished; the log is /fixtures/perf-strictmode.txt');return
    builds=[a.split('=',1) for a in args.apk] or [['candidate',None]]
    results=[]
    for label,path in builds:
        if path:print('Install',label,path,flush=True);s.adb('install','-r',path)
        result=measure(label,args.quick)
        if args.strict and label==builds[-1][0]:result["strictMode"]=strict_pass()
        results.append(result)
        print(json.dumps(result,indent=1),flush=True)
        with open('/fixtures/perf-'+label+'.json','w') as out:json.dump(result,out,indent=1)
    if len(results)>1:
        print('\nSummary')
        rows=[('cold start ms (median)','coldStartMsMedian',None),('hot start ms (median)','hotStartMsMedian',None)]
        for scene in ('homeFrames','gridFrames','titleFrames','backToGridFrames'):
            for field in ('frames','janky','p90','frameMsMedian','uiThreadMsP95','slowBitmap'):rows.append((scene+' '+field,scene,field))
        for scene in ('homeMemory','gridMemory20Rows','gridMemory50Rows','titleMemory'):
            for field in ('totalPssKb','nativeHeapKb','javaHeapKb','views'):rows.append((scene+' '+field,scene,field))
        for name,first,second in rows:
            values=[(r.get(first) or {}).get(second) if second else r.get(first) for r in results]
            print(f'{name:40}'+''.join(f'{str(v):>14}' for v in values))
    print('PASS: performance probe finished for',', '.join(label for label,_ in builds))

if __name__=='__main__':main()

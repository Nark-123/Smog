#!/usr/bin/env python3
"""Synthetic profiles; real iteration-1 A, new B/C/D. Whole invocation <=290 seconds."""
from pathlib import Path
import argparse,json,os,shlex,signal,subprocess,time,shutil,hashlib
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--baseline',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--sections',type=int,choices=[1,4,8,16],default=4);p.add_argument('--prepare',action='store_true');p.add_argument('--prepare-fixture',action='store_true');p.add_argument('--compile-only',action='store_true');p.add_argument('--modes',default='');a=p.parse_args()
OUT=a.output.resolve();OUT.mkdir(parents=True,exist_ok=True);CACHE=OUT/('cache-'+str(a.sections));TMP=OUT/'tmp';TMP.mkdir(exist_ok=True)
ENV=os.environ.copy();
JAVA=shlex.split(ENV.get('SMOG_JAVA','java'))+['-ea','-Xmx1800m','-Dsmog.geometryCacheMiB=128','-Djava.io.tmpdir='+str(TMP)]
JAVAC=shlex.split(ENV.get('SMOG_JAVAC','javac'));START=time.monotonic();ROWS=[]
def run(name,cmd,limit=170):
 t=time.monotonic()
 with (OUT/(name+'.log')).open('w') as log:
  proc=subprocess.Popen(list(map(str,cmd)),cwd=ROOT,env=ENV,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
  try:code=proc.wait(timeout=max(.01,min(limit,290-(time.monotonic()-START))))
  except subprocess.TimeoutExpired:os.killpg(proc.pid,signal.SIGKILL);proc.wait();code=124
 row=dict(name=name,exit=code,seconds=round(time.monotonic()-t,3),max_seconds=limit);ROWS.append(row);print(json.dumps(row),flush=True)
 if code:raise RuntimeError((OUT/(name+'.log')).read_text()[-5000:])
failure=None
try:
 for relative,expected in json.loads((ROOT/'verification/restart-base-sha256.json').read_text()).items():
  if hashlib.sha256((a.baseline.resolve()/relative).read_bytes()).hexdigest()!=expected:raise RuntimeError('Baseline SHA-256 mismatch: '+relative)
 if a.prepare or a.prepare_fixture or a.compile_only:
  for label,base in [('current',ROOT),('baseline',a.baseline.resolve())]:
   classes=OUT/(label+'-classes');classes.mkdir(exist_ok=True)
   run('compile-'+label,JAVAC+['--release','8','-d',classes]+sorted((base/'src/main/java/smog').glob('*.java'))+[ROOT/'verification/RestartBenchmark.java']+([ROOT/'verification/PrepareRestartScale.java'] if label=='current' else []),45)
  if a.prepare:run('prime-'+str(a.sections),JAVA+['-cp',OUT/'current-classes','smog.RestartBenchmark','prime',a.sections,CACHE])
  if a.prepare_fixture:run('fixture-'+str(a.sections),JAVA+['-cp',OUT/'current-classes','smog.PrepareRestartScale',a.sections,CACHE])
 for mode in filter(None,a.modes.split(',')):
  if mode not in ['A','B','C','D','D-paced']:raise ValueError(mode)
  current_cache=CACHE
  if mode in ['B','D','D-paced']:
   current_cache=OUT/('cache-run-'+mode+'-'+str(a.sections))
   if current_cache.exists():shutil.rmtree(current_cache)
   shutil.copytree(CACHE,current_cache)
  run(mode+'-'+str(a.sections),JAVA+['-cp',OUT/('baseline-classes' if mode=='A' else 'current-classes'),'smog.RestartBenchmark',mode.split('-')[0],a.sections,current_cache]+(['paced'] if mode=='D-paced' else []))
except Exception as e:failure=str(e);print('FAIL',failure,flush=True)
result=dict(status='FAIL' if failure else 'PASS',failure=failure,seconds=round(time.monotonic()-START,3),max_seconds=290,tests=ROWS,scope='Unpaced synthetic core. Not GTNH/MC MSPT or 20 TPS. Snapshot is a cheap semantic fixture; no chunk IO.')
(OUT/('result-'+('prepare' if a.prepare else a.modes.replace(',','_'))+'-'+str(a.sections)+'.json')).write_text(json.dumps(result,indent=2));print(json.dumps(result),flush=True)
raise SystemExit(bool(failure))

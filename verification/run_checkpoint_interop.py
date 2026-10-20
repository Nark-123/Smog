#!/usr/bin/env python3
"""Real iteration-1 readers/writers, v1 export and deliberate old-reader v2 refusal."""
from pathlib import Path
import argparse,json,os,shlex,subprocess,time,hashlib
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--baseline',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();OUT=a.output.resolve();OUT.mkdir(parents=True,exist_ok=True);(ROOT/'build').mkdir(exist_ok=True)
ENV=os.environ.copy();JAVA=shlex.split(ENV.get('SMOG_JAVA','java'));JAVAC=shlex.split(ENV.get('SMOG_JAVAC','javac'));START=time.monotonic();rows=[]
def run(name,cmd,expected=0):
 t=time.monotonic()
 with (OUT/(name+'.log')).open('w') as log:r=subprocess.run(list(map(str,cmd)),cwd=ROOT,env=ENV,stdout=log,stderr=subprocess.STDOUT,timeout=max(.01,min(170,290-(time.monotonic()-START))))
 ok=r.returncode==expected if expected==0 else r.returncode!=0
 row=dict(name=name,exit=r.returncode,expected='zero' if expected==0 else 'refused',passed=ok,seconds=round(time.monotonic()-t,3));rows.append(row);print(json.dumps(row),flush=True)
 if not ok:raise RuntimeError(name+': '+(OUT/(name+'.log')).read_text()[-3000:])
failure=None
try:
 for relative,expected in json.loads((ROOT/'verification/restart-base-sha256.json').read_text()).items():
  if hashlib.sha256((a.baseline.resolve()/relative).read_bytes()).hexdigest()!=expected:raise RuntimeError('Baseline SHA-256 mismatch: '+relative)
 for label,base in [('new',ROOT),('iteration1',a.baseline.resolve())]:
  classes=OUT/(label+'-classes');classes.mkdir(exist_ok=True)
  run('compile-'+label,JAVAC+['--release','8','-d',classes]+sorted((base/'src/main/java/smog').glob('*.java'))+[base/'src/test/java/smog/SparseTests.java',ROOT/'verification/ColdMassCheckpointInterop.java'])
 new=OUT/'new-classes';old=OUT/'iteration1-classes';v1=OUT/'export-v1';legacy=OUT/'original-v1';v2=OUT/'new-v2'
 run('new-v1-write',JAVA+['-ea','-Dsmog.checkpointV1=true','-cp',new,'smog.ColdMassCheckpointInterop','write',v1])
 run('iteration1-reads-export',JAVA+['-ea','-cp',old,'smog.ColdMassCheckpointInterop','read',v1])
 run('iteration1-writes-v1',JAVA+['-ea','-cp',old,'smog.ColdMassCheckpointInterop','write',legacy])
 run('new-reads-original',JAVA+['-ea','-cp',new,'smog.ColdMassCheckpointInterop','read-and-restore',legacy])
 run('new-v2-write',JAVA+['-ea','-cp',new,'smog.ColdMassCheckpointInterop','write',v2])
 run('new-v2-read',JAVA+['-ea','-cp',new,'smog.ColdMassCheckpointInterop','read-and-restore',v2])
 run('old-refuses-v2',JAVA+['-ea','-cp',old,'smog.ColdMassCheckpointInterop','read',v2],expected=1)
except Exception as e:failure=str(e);print(failure,flush=True)
result=dict(status='FAIL' if failure else 'PASS',failure=failure,seconds=round(time.monotonic()-START,3),max_seconds=290,tests=rows);(OUT/'result.json').write_text(json.dumps(result,indent=2));raise SystemExit(bool(failure))

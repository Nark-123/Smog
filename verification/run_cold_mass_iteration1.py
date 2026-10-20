#!/usr/bin/env python3
"""Core/save/lifecycle + three publication variants. Whole important suite <=290 s.

Ordinary child tests stop after completion and have at most 170 s, not a minimum.
This uses Minecraft/FML doubles, not a live Forge/GTNH server.
"""
from pathlib import Path
import argparse, hashlib, json, os, shlex, signal, subprocess, time, tempfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--baseline', type=Path, required=True)
parser.add_argument('--output', type=Path, default=ROOT/'build/cold-mass-iteration1')
args = parser.parse_args()
BASE=args.baseline.resolve();OUT=args.output.resolve();OUT.mkdir(parents=True,exist_ok=True)
(ROOT/'build').mkdir(exist_ok=True);TMP=OUT/'tmp';TMP.mkdir(exist_ok=True)
ENV=os.environ.copy()
# This environment exposes the compiler as a module. Override both commands for another JDK.
JAVA=shlex.split(ENV.get('SMOG_JAVA','java'))+['-Djava.io.tmpdir='+str(TMP)]
JAVAC=shlex.split(ENV.get('SMOG_JAVAC','java -m jdk.compiler/com.sun.tools.javac.Main'))
ENV['SMOG_JAVA']=shlex.join(JAVA);ENV['SMOG_JAVAC']=shlex.join(JAVAC)
START=time.monotonic();DEADLINE=START+290;RESULTS=[];BENCH=[]

def run(name, cmd, limit=70):
    t=time.monotonic()
    with (OUT/(name+'.log')).open('w') as log:
        process=subprocess.Popen(list(map(str,cmd)),cwd=ROOT,env=ENV,
                stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
        try:code=process.wait(timeout=max(.01,min(limit,DEADLINE-time.monotonic())))
        except subprocess.TimeoutExpired:
            os.killpg(process.pid,signal.SIGKILL);process.wait();code=124
    row=dict(name=name,exit=code,seconds=round(time.monotonic()-t,3),max_seconds=limit)
    RESULTS.append(row);print(json.dumps(row),flush=True)
    if code:
        print((OUT/(name+'.log')).read_text()[-4000:],flush=True)
        raise RuntimeError(name+' failed/timeout')

def compile_core(name, base, current):
    target=OUT/(name+'-classes');target.mkdir(exist_ok=True)
    main=base/'src/main/java';files=sorted((main/'smog').glob('*.java'))
    files += [main/'org/smogmod/compat/gt5u/PendingPulse.java',main/'org/smogmod/persistence/WorldStore.java']
    names=['SmogTests','SparseTests','SparseEventTests','CoreIntegrationSmoke','WindowsSaveSmoke',
        'PerformanceFixTests','PerformanceWindowsSave','GeometryMetricsTests','ContactRefreshReuseTests',
        'SafeWindowProfileTests','ColdExactMassTests','CellMassRemapperTests'] if current else ['SparseTests']
    files += [base/('src/test/java/smog/'+n+'.java') for n in names]
    files += [ROOT/'verification/ColdMassBenchmark.java',ROOT/'verification/ColdMassCheckpointInterop.java']
    run('compile-'+name,JAVAC+['--release','8','-g','-d',target]+files,45)
    return target,names

failure=None
try:
    manifest_path=ROOT/'verification/cold-mass-base-sha256.json'
    for path,expected in json.loads(manifest_path.read_text()).items():
        if hashlib.sha256((BASE/path).read_bytes()).hexdigest()!=expected:
            raise RuntimeError('Baseline mismatch: '+path)
    for name in ['SmogGraph.java','Partitioner.java','SmogConfig.java','CellBuild.java']:
        if (ROOT/'src/main/java/smog'/name).read_bytes()!=(BASE/'src/main/java/smog'/name).read_bytes():
            raise RuntimeError('Physics/contacts changed: '+name)
    checkpoint=(ROOT/'src/main/java/smog/Checkpoint.java').read_bytes()
    marker=b'            c.checkpointRestorePending = true;\n'
    if checkpoint.count(marker)!=1 or checkpoint.replace(marker,b'')!=(BASE/'src/main/java/smog/Checkpoint.java').read_bytes():
        raise RuntimeError('Checkpoint changed beyond transient restore marker')
    current,names=compile_core('current',ROOT,True)
    for n in names:run(n,JAVA+['-ea','-Dsmog.preparedLimit=1024','-cp',current,'smog.'+n])
    run('cold-exact-disabled',JAVA+['-ea','-Dsmog.coldExactMass=false','-Dsmog.preparedLimit=1024','-cp',current,'smog.ColdExactMassTests'])
    run('runtime-boundary',['python3','verification/run_geometry_diagnostics_tests.py'],170)
    run('cube-section-revision',['python3','verification/run_cube_section_revision_tests.py'],170)
    baseline,_=compile_core('baseline',BASE,False)
    modern=Path(tempfile.mkdtemp(prefix='checkpoint-from-new-',dir=OUT))
    legacy=Path(tempfile.mkdtemp(prefix='checkpoint-from-baseline-',dir=OUT))
    run('new-checkpoint-write',JAVA+['-ea','-cp',current,'smog.ColdMassCheckpointInterop','write',modern])
    run('baseline-checkpoint-read',JAVA+['-ea','-cp',baseline,'smog.ColdMassCheckpointInterop','read',modern])
    run('baseline-checkpoint-write',JAVA+['-ea','-cp',baseline,'smog.ColdMassCheckpointInterop','write-legacy',legacy])
    run('new-checkpoint-read',JAVA+['-ea','-cp',current,'smog.ColdMassCheckpointInterop','read-and-restore',legacy])
    for label,classes,extra in [('baseline',baseline,[]),('cold-exact',current,[]),('new-fallback',current,['-Dsmog.coldExactMass=false'])]:
        run('benchmark-'+label,JAVA+['-ea','-Xms256m','-Xmx768m','-Dsmog.preparedLimit=1024']+extra+['-cp',classes,'smog.ColdMassBenchmark',label],170)
        BENCH += [json.loads(line) for line in (OUT/('benchmark-'+label+'.log')).read_text().splitlines() if line.startswith('{')]
    for scene in ['open','fragmented']:
        rows=[r for r in BENCH if r['scene']==scene]
        if len({r['topologySha256'] for r in rows})!=1:raise RuntimeError('A/B topology mismatch: '+scene)
        reference=rows[0]['physicsSamples']
        for r in rows:
            for a,b in zip(reference,r['physicsSamples']):
                if abs(a-b)>1e-9*max(1,abs(a),abs(b)):raise RuntimeError('A/B law4 mismatch')
        fast=next(r for r in rows if r['variant']=='cold-exact')
        slow=next(r for r in rows if r['variant']=='new-fallback')
        if fast['coldExactCount']!=1024 or slow['coldFallbackCount']!=1024:raise RuntimeError('Benchmark did not exercise intended path')
except Exception as exc:
    failure=str(exc);print('FAIL',failure,flush=True)
result=dict(status='FAIL' if failure else 'PASS_ITERATION_1',failure=failure,
        seconds=round(time.monotonic()-START,3),max_seconds=290,tests=RESULTS,benchmarks=BENCH,
        full_forge_gtnh='NOT_RUN',runtime='OpenJDK 17; core and new tests compiled --release 8')
(OUT/'result.json').write_text(json.dumps(result,indent=2))
print(json.dumps({k:v for k,v in result.items() if k not in ['tests','benchmarks']}),flush=True)
raise SystemExit(bool(failure))

#!/usr/bin/env python3
"""One wall-clock-bounded regression/benchmark/packaging verification run (<=295 s)."""
import hashlib
import json
import os
from pathlib import Path
import shlex
import signal
import struct
import subprocess
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT/'build/performance-tests'
OUT.mkdir(parents=True, exist_ok=True)
START = time.monotonic()
DEADLINE = START + 290
RESULTS = []
JAVA = shlex.split(os.environ.get('SMOG_JAVA', 'java'))
JAVAC = shlex.split(os.environ.get('SMOG_JAVAC', 'javac'))

def run(name, command, limit=30):
    started = time.monotonic()
    with (OUT/(name+'.log')).open('wb') as log:
        process = subprocess.Popen(list(map(str,command)), cwd=ROOT, env=os.environ,
            stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = process.wait(timeout=max(.01,min(limit,DEADLINE-time.monotonic())))
        except subprocess.TimeoutExpired:
            os.killpg(process.pid,signal.SIGKILL)
            process.wait(timeout=2)
            code = 124
    RESULTS.append(dict(name=name,code=code,seconds=round(time.monotonic()-started,3)))
    if code:
        raise RuntimeError(name+' failed/timeout: '+str(code)+'; see '+str(OUT/(name+'.log')))

def compile_core(name, source):
    target = OUT/name
    target.mkdir(exist_ok=True)
    files = sorted((source/'smog').glob('*.java'))
    files += [source/'org/smogmod/compat/gt5u/PendingPulse.java',source/'org/smogmod/persistence/WorldStore.java']
    files += [ROOT/'verification/SaveRequestBenchmark.java']
    run('compile-'+name,JAVAC+['-source','8','-target','8','-g:none','-d',target]+files,20)
    return target

def main():
    before = compile_core('before',ROOT/'verification/performance-baseline/src/main/java')
    after = compile_core('after',ROOT/'src/main/java')
    test_files = [ROOT/'src/test/java/smog'/name for name in ['SmogTests.java','SparseTests.java',
        'SparseEventTests.java','CoreIntegrationSmoke.java','WindowsSaveSmoke.java',
        'PerformanceFixTests.java','PerformanceWindowsSave.java']]
    run('compile-regressions',JAVAC+['-source','8','-target','8','-g:none','-cp',str(after),'-d',str(after)]+test_files,20)
    run('core-integration',JAVA+['-ea','-cp',after,'smog.CoreIntegrationSmoke'],45)
    run('performance-consistency',JAVA+['-ea','-cp',after,'smog.PerformanceFixTests'],20)
    run('windows-simulation',JAVA+['-ea','-cp',after,'smog.PerformanceWindowsSave'],20)
    run('network-source-replay',['python3','verification/network_replay.py'],45)
    run('worlds-lifecycle-source-replay',['python3','verification/worlds_lifecycle_replay.py'],25)
    # The baseline writes its original queue/checkpoint formats; current code loads the same bytes.
    for name,classes in [('before',before),('after',after)]:
        run('save-request-'+name,JAVA+['-ea','-cp',classes,'smog.SaveRequestBenchmark','save',
            OUT/(name+'-world')],5)
    bundles=[]
    for name in ['before','after']:
        world=OUT/(name+'-world')
        bundles.append(world/(world/'CURRENT').read_text().strip())
    for component in ['core/generation-1.smog','pending.bin','pending.sha256']:
        if (bundles[0]/component).read_bytes() != (bundles[1]/component).read_bytes():
            raise RuntimeError('Baseline/current fixture payload changed: '+component)
    RESULTS.append(dict(name='baseline-current-payload-byte-identical',code=0))
    run('legacy-save-load',JAVA+['-ea','-cp',after,'smog.SaveRequestBenchmark','load',OUT/'before-world'],5)
    run('required-braces',['python3','verification/check_braces.py'],5)
    # Verify unchanged physics/geometry code against the exact attached source, not the older core-baseline.
    for name in ['SmogGraph.java','Partitioner.java','CellBuild.java','SmogConfig.java']:
        original=(ROOT/'verification/performance-baseline/src/main/java/smog'/name).read_bytes()
        current=(ROOT/'src/main/java/smog'/name).read_bytes()
        if original != current:
            raise RuntimeError('Physics/geometry implementation changed: '+name)
    RESULTS.append(dict(name='unchanged-physics-geometry-source',code=0))
    jars=[p for p in (ROOT/'build/libs').glob('*.jar') if not any(x in p.name for x in ['-dev','-sources','-javadoc'])]
    if len(jars)!=1:
        raise RuntimeError('Expected exactly one runtime/reobfuscated JAR; run ./gradlew prepareSmogSmoke first')
    with zipfile.ZipFile(jars[0]) as jar:
        classes=0
        for name in jar.namelist():
            if name.endswith('.class'):
                classes+=1
                if struct.unpack('>H',jar.read(name)[6:8])[0]!=52:
                    raise RuntimeError('Not Java 8: '+name)
                if not (name.startswith('smog/') or name.startswith('org/smogmod/')):
                    raise RuntimeError('Unexpected bundled class: '+name)
        for name in ['mcmod.info','mixins.smog.early.json','mixins.smog.late.json','mixins.smog.refmap.json',
            'org/smogmod/world/WorldRuntime.class','org/smogmod/persistence/WorldStore.class']:
            jar.read(name)
        if not json.loads(jar.read('mixins.smog.refmap.json')).get('mappings'):
            raise RuntimeError('Empty runtime refmap')
        manifest=jar.read('META-INF/MANIFEST.MF').decode()
        if 'org.smogmod.mixin.EarlyLoader' not in manifest:
            raise RuntimeError('Coremod loader absent')
        # The runtime must use SRG names for Minecraft calls, not development MCP names.
        runtime=jar.read('org/smogmod/world/WorldRuntime.class')
        if b'func_82737_E' not in runtime:
            raise RuntimeError('WorldRuntime was not reobfuscated')
    RESULTS.append(dict(name='runtime-jar-major52-refmap-srg',code=0,classes=classes,
        jar=jars[0].name,sha256=hashlib.sha256(jars[0].read_bytes()).hexdigest()))
    classpath=ROOT/'build/smog-test-classpath.txt'
    if not classpath.exists():
        raise RuntimeError('Missing real dependency classpath: run prepareSmogSmoke')
    real_classpath=classpath.read_text().strip()
    run('gt5u-real-target-contract',JAVA+['-ea','-cp',real_classpath,'smog.TargetContracts'],10)
    contracts=OUT/'jar-contracts'
    contracts.mkdir(exist_ok=True)
    run('compile-jar-contract',JAVAC+['-source','8','-target','8','-proc:none','-cp',real_classpath,
        '-d',contracts,ROOT/'verification/RuntimeJarContracts.java'],10)
    run('runtime-instructions',JAVA+['-ea','-cp',str(contracts)+os.pathsep+real_classpath,
        'smog.RuntimeJarContracts',jars[0]],5)
    # A style-only commit must compile identically with debug information disabled.
    originals=ROOT/'verification/logic-before-format'
    selected=set(json.loads((originals/'files.json').read_text()))
    hashes=[]
    for name in ['logic','formatted']:
        target=OUT/('style-'+name)
        target.mkdir(exist_ok=True)
        sources=[]
        for path in sorted((ROOT/'src').glob('*/java/**/*.java')):
            relative=path.relative_to(ROOT).as_posix()
            sources.append(originals/relative if name=='logic' and relative in selected else path)
        run('compile-style-'+name,JAVAC+['-source','8','-target','8','-g:none','-proc:none',
            '-cp',real_classpath,'-d',target]+sources,25)
        hashes.append({p.relative_to(target).as_posix():hashlib.sha256(p.read_bytes()).hexdigest()
            for p in target.rglob('*.class')})
    if hashes[0]!=hashes[1]:
        raise RuntimeError('Formatting-only commit changed compiled bytecode')
    RESULTS.append(dict(name='formatting-bytecode-identical',code=0,classes=len(hashes[0])))
    command=os.environ.get('SMOG_SERVER_SMOKE_COMMAND')
    if command:
        run('prepared-dedicated-server',shlex.split(command),60)
        if 'SMOG_GAME_METHOD_FIXTURE_PASS' not in (OUT/'prepared-dedicated-server.log').read_text(errors='replace'):
            raise RuntimeError('Prepared server did not report fixture pass')
    else:
        RESULTS.append(dict(name='full-gtnh-client-dedicated-integrated',status='NOT_RUN'))

if __name__=='__main__':
    failure=None
    try:
        main()
    except Exception as error:
        failure=str(error)
    result=dict(status='FAIL' if failure else 'PASS_BOUNDED_REGRESSION',
        seconds=round(time.monotonic()-START,3),hard_limit_seconds=295,failure=failure,tests=RESULTS,
        full_gtnh='NOT_RUN' if not os.environ.get('SMOG_SERVER_SMOKE_COMMAND') else 'SEE_SERVER_RESULT')
    (OUT/'result.json').write_text(json.dumps(result,indent=2))
    print(json.dumps(result,ensure_ascii=False,indent=2),flush=True)
    raise SystemExit(1 if failure else 0)

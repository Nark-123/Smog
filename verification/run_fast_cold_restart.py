#!/usr/bin/env python3
"""Self-contained Java 8 core and diagnostics checks for the supplied src.zip tree.

No C++ fixtures, Forge/GTNH dependencies, ASM, or unavailable auxiliary scripts required.
The full 384000-cell checkpoint test is opt-in with --scale.
"""
from pathlib import Path
import argparse
import json
import os
import shlex
import signal
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--output', type=Path, default=ROOT / 'build/fast-cold-restart')
p.add_argument('--scale', action='store_true')
args = p.parse_args()
OUT = args.output.resolve()
OUT.mkdir(parents=True, exist_ok=True)
(ROOT / 'build').mkdir(exist_ok=True)
TMP = OUT / 'tmp'
TMP.mkdir(exist_ok=True)
ENV = os.environ.copy()
JAVA = shlex.split(ENV.get('SMOG_JAVA', 'java')) + ['-Djava.io.tmpdir=' + str(TMP)]
JAVAC = shlex.split(ENV.get('SMOG_JAVAC', 'javac'))
START = time.monotonic()
ROWS = []
CLASSES = OUT / 'classes'
CLASSES.mkdir(exist_ok=True)


def run(name, command, limit=170):
    now = time.monotonic()
    with (OUT / (name + '.log')).open('w') as log:
        proc = subprocess.Popen(list(map(str, command)), cwd=ROOT, env=ENV,
                                stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = proc.wait(timeout=max(.01, min(limit, 290 - (time.monotonic() - START))))
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid, signal.SIGKILL)
            proc.wait()
            code = 124
    row = dict(name=name, exit=code, seconds=round(time.monotonic() - now, 3))
    ROWS.append(row)
    print(json.dumps(row), flush=True)
    if code:
        raise RuntimeError(name + ': ' + (OUT / (name + '.log')).read_text(errors='replace')[-5000:])


names = ['SmogTests', 'SparseTests', 'SparseEventTests', 'CoreIntegrationSmoke', 'WindowsSaveSmoke',
         'PerformanceFixTests', 'PerformanceWindowsSave', 'GeometryMetricsTests',
         'ContactRefreshReuseTests', 'SafeWindowProfileTests', 'ColdExactMassTests',
         'CellMassRemapperTests', 'FastColdRestartTests', 'LargeCheckpointTests']
files = sorted((ROOT / 'src/main/java/smog').glob('*.java')) + [
    ROOT / 'src/main/java/org/smogmod/compat/gt5u/PendingPulse.java',
    ROOT / 'src/main/java/org/smogmod/persistence/WorldStore.java',
] + [ROOT / ('src/test/java/smog/' + name + '.java') for name in names]
fail = None
try:
    run('compile-core', JAVAC + ['--release', '8', '-g', '-d', CLASSES] + files, 60)
    if args.scale:
        run('checkpoint-384000', JAVA + ['-ea', '-Xmx1800m', '-cp', CLASSES,
                                        'smog.LargeCheckpointTests', '384000'], 170)
    else:
        for name in names[1:-1]:
            run(name, JAVA + ['-ea', '-cp', CLASSES, 'smog.' + name])
        run('checkpoint-24000', JAVA + ['-ea', '-Xmx1200m', '-cp', CLASSES,
                                        'smog.LargeCheckpointTests', '24000'])
        run('cold-exact-disabled', JAVA + ['-ea', '-Dsmog.coldExactMass=false',
                                          '-cp', CLASSES, 'smog.ColdExactMassTests'])
        run('checkpoint-v1-writer', JAVA + ['-ea', '-Dsmog.checkpointV1=true',
                                           '-cp', CLASSES, 'smog.SparseTests'])
        run('compile-performance-csv', JAVAC + ['--release', '8', '-d', CLASSES,
             ROOT / 'src/main/java/org/smogmod/world/PerformanceFileLog.java',
             ROOT / 'verification/fixtures/PerformanceCsvSmoke.java'], 30)
        run('performance-csv-v2', JAVA + ['-ea', '-cp', CLASSES,
                                          'org.smogmod.world.PerformanceCsvSmoke'])
except Exception as exception:
    fail = str(exception)
    print('FAIL', fail, flush=True)
result = dict(status='FAIL' if fail else 'PASS', failure=fail,
              seconds=round(time.monotonic() - START, 3), tests=ROWS,
              java_target=8, forge_gtnh='NOT_RUN',
              excluded='SmogTests compiled but not run (needs C++ fixtures); TargetContracts needs ASM')
(OUT / 'result.json').write_text(json.dumps(result, indent=2))
print(json.dumps({k: v for k, v in result.items() if k != 'tests'}), flush=True)
raise SystemExit(bool(fail))

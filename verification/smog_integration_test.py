#!/usr/bin/env python3
"""Hard wall-clock boundary includes Gradle launch and shutdown; process-tree kill on timeout."""
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import time

root = Path(__file__).resolve().parents[1]
out = root/'build/smog-smoke'
out.mkdir(parents=True, exist_ok=True)
start = time.monotonic()
command = ['python3', 'verification/run_smoke.py']
with (out/'outer.log').open('wb') as log:
    process = subprocess.Popen(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        code = process.wait(timeout=296)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=2)
        code = 124
result = dict(code=code, seconds=round(time.monotonic()-start, 3), hard_limit_seconds=300)
(out/'outer-result.json').write_text(json.dumps(result, indent=2))
print(json.dumps(result))
raise SystemExit(code)

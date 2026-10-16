#!/usr/bin/env python3
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
work = root/os.environ.get('SMOG_SMOKE_WORKDIR', 'run/smog-smoke')
work.mkdir(parents=True, exist_ok=True)
if os.environ.get('SMOG_MCP_CONF'):
    # Old CoFH dev transformers search ../conf rather than the RFG cache.
    source = Path(os.environ['SMOG_MCP_CONF'])
    target = work.parent/'conf'
    target.mkdir(parents=True, exist_ok=True)
    for name in ['packaged.srg', 'joined.srg', 'fields.csv', 'methods.csv']:
        if (source/name).is_file():
            shutil.copyfile(source/name, target/name)
(work/'eula.txt').write_text('eula=true\n')
(work/'server.properties').write_text('''level-name=SMOG_INTEGRATION_SMOKE
level-type=FLAT
generate-structures=false
online-mode=false
server-ip=127.0.0.1
server-port=25585
view-distance=4
allow-nether=false
spawn-protection=0
max-tick-time=60000
motd=SMOG isolated method smoke
''')
command = shlex.split(os.environ.get('SMOG_PREPARED_GRADLE', './gradlew')) + ['--offline', '--no-configuration-cache', '-PsmogGameSmoke=true', '-PrunServerWorkingDirectory='+str(work), 'runServer']
if os.environ.get('SMOG_NESTED_GRADLE'):
    command += ['--project-cache-dir', 'run/smog-smoke/gradle-cache']
process = subprocess.Popen(command, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
done = passed = shutdown = False
for line in iter(process.stdout.readline, b''):
    sys.stdout.buffer.write(line)
    sys.stdout.buffer.flush()
    done |= b'Done (' in line
    passed |= b'SMOG_GAME_METHOD_FIXTURE_PASS' in line
    shutdown |= b'SMOG_GAME_SHUTDOWN_READY' in line
code = process.wait()
raise SystemExit(0 if code == 0 and done and passed and shutdown else 1)

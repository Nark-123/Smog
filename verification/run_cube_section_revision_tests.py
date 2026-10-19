#!/usr/bin/env python3
"""Java 8 provider/version regression with the production SMOG classes and Minecraft stubs.

No claim of an in-game Forge/GTNH build is made by these tests.
"""
import json
import os
from pathlib import Path
import shlex
import subprocess

from run_geometry_diagnostics_tests import STUBS

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build' / 'cube-section-revision-smoke'


def main():
    stub = OUT / 'stubs'
    classes = OUT / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    for name, code in STUBS.items():
        target = stub / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(code)
    production = ROOT / 'src/main/java'
    sources = list((production / 'smog').glob('*.java'))
    sources += [production / 'org/smogmod/world/ForgeWorldSnapshotProvider.java']
    sources += list((stub / 'net/minecraft/block').glob('*.java'))
    sources += [stub / 'net/minecraft/init/Blocks.java',
                stub / 'net/minecraft/world/chunk/Chunk.java']
    sources += [ROOT / 'verification/fixtures/CubeSectionRevisionSmoke.java']
    javac = shlex.split(os.environ.get('SMOG_JAVAC', 'javac'))
    java = shlex.split(os.environ.get('SMOG_JAVA', 'java'))
    subprocess.run(javac + ['-source', '8', '-target', '8', '-d', str(classes)]
                   + [str(path) for path in sources], cwd=ROOT, check=True, timeout=60)
    subprocess.run(java + ['-ea', '-cp', str(classes),
                           'org.smogmod.world.CubeSectionRevisionSmoke'],
                   cwd=ROOT, check=True, timeout=30)
    print(json.dumps({'status': 'PASS_SECTION_VERSIONS_WITH_MC_DOUBLES',
                      'scope': 'real core and provider; stub Minecraft (not Forge GTNH runtime)'},
                     ensure_ascii=False))


if __name__ == '__main__':
    main()

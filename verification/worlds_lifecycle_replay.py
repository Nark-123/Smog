#!/usr/bin/env python3
"""Run the production Worlds manager with a boundary double for Minecraft and WorldRuntime.close."""
import json
import os
from pathlib import Path
import shlex
import subprocess

ROOT=Path(__file__).resolve().parents[1]
FILES={
'net/minecraft/world/World.java':'''package net.minecraft.world; public class World {public boolean isRemote;}''',
'net/minecraft/world/WorldServer.java':'''package net.minecraft.world; public final class WorldServer extends World {}''',
'org/smogmod/world/WorldRuntime.java':'''package org.smogmod.world;
public final class WorldRuntime {
 public int closes;
 public boolean fail;
 public WorldRuntime(net.minecraft.world.WorldServer world){}
 public void close(){closes++;if(fail){throw new IllegalStateException("injected final flush failure");}}
}''',
'WorldsReplay.java':'''import org.smogmod.world.*;
import net.minecraft.world.WorldServer;
public final class WorldsReplay {
 static void check(boolean b,String m){if(!b){throw new AssertionError(m);}}
 public static void main(String[] args)throws Exception {
  WorldServer a=new WorldServer(),b=new WorldServer();
  Worlds.start();WorldRuntime first=Worlds.get(a),second=Worlds.get(b);
  check(Worlds.get(a)==first,"Duplicate runtime");
  Worlds.unload(a);Worlds.stop();Worlds.unload(a);Worlds.stop();
  check(first.closes==1&&second.closes==1,"Unload + stop closed a runtime twice");
  Worlds.start();WorldRuntime restarted=Worlds.get(a);
  check(restarted!=first,"Integrated restart reused closed state");Worlds.stop();
  Worlds.start();WorldRuntime c=Worlds.get(a),d=Worlds.get(b);c.fail=true;d.fail=true;
  try{Worlds.stop();throw new AssertionError("Failure discarded");}
  catch(IllegalStateException expected){check(expected.getSuppressed().length==1,"Other dimension failure lost");}
  check(c.closes==1&&d.closes==1,"Stop did not flush every dimension");
  check(Worlds.existing(a)==c&&Worlds.existing(b)==d,"Failed state silently removed");
  try{Worlds.start();throw new AssertionError("Restart accepted unclosed worlds");}
  catch(IllegalStateException expected){}
  c.fail=false;d.fail=false;Worlds.stop();
  check(Worlds.existing(a)==null&&Worlds.existing(b)==null,"Retry left old worlds");
  Worlds.start();final boolean[] rejected={false};
  Thread other=new Thread(()->{try{Worlds.get(a);}catch(IllegalStateException expected){rejected[0]=true;}});
  other.start();other.join(1000);check(!other.isAlive()&&rejected[0],"Wrong-thread creation accepted");
  Worlds.stop();System.out.println("WORLDS_MANAGER_LIFECYCLE_PASS");
 }
}'''
}

def main():
    out=ROOT/'build/performance-tests/worlds-replay'
    sources=out/'src'
    for name,text in FILES.items():
        p=sources/name
        p.parent.mkdir(parents=True,exist_ok=True)
        p.write_text(text)
    classes=out/'classes'
    classes.mkdir(exist_ok=True)
    java=shlex.split(os.environ.get('SMOG_JAVA','java'))
    javac=shlex.split(os.environ.get('SMOG_JAVAC','javac'))
    subprocess.run(javac+['-source','8','-target','8','-g:none','-d',str(classes)]
        +[str(p) for p in sources.rglob('*.java')]
        +[str(ROOT/'src/main/java/org/smogmod/world/Worlds.java')],check=True,timeout=15)
    subprocess.run(java+['-ea','-cp',str(classes),'WorldsReplay'],check=True,timeout=5)
    (out/'result.json').write_text(json.dumps(dict(status='PASS_SOURCE_REPLAY',
        coverage='unload, stop, idempotence, integrated restart, multi-world failure, retry, owner thread')))

if __name__=='__main__':
    main()

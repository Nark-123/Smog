#!/usr/bin/env python3
"""Small Java-8-compatible boundary smoke for the real SMOG perf command/provider/runtime.

The Minecraft, FML logging and World registry classes below are *doubles*. No claim of a full
Forge / GTNH runtime build should be inferred from a PASS here.
"""
import json
import os
from pathlib import Path
import shlex
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build' / 'geometry-diagnostics-smoke'

STUBS = {
    'net/minecraft/block/Block.java': 'package net.minecraft.block; public class Block {}',
    'net/minecraft/block/BlockDoor.java': 'package net.minecraft.block; public class BlockDoor extends Block {}',
    'net/minecraft/block/BlockTrapDoor.java': 'package net.minecraft.block; public class BlockTrapDoor extends Block {}',
    'net/minecraft/block/BlockFenceGate.java': 'package net.minecraft.block; public class BlockFenceGate extends Block {}',
    'net/minecraft/init/Blocks.java': '''package net.minecraft.init;
public final class Blocks {
 public static final net.minecraft.block.Block air=new net.minecraft.block.Block();
 public static final net.minecraft.block.Block fire=new net.minecraft.block.Block();
 public static final net.minecraft.block.Block torch=new net.minecraft.block.Block();
 public static final net.minecraft.block.Block redstone_wire=new net.minecraft.block.Block();
}''',
    'net/minecraft/world/chunk/Chunk.java': '''package net.minecraft.world.chunk;
public class Chunk {
 public final int xPosition,zPosition;
 public Chunk(int x,int z){xPosition=x;zPosition=z;}
 public net.minecraft.block.Block getBlock(int x,int y,int z){return net.minecraft.init.Blocks.air;}
 public int getBlockMetadata(int x,int y,int z){return 0;}
}''',
    'net/minecraft/world/gen/ChunkProviderServer.java': '''package net.minecraft.world.gen;
public final class ChunkProviderServer {
 public final java.util.List<Object> loadedChunks=new java.util.ArrayList<>();
}''',
    'net/minecraft/world/WorldServer.java': '''package net.minecraft.world;
public final class WorldServer {
 public static final class Provider {public int dimensionId;}
 public static final class SaveHandler {
  private final java.io.File dir;
  public SaveHandler(java.io.File dir){this.dir=dir;}
  public java.io.File getWorldDirectory(){return dir;}
 }
 public final Provider provider=new Provider();
 public final net.minecraft.world.gen.ChunkProviderServer chunks=new net.minecraft.world.gen.ChunkProviderServer();
 public long time;
 private final SaveHandler save;
 public WorldServer(int dim,java.io.File dir){provider.dimensionId=dim;save=new SaveHandler(dir);}
 public long getTotalWorldTime(){return time;}
 public SaveHandler getSaveHandler(){return save;}
 public Object getChunkProvider(){return chunks;}
}''',
    'net/minecraft/command/CommandBase.java': '''package net.minecraft.command;
public abstract class CommandBase {
 public abstract String getCommandName();
 public abstract String getCommandUsage(ICommandSender sender);
 public int getRequiredPermissionLevel(){return 0;}
 public abstract void processCommand(ICommandSender sender,String[] args);
 protected int parseInt(ICommandSender sender,String value){return Integer.parseInt(value);}
 protected double parseDouble(ICommandSender sender,String value){return Double.parseDouble(value);}
}''',
    'net/minecraft/command/ICommandSender.java': '''package net.minecraft.command;
public interface ICommandSender {
 net.minecraft.world.WorldServer getEntityWorld();
 void addChatMessage(net.minecraft.util.ChatComponentText message);
}''',
    'net/minecraft/command/WrongUsageException.java': '''package net.minecraft.command;
public final class WrongUsageException extends RuntimeException {
 public WrongUsageException(String message){super(message);}
}''',
    'net/minecraft/util/ChatComponentText.java': '''package net.minecraft.util;
public final class ChatComponentText {
 public final String text;
 public ChatComponentText(String text){this.text=text;}
}''',
    'cpw/mods/fml/common/FMLLog.java': '''package cpw.mods.fml.common;
public final class FMLLog {
 public static boolean infoEnabled=false;
 public static final java.util.List<String> lines=new java.util.ArrayList<>();
 public static void info(String message,Object... data){
  if(infoEnabled){lines.add(String.format(java.util.Locale.ROOT,message,data));}
 }
 public static void warning(String message,Object... data){
  lines.add(String.format(java.util.Locale.ROOT,message,data));
 }
}''',
    'org/smogmod/world/Worlds.java': '''package org.smogmod.world;
public final class Worlds {
 public static WorldRuntime current;
 public static WorldRuntime get(net.minecraft.world.WorldServer world){return current;}
}''',
    'org/smogmod/compat/gt5u/SmogGt5uBridge.java': '''package org.smogmod.compat.gt5u;
public final class SmogGt5uBridge {
 public static void emitPulse(net.minecraft.world.WorldServer w,int x,int y,int z,long value){}
 public static void removeAt(net.minecraft.world.WorldServer w,int x,int y,int z,long value){}
}''',
}


def main():
    began = time.monotonic()
    stub = OUT / 'stubs'
    classes = OUT / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    for name, code in STUBS.items():
        target = stub / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(code)
    production = ROOT / 'src' / 'main' / 'java'
    sources = list((production / 'smog').glob('*.java'))
    sources += [production / path for path in [
        'org/smogmod/compat/gt5u/PendingPulse.java',
        'org/smogmod/persistence/WorldStore.java',
        'org/smogmod/world/ForgeWorldSnapshotProvider.java',
        'org/smogmod/world/WorldRuntime.java',
        'org/smogmod/world/PerformanceFileLog.java',
        'org/smogmod/commands/SmogCommand.java',
    ]]
    sources += list(stub.rglob('*.java'))
    sources += [ROOT / 'verification/fixtures/PerformanceCsvSmoke.java',
                ROOT / 'src/test/java/smog/SafeWindowProfileTests.java',
                ROOT / 'verification/fixtures/PerformanceBoundarySmoke.java',
                ROOT / 'src/test/java/smog/SparseTests.java',
                ROOT / 'src/test/java/smog/GeometryMetricsTests.java',
                ROOT / 'src/test/java/smog/ContactRefreshReuseTests.java']
    javac = shlex.split(os.environ.get('SMOG_JAVAC', 'javac'))
    java = shlex.split(os.environ.get('SMOG_JAVA', 'java'))
    subprocess.run(javac + ['-source', '8', '-target', '8', '-g:none', '-d', str(classes)]
                   + [str(path) for path in sources], cwd=ROOT, check=True, timeout=45)
    subprocess.run(java + ['-ea', '-Dsmog.perfLogTicks=20', '-cp', str(classes),
                           'org.smogmod.diagnostics.PerformanceBoundarySmoke'],
                   cwd=ROOT, check=True, timeout=25)
    subprocess.run(java + ['-ea', '-Dsmog.preparedLimit=1024', '-cp', str(classes),
                           'smog.GeometryMetricsTests'],
                   cwd=ROOT, check=True, timeout=25)
    subprocess.run(java + ['-ea', '-Dsmog.preparedLimit=1024', '-cp', str(classes),
                           'smog.ContactRefreshReuseTests'],
                   cwd=ROOT, check=True, timeout=25)
    subprocess.run(java + ['-ea', '-cp', str(classes), 'smog.SafeWindowProfileTests'],
                   cwd=ROOT, check=True, timeout=25)
    subprocess.run(java + ['-ea', '-cp', str(classes),
                           'org.smogmod.world.PerformanceCsvSmoke'],
                   cwd=ROOT, check=True, timeout=25)
    result = {'status': 'PASS_BOUNDARY_CONTACTS_SAFE_CSV', 'seconds': round(time.monotonic()-began, 3),
              'scope': 'real SMOG core, provider, world runtime, command, async file; stub Minecraft and FMLLog'}
    (OUT / 'result.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result))


if __name__ == '__main__':
    main()

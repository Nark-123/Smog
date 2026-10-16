#!/usr/bin/env python3
"""Replay the exact baseline/current SmogNetwork source with small Minecraft/FML boundary doubles.
This tests event/control flow, not a real client or the network serialization implementation.
"""
import json
import os
from pathlib import Path
import shlex
import subprocess

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
'net/minecraft/world/ChunkCoordIntPair.java': '''package net.minecraft.world;
public final class ChunkCoordIntPair {
 public final int chunkXPos,chunkZPos;
 public ChunkCoordIntPair(int x,int z){chunkXPos=x;chunkZPos=z;}
 public int hashCode(){return chunkXPos*31+chunkZPos;}
 public boolean equals(Object o){if(!(o instanceof ChunkCoordIntPair)){return false;} ChunkCoordIntPair c=(ChunkCoordIntPair)o; return c.chunkXPos==chunkXPos&&c.chunkZPos==chunkZPos;}
}''',
'net/minecraft/world/WorldServer.java': '''package net.minecraft.world;
import java.util.*;
public final class WorldServer {
 public long time;
 public final int dimension;
 public final List<Object> playerEntities=new ArrayList<>();
 public final smog.DimensionState state=new smog.DimensionState();
 public WorldServer(int d){dimension=d;}
 public long getTotalWorldTime(){return time;}
}''',
'net/minecraft/entity/player/EntityPlayerMP.java': '''package net.minecraft.entity.player;
public final class EntityPlayerMP {
 public final int dimension;
 public final net.minecraft.world.WorldServer worldObj;
 public final java.util.UUID id;
 public double posX=-1.2,posY=71.25,posZ=-17.8;
 public EntityPlayerMP(net.minecraft.world.WorldServer w,int n){worldObj=w;dimension=w.dimension;id=new java.util.UUID(0,n);w.playerEntities.add(this);}
 public java.util.UUID getUniqueID(){return id;}
 public float getEyeHeight(){return 1.62f;}
}''',
'net/minecraftforge/event/world/ChunkWatchEvent.java': '''package net.minecraftforge.event.world;
public class ChunkWatchEvent {
 public final net.minecraft.entity.player.EntityPlayerMP player;
 public final net.minecraft.world.ChunkCoordIntPair chunk;
 public ChunkWatchEvent(net.minecraft.entity.player.EntityPlayerMP p,net.minecraft.world.ChunkCoordIntPair c){player=p;chunk=c;}
 public static final class Watch extends ChunkWatchEvent {public Watch(net.minecraft.entity.player.EntityPlayerMP p,net.minecraft.world.ChunkCoordIntPair c){super(p,c);}}
 public static final class UnWatch extends ChunkWatchEvent {public UnWatch(net.minecraft.entity.player.EntityPlayerMP p,net.minecraft.world.ChunkCoordIntPair c){super(p,c);}}
}''',
'cpw/mods/fml/common/eventhandler/SubscribeEvent.java': '''package cpw.mods.fml.common.eventhandler;
public @interface SubscribeEvent {}''',
'cpw/mods/fml/relauncher/Side.java': '''package cpw.mods.fml.relauncher; public enum Side {CLIENT,SERVER}''',
'cpw/mods/fml/common/gameevent/PlayerEvent.java': '''package cpw.mods.fml.common.gameevent;
public class PlayerEvent {
 public final net.minecraft.entity.player.EntityPlayerMP player;
 public PlayerEvent(net.minecraft.entity.player.EntityPlayerMP p){player=p;}
 public static final class PlayerLoggedOutEvent extends PlayerEvent {public PlayerLoggedOutEvent(net.minecraft.entity.player.EntityPlayerMP p){super(p);}}
 public static final class PlayerChangedDimensionEvent extends PlayerEvent {public PlayerChangedDimensionEvent(net.minecraft.entity.player.EntityPlayerMP p){super(p);}}
}''',
'cpw/mods/fml/common/network/NetworkRegistry.java': '''package cpw.mods.fml.common.network;
public final class NetworkRegistry {
 public static final NetworkRegistry INSTANCE=new NetworkRegistry();
 public cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper newSimpleChannel(String n){return new cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper();}
}''',
'cpw/mods/fml/common/network/simpleimpl/SimpleNetworkWrapper.java': '''package cpw.mods.fml.common.network.simpleimpl;
public final class SimpleNetworkWrapper {
 public static final java.util.List<String> sent=new java.util.ArrayList<>();
 public void registerMessage(Class<?> h,Class<?> m,int n,cpw.mods.fml.relauncher.Side s){}
 public void sendTo(org.smogmod.network.PollutionMessage m,net.minecraft.entity.player.EntityPlayerMP p){sent.add(p.id+":"+m.text);}
}''',
'org/smogmod/network/PollutionMessage.java': '''package org.smogmod.network;
public final class PollutionMessage {
 public final String text;
 public PollutionMessage(int d,int x,int z,int a,boolean l){text=d+","+x+","+z+","+a+","+l;}
 public static final class Handler {}
}''',
'org/smogmod/world/WorldRuntime.java': '''package org.smogmod.world;
public final class WorldRuntime {
 public final net.minecraft.world.WorldServer world;
 public final smog.DimensionState state;
 public long networkBatches,networkUniqueChunks,networkFullScans,networkCubeVisits,networkPackets;
 public WorldRuntime(net.minecraft.world.WorldServer w){world=w;state=w.state;}
}''',
'smog/DimensionState.java': '''package smog;
public final class DimensionState {public final java.util.Map<Long,Double> values=new java.util.HashMap<>();}''',
'smog/SpatialQueries.java': '''package smog;
public final class SpatialQueries {
 public static int scans;
 public static long chunkKey(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
 public static final class ChunkMassBatch {
  public final java.util.Map<Long,Double> masses=new java.util.HashMap<>();
  public long cubeVisits;
 }
 public static ChunkMassBatch chunkMasses(DimensionState s,java.util.Set<Long> keys){
  ChunkMassBatch b=new ChunkMassBatch();
  if(!keys.isEmpty()){scans++;b.cubeVisits=3125;}
  for(long k:keys){b.masses.put(k,s.values.getOrDefault(k,0.0));}
  return b;
 }
}''',
'org/smogmod/compat/gt5u/SmogGt5uBridge.java': '''package org.smogmod.compat.gt5u;
public final class SmogGt5uBridge {
 public static int reads,locals;
 public static int clamp(double a){return (int)Math.max(0,Math.min(Integer.MAX_VALUE,a));}
 public static int read(net.minecraft.world.WorldServer w,int x,int z){reads++;return clamp(w.state.values.getOrDefault(smog.SpatialQueries.chunkKey(x,z),0.0));}
 public static int local(net.minecraft.world.WorldServer w,int x,int y,int z){
  locals++;
  if(x!=-2||y!=72||z!=-18){throw new AssertionError("XYZ floor/eye coordinates changed");}
  return w.dimension+512;
 }
}''',
'NetworkReplay.java': '''import net.minecraft.world.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.event.world.ChunkWatchEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import org.smogmod.network.SmogNetwork;
import org.smogmod.world.WorldRuntime;
public final class NetworkReplay {
 static void value(WorldServer w,int x,int z,double a){w.state.values.put(smog.SpatialQueries.chunkKey(x,z),a);}
 static void tick(SmogNetwork n,WorldRuntime a,WorldRuntime b,int t){a.world.time=t;b.world.time=t;n.tick(a);n.tick(b);}
 public static void main(String[] args){
  WorldRuntime a=new WorldRuntime(new WorldServer(0)),b=new WorldRuntime(new WorldServer(7));
  EntityPlayerMP p=new EntityPlayerMP(a.world,1),q=new EntityPlayerMP(a.world,2),r=new EntityPlayerMP(b.world,3);
  SmogNetwork n=SmogNetwork.INSTANCE;n.init();
  ChunkCoordIntPair c=new ChunkCoordIntPair(-1,-1),d=new ChunkCoordIntPair(1,2),empty=new ChunkCoordIntPair(-90,90);
  value(a.world,-1,-1,900.8);value(a.world,1,2,3);value(b.world,-1,-1,42);
  n.watch(new ChunkWatchEvent.Watch(p,c));n.watch(new ChunkWatchEvent.Watch(q,c));
  n.watch(new ChunkWatchEvent.Watch(p,d));n.watch(new ChunkWatchEvent.Watch(p,empty));n.watch(new ChunkWatchEvent.Watch(r,c));
  int initial=SimpleNetworkWrapper.sent.size();tick(n,a,b,1);
  if(SimpleNetworkWrapper.sent.size()!=initial){throw new AssertionError("Cadence changed");}
  tick(n,a,b,20);
  if(Boolean.getBoolean("expectBatch")&&(a.networkUniqueChunks!=3||a.networkFullScans!=1||b.networkUniqueChunks!=1)){
   throw new AssertionError("Watch union crossed players/dimensions or repeated scans");
  }
  value(a.world,-1,-1,0);value(a.world,1,2,8.5);tick(n,a,b,40);
  n.unwatch(new ChunkWatchEvent.UnWatch(q,c));n.logout(new PlayerEvent.PlayerLoggedOutEvent(p));
  n.dimension(new PlayerEvent.PlayerChangedDimensionEvent(r));tick(n,a,b,60);
  n.watch(new ChunkWatchEvent.Watch(p,d));n.clear();tick(n,a,b,80);
  for(String message:SimpleNetworkWrapper.sent){System.out.println("PACKET "+message);}
  System.out.println("COUNTERS reads="+org.smogmod.compat.gt5u.SmogGt5uBridge.reads+" scans="+smog.SpatialQueries.scans+" locals="+org.smogmod.compat.gt5u.SmogGt5uBridge.locals);
 }
}'''
}

def main():
    out = ROOT/'build/performance-tests/network-replay'
    out.mkdir(parents=True, exist_ok=True)
    src = out/'stubs'
    for name, text in STUBS.items():
        p = src/name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
    java = shlex.split(os.environ.get('SMOG_JAVA', 'java'))
    javac = shlex.split(os.environ.get('SMOG_JAVAC', 'javac'))
    outputs = []
    metrics = []
    for name, network in [
        ('before', ROOT/'verification/performance-baseline/src/main/java/org/smogmod/network/SmogNetwork.java'),
        ('after', ROOT/'src/main/java/org/smogmod/network/SmogNetwork.java')
    ]:
        classes = out/name
        classes.mkdir(exist_ok=True)
        subprocess.run(javac + ['-source','8','-target','8','-g:none','-d',str(classes)]
            + [str(p) for p in sorted(src.rglob('*.java'))] + [str(network)], check=True, timeout=20)
        result = subprocess.run(java + ['-DexpectBatch='+str(name=='after').lower(),'-cp',str(classes),'NetworkReplay'],
            text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=True,timeout=5).stdout
        (out/(name+'.log')).write_text(result)
        outputs.append([line for line in result.splitlines() if line.startswith('PACKET ')])
        metrics.append(result.splitlines()[-1])
    if outputs[0] != outputs[1]:
        raise AssertionError('Before/after packet trace differs')
    report = dict(packets=len(outputs[0]),before=metrics[0],after=metrics[1],
        coverage='watch, overlap, dimensions, unchanged, changed, zero, unwatch, logout, dimension switch, clear, 20-tick cadence, local XYZ')
    (out/'result.json').write_text(json.dumps(report,indent=2))
    print('NETWORK_SOURCE_REPLAY_PASS '+json.dumps(report))

if __name__ == '__main__':
    main()

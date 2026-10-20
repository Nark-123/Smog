package smog;

import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;

/** Offline homogeneous-world fixture, not runtime cache warming or a Minecraft benchmark. */
public final class PrepareRestartScale {
    public static void main(String[] args)throws Exception{
        int sections=Integer.parseInt(args[0]);Path directory=Paths.get(args[1]);Files.createDirectories(directory);
        long start=System.nanoTime(),deadline=start+160000000000L;RestartBenchmark.World world=new RestartBenchmark.World();
        Map<CellKey,GeometryPack> templates=new HashMap<>();int samples=0,records=0;
        try(DimensionState state=new DimensionState(0,SmogConfig.throat8(),world)){
            // All 3x3x3 boundary classes, all eight slots: real, validated ordinary publications.
            for(int z:new int[]{0,1,59})for(int y:new int[]{0,1,15})for(int x:new int[]{0,1,49})for(int slot=0;slot<8;slot++){
                CellKey key=new CellKey(0,x*2+(slot&1),y*2+((slot>>1)&1),z*2+((slot>>2)&1));
                CellBuild b=new CellBuild(state,state.cell(key,true));while(!b.ready)b.advance();if(!b.validWorld()||!b.validContacts())throw new AssertionError("template validation");
                state.publishGeometry(b.cell,b.geometry,b.local.seeds,b.boundary,b.self(),b.edges,true);templates.put(key,b.pack());
            }
            GeometryCache cache=new GeometryCache(directory,0,state.config,32L*1048576);
            Method write=GeometryCache.class.getDeclaredMethod("writeRegion",CubeKey.class,Map.class);write.setAccessible(true);
            try{
                for(int gz=0;gz<15;gz++)for(int gy=0;gy<(sections+3)/4;gy++)for(int gx=0;gx<13;gx++){
                    Map<CellKey,GeometryPack> group=new LinkedHashMap<>();
                    for(int z=gz*4;z<Math.min(60,gz*4+4);z++)for(int y=gy*4;y<Math.min(sections,gy*4+4);y++)for(int x=gx*4;x<Math.min(50,gx*4+4);x++)for(int slot=0;slot<8;slot++){
                        if(System.nanoTime()>deadline)throw new AssertionError("fixture time limit");
                        CellKey key=new CellKey(0,x*2+(slot&1),y*2+((slot>>1)&1),z*2+((slot>>2)&1));
                        int tx=x==0?0:x==49?49:1,ty=y==0?0:y==15?15:1,tz=z==0?0:z==59?59:1;
                        GeometryPack t=templates.get(new CellKey(0,tx*2+(slot&1),ty*2+((slot>>1)&1),tz*2+((slot>>2)&1)));
                        DimensionState.Geometry g=new DimensionState.Geometry();g.labels=t.labels;g.volumes=t.volumes;g.distance=t.distance;g.dependencies=new CubeKey[t.dependencies.length];ImmutableCubeSnapshot[] snapshots=new ImmutableCubeSnapshot[g.dependencies.length];
                        for(int i=0;i<g.dependencies.length;i++){CubeKey dep=t.dependencies[i];g.dependencies[i]=new CubeKey(0,dep.x+x-tx,dep.y+y-ty,dep.z+z-tz);snapshots[i]=world.isReady(g.dependencies[i])?world.snapshotIfReady(g.dependencies[i]):null;}
                        GeometryPack p=new GeometryPack(key,g,t.seeds,t.boundary,t.contacts,snapshots);group.put(key,p);records++;
                        if(records%997==0){
                            CellBuild b=new CellBuild(state,state.cell(key,true));while(!b.ready)b.advance();GeometryPack actual=b.pack();
                            if(!b.validWorld()||!Arrays.equals(p.labels,actual.labels)||!Arrays.equals(p.distance,actual.distance)||!Arrays.equals(p.volumes,actual.volumes)||!Arrays.equals(p.seeds,actual.seeds)||!Arrays.equals(p.boundary,actual.boundary)||!Arrays.equals(p.contacts,actual.contacts)||!Arrays.deepEquals(p.fingerprints,actual.fingerprints))throw new AssertionError("translation oracle "+key);
                            samples++;
                        }
                    }
                    write.invoke(cache,new CubeKey(0,gx,gy,gz),group);
                }
            }finally{cache.close();}
        }
        System.out.println("{\"fixture_cells\":"+records+",\"ordinary_translation_checks\":"+samples+",\"wall_ms\":"+(System.nanoTime()-start)/1e6+",\"mass_in_cache\":false}");
    }
}

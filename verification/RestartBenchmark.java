package smog;

import java.lang.management.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Unpaced synthetic owner-thread profile. Compiles against both actual iteration 1 and new core. */
public final class RestartBenchmark {
    static final class World implements WorldSnapshotProvider {
        final LinkedHashMap<CubeKey,ImmutableCubeSnapshot> lru=new LinkedHashMap<>(16,.75f,true);
        final byte[] air=new byte[4096];long reads,builds,nanos;
        World(){Arrays.fill(air,(byte)1);}
        public boolean isReady(CubeKey k){return k.dimension==0&&k.x>=0&&k.x<50&&k.z>=0&&k.z<60&&k.y>=0&&k.y<16;}
        public boolean matchesSnapshot(CubeKey k,ImmutableCubeSnapshot s){return isReady(k)?s!=null&&s.key.equals(k)&&s.epoch==1&&s.revision==1:s==null;}
        public ImmutableCubeSnapshot snapshotIfReady(CubeKey k){
            if(!isReady(k))throw new AssertionError("forced unavailable chunk");reads++;ImmutableCubeSnapshot s=lru.get(k);
            if(s==null){long start=System.nanoTime();s=new ImmutableCubeSnapshot(k,1,1,air);lru.put(k,s);builds++;while(lru.size()>2048)lru.remove(lru.keySet().iterator().next());nanos+=System.nanoTime()-start;}return s;
        }
    }
    static Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    static Object call(Object object,String name,Class<?>[] types,Object...values)throws Exception{Method m=object.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(object,values);}
    static long gc(boolean millis){long value=0;for(GarbageCollectorMXBean bean:ManagementFactory.getGarbageCollectorMXBeans())value+=Math.max(0,millis?bean.getCollectionTime():bean.getCollectionCount());return value;}
    static long heap(){Runtime r=Runtime.getRuntime();return r.totalMemory()-r.freeMemory();}
    static CellKey key(int column,int section,int slot){return new CellKey(0,(column%50)*2+(slot&1),section*2+((slot>>1)&1),(column/50)*2+((slot>>2)&1));}
    static Checkpoint.Data data(int sections){
        Checkpoint.Data d=new Checkpoint.Data();d.config=SmogConfig.throat8();d.parts=20;int[] labels=new int[512];
        for(int column=0;column<3000;column++)for(int section=0;section<sections;section++)for(int slot=0;slot<8;slot++){
            Checkpoint.CellData cell=new Checkpoint.CellData();cell.key=key(column,section,slot);cell.mass=new DimensionState.MassRecord(labels.clone(),new int[]{512},new double[]{10});d.cells.add(cell);d.injected+=10;
        }return d;
    }
    public static void main(String[] args)throws Exception{
        String mode=args[0];int sections=Integer.parseInt(args[1]);Path cache=Paths.get(args[2]);
        boolean paced=args.length>3&&args[3].equals("paced");
        boolean prime=mode.equals("prime");System.setProperty("smog.geometryCacheEnabled",""+(prime||mode.equals("B")||mode.equals("D")));
        System.setProperty("smog.fastRestoreEnabled",""+mode.equals("D"));System.setProperty("smog.bootstrapEnabled",""+(mode.equals("C")||mode.equals("D")));
        World world=new World();long start=System.nanoTime(),gc=gc(false),gcms=gc(true);long heapMax=heap();
        if(prime){
            try(DimensionState s=new DimensionState(0,SmogConfig.throat8(),world)){
                call(s,"geometryCache",new Class<?>[]{Path.class},cache);
                for(int column=0;column<3000;column++)for(int section=0;section<sections;section++)for(int slot=0;slot<8;slot++)s.queueAddMass(key(column,section,slot).block(0),10);
                long deadline=start+TimeUnit.SECONDS.toNanos(160);
                while(s.publications<3000L*sections*8&&System.nanoTime()<deadline){s.geometry(20000000);heapMax=Math.max(heapMax,heap());}
                if(s.publications<3000L*sections*8)throw new AssertionError("prime deadline");Object geometryCache=field(s,"geometryCache");
                java.util.concurrent.CompletableFuture<?> future=(java.util.concurrent.CompletableFuture<?>)call(geometryCache,"flush",new Class<?>[0]);future.get(15,TimeUnit.SECONDS);
                Object metrics=field(s,"restartMetrics");call(geometryCache,"syncMetrics",new Class<?>[]{metrics.getClass()},metrics);
                double dropped=(Double)call(metrics,"get",new Class<?>[]{String.class},"cache_write_dropped");if(dropped!=0)throw new AssertionError("cache priming dropped "+dropped);
                System.out.println("{\"mode\":\"prime\",\"cells\":"+s.publications+",\"wall_ms\":"+(System.nanoTime()-start)/1e6+",\"geometry_ms\":"+s.geometryMetrics.geometryNanos/1e6+",\"partition_ms\":"+s.geometryMetrics.phaseNanos[GeometryMetrics.PARTITION]/1e6+",\"heap_sample_max\":"+heapMax+"}");
                System.out.println(call(metrics,"logLine",new Class<?>[0]));
            }return;
        }
        Checkpoint.Data d=data(sections);long fixture=System.nanoTime()-start;long checkpointStart=System.nanoTime();
        try(DimensionState s=Checkpoint.restore(d,world)){
            try{call(s,"geometryCache",new Class<?>[]{Path.class},cache);}catch(NoSuchMethodException baseline){}
            int savedCount=d.cells.size();double expectedInjected=d.injected;d=null;
            long restore=System.nanoTime()-checkpointStart;long resumeStart=System.nanoTime();s.resumeReadyCubes();long resume=System.nanoTime()-resumeStart;
            start=System.nanoTime();long hundred=-1,five=-1,first=-1,maxTick=0,ticks=0;long deadline=start+TimeUnit.SECONDS.toNanos(150);
            while(s.geometryMetrics.coldPublishCount<savedCount&&System.nanoTime()<deadline){
                long t=System.nanoTime();s.tick(2000000,20,false);maxTick=Math.max(maxTick,System.nanoTime()-t);ticks++;
                if(first<0&&s.publications>0)first=System.nanoTime()-start;
                if(ticks==100)hundred=s.geometryMetrics.coldPublishCount;
                if(five<0&&System.nanoTime()-start>=5000000000L)five=s.geometryMetrics.coldPublishCount;
                if(ticks%100==0)heapMax=Math.max(heapMax,heap());
                if(paced){long due=start+ticks*50000000L;long wait=due-System.nanoTime();if(wait>0)TimeUnit.NANOSECONDS.sleep(wait);if(ticks>=100)break;}
            }
            long elapsed=System.nanoTime()-start;boolean done=s.geometryMetrics.coldPublishCount==savedCount;
            if(five<0&&(done||elapsed>=5000000000L))five=s.geometryMetrics.coldPublishCount;
            if(Math.abs(s.balanceError())>1e-6*Math.max(1,expectedInjected))throw new AssertionError("ledger");
            if(done&&s.geometryMetrics.coldPublishCount!=savedCount)throw new AssertionError("missing saved cells");
            System.out.printf(Locale.ROOT,"{\"mode\":\"%s\",\"mc_chunks\":3000,\"saved_cells\":%d,\"sections_per_chunk\":%d,\"fixture_ms\":%.3f,\"mass_object_restore_ms\":%.3f,\"resume_ms\":%.3f,\"wall_ms\":%.3f,\"first_publish_ms\":%.3f,\"published_at_100_unpaced_ticks\":%d,\"published_at_5s\":%d,\"completed_before_5s\":%s,\"completed\":%s,\"published\":%d,\"unpaced_ticks\":%d,\"max_smog_tick_ms\":%.3f,\"geometry_ms\":%.3f,\"partition_ms\":%.3f,\"publish_ms\":%.3f,\"snapshot_build_ms\":%.3f,\"snapshot_builds\":%d,\"heap_sample_max\":%d,\"gc_count\":%d,\"gc_ms\":%d,\"balance\":%.9g}%n",mode,savedCount,sections,fixture/1e6,restore/1e6,resume/1e6,elapsed/1e6,first/1e6,hundred,five,done&&elapsed<5000000000L,done,s.publications,ticks,maxTick/1e6,s.geometryMetrics.geometryNanos/1e6,s.geometryMetrics.phaseNanos[GeometryMetrics.PARTITION]/1e6,s.publishNanos/1e6,world.nanos/1e6,world.builds,heapMax,gc(false)-gc,gc(true)-gcms,s.balanceError());
            try{System.out.println(call(field(s,"restartMetrics"),"logLine",new Class<?>[0]));}catch(NoSuchFieldException baseline){}
        }
    }
}

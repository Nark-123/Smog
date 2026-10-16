package smog;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Real game ticks; console output is emitted only after all timed work.
 */
public final class SparseBenchmark {

    static final com.sun.management.ThreadMXBean MX = (com.sun.management.ThreadMXBean) ManagementFactory
        .getThreadMXBean();

    static long gc(boolean time) {
        long n = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            n += Math.max(0, time ? b.getCollectionTime() : b.getCollectionCount());
        }
        return n;
    }

    static class World implements WorldSnapshotProvider {

        final int sx, sz;

        final Map<CubeKey, ImmutableCubeSnapshot> cache = new HashMap<>();

        final Set<CubeKey> offline = new HashSet<>();

        long revision;

        World(int x, int z) {
            sx = x;
            sz = z;
        }

        public boolean isReady(CubeKey k) {
            return k.dimension == 0 && k.y == 0 && k.x >= 0 && k.x < sx && k.z >= 0 && k.z < sz && !offline.contains(k);
        }

        public ImmutableCubeSnapshot snapshotIfReady(CubeKey k) {
            if (!isReady(k)) {
                throw new AssertionError("Unavailable read");
            }
            ImmutableCubeSnapshot s = cache.get(k);
            if (s == null) {
                byte[] a = new byte[4096];
                Arrays.fill(a, (byte) 1);
                s = new ImmutableCubeSnapshot(k, revision, revision, a);
                cache.put(k, s);
            }
            return s;
        }

        void wall(DimensionState s, CubeKey k, boolean closed) {
            byte[] a = new byte[4096];
            Arrays.fill(a, (byte) 1);
            if (closed) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 0; y < 16; y++) {
                        a[8 + 16 * y + 256 * z] = 0;
                    }
                }
            }
            cache.put(k, new ImmutableCubeSnapshot(k, ++revision, revision, a));
            s.invalidateRegion(
                new BlockPos(0, k.x * 16 + 8, 0, k.z * 16),
                new BlockPos(0, k.x * 16 + 8, 15, k.z * 16 + 15));
        }
    }

    static void run(String scene, int nx, int nz, int ticks, int parts) throws Exception {
        World w = new World(nx, nz);
        long cold = System.nanoTime();
        try (DimensionState s = new DimensionState(0, SmogConfig.throat8(), w)) {
            for (int z = 0; z < nz; z++) {
                for (int x = 0; x < nx; x++) {
                    s.onWorldCubeReady(new CubeKey(0, x, 0, z), 0, 0);
                }
            }
            cold = System.nanoTime() - cold;
            int cleanRecords = s.cubeCount();
            BlockPos[] sources = { new BlockPos(0, 20, 4, 20), new BlockPos(0, nx * 8, 4, nz * 8),
                new BlockPos(0, nx * 16 - 20, 4, nz * 16 - 20) };
            for (BlockPos p : sources) {
                s.setSourceRate(p, 5);
            }
            Path checkpoint = Files.createTempDirectory(Paths.get("build"), "sparse-bench-");
            CompletableFuture<Path> save = null;
            long processStart = ((com.sun.management.OperatingSystemMXBean) ManagementFactory
                .getOperatingSystemMXBean()).getProcessCpuTime();
            long[][] rows = new long[ticks][8];
            long cpu = MX.getCurrentThreadCpuTime(), alloc = MX.getThreadAllocatedBytes(
                Thread.currentThread()
                    .getId()),
                gc = gc(false), gct = gc(true);
            long maxSnapshot = 0, maxPark = 0;
            double parkedPeak = 0;
            for (int t = 0; t < ticks; t++) {
                long start = System.nanoTime();
                s.lastParkNanos = 0;
                if (scene.equals("sparse-world")) {
                    if (t == 300 || t == 700) {
                        for (int z = 0; z < 6; z++) {
                            for (int x = 0; x < 6; x++) {
                                CubeKey k = new CubeKey(0, x, 0, z);
                                if (t == 300) {
                                    ImmutableCubeSnapshot old = w.cache.get(k);
                                    w.offline.add(k);
                                    s.onWorldCubeUnloading(k, old == null ? w.revision : old.epoch);
                                } else {
                                    w.offline.remove(k);
                                    w.cache.remove(k);
                                    ImmutableCubeSnapshot a = w.snapshotIfReady(k);
                                    s.onWorldCubeReady(k, a.epoch, a.revision);
                                }
                            }
                        }
                    }
                    if (t > 0 && t % 170 == 0) {
                        w.wall(s, new CubeKey(0, nx / 2, 0, nz / 2), ((t / 170) & 1) != 0);
                    }
                    if ((t == 800 || t == 1500) && (save == null || save.isDone())) {
                        save = s.requestSave(checkpoint);
                    }
                }
                s.tick(500000, parts, false);
                rows[t][0] = System.nanoTime() - start;
                rows[t][1] = s.lastGeometryNanos;
                rows[t][2] = s.lastLawNanos;
                rows[t][3] = s.lastSourceNanos;
                rows[t][4] = s.lastParkNanos;
                rows[t][5] = s.lastSnapshotNanos;
                rows[t][6] = s.publications;
                rows[t][7] = s.staleJobs;
                maxSnapshot = Math.max(maxSnapshot, s.lastSnapshotNanos);
                maxPark = Math.max(maxPark, s.lastParkNanos);
                if (t == 300) {
                    parkedPeak = s.parkedMass();
                }
            }
            long processNs = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                .getProcessCpuTime() - processStart;
            long gcCount = gc(false) - gc, gcTime = gc(true) - gct;
            long cpuNs = MX.getCurrentThreadCpuTime() - cpu, allocated = MX.getThreadAllocatedBytes(
                Thread.currentThread()
                    .getId())
                - alloc;
            long drainCpu = MX.getCurrentThreadCpuTime();
            while (s.pendingIntervals() > 0) {
                s.finishPendingPart(parts);
            }
            drainCpu = MX.getCurrentThreadCpuTime() - drainCpu;
            if (save != null) {
                for (int i = 0; i < 100000 && !save.isDone(); i++) {
                    s.geometry(500000);
                }
                save.get(30, TimeUnit.SECONDS);
            }
            if (save != null) {
                long loadStart = System.nanoTime();
                try (DimensionState restored = DimensionState.load(checkpoint, w)) {
                    long loadNs = System.nanoTime() - loadStart;
                    double restoredMass = restored.parkedMass();
                    long restoreStart = System.nanoTime();
                    restored.resumeReadyCubes();
                    SparseTests.drain(restored);
                    System.out.printf(
                        Locale.ROOT,
                        "{\"kind\":\"restore\",\"scene\":\"%s\",\"load_ns\":%d,\"geometry_ns\":%d,\"parked_peak\":%.9f,\"restored_mass\":%.9f,\"balance\":%.12g}%n",
                        scene,
                        loadNs,
                        System.nanoTime() - restoreStart,
                        parkedPeak,
                        restoredMass,
                        restored.balanceError());
                }
            }
            long size = 0;
            try (java.util.stream.Stream<Path> files = Files.list(checkpoint)) {
                for (Path p : (Iterable<Path>) files::iterator) {
                    size += Files.size(p);
                }
            }
            System.out.printf(
                "{\"kind\":\"micro\",\"scene\":\"%s\",\"max_publish_ns\":%d,\"max_partition_unit_ns\":%d,\"max_contact_unit_ns\":%d,\"max_snapshot_unit_ns\":%d}%n",
                scene,
                s.maxPublishNanos,
                s.maxBuildUnitNanos,
                s.maxContactUnitNanos,
                s.maxSnapshotUnitNanos);
            int positive = 0;
            for (DimensionState.CubeRecord cube : s.cubes.values()) {
                for (DimensionState.Cell c : cube.cells) {
                    if (c != null && c.geometry != null) {
                        for (int id : c.geometry.ids) {
                            if (s.graph.nodeMass(id) > 0) {
                                positive++;
                                break;
                            }
                        }
                    }
                }
            }
            if (scene.equals("saturated")) {
                SparseTests.yes(positive == nx * nz * 8, "Long run did not saturate");
            }
            System.out.printf(
                "{\"kind\":\"cpu\",\"scene\":\"%s\",\"process_interval_ns\":%d,\"writer_cpu_ns\":%d,\"positive_cells\":%d}%n",
                scene,
                processNs,
                s.ioCpuNanos,
                positive);
            long[] times = new long[ticks];
            for (int i = 0; i < ticks; i++) {
                times[i] = rows[i][0];
                System.out.printf(
                    Locale.ROOT,
                    "{\"kind\":\"timing\",\"scene\":\"%s\",\"tick\":%d,\"ns\":%d,\"geometry_ns\":%d,\"law_ns\":%d,\"source_ns\":%d,\"park_ns\":%d,\"snapshot_ns\":%d,\"publications\":%d,\"stale\":%d}%n",
                    scene,
                    i,
                    rows[i][0],
                    rows[i][1],
                    rows[i][2],
                    rows[i][3],
                    rows[i][4],
                    rows[i][5],
                    rows[i][6],
                    rows[i][7]);
            }
            Arrays.sort(times);
            System.out.printf(
                Locale.ROOT,
                "{\"kind\":\"summary\",\"scene\":\"%s\",\"ticks\":%d,\"parts\":%d,\"p50\":%d,\"p95\":%d,\"p99\":%d,\"max\":%d,\"cpu_ns\":%d,\"drain_cpu_ns\":%d,\"cold_ns\":%d,\"clean_records\":%d,\"allocated\":%d,\"gc_count\":%d,\"gc_ms\":%d,\"cells\":%d,\"total_cells\":%d,\"nodes\":%d,\"edges\":%d,\"build_ns\":%d,\"rebuild_ns\":%d,\"contacts_ns\":%d,\"publish_ns\":%d,\"park_ns\":%d,\"snapshot_ns\":%d,\"snapshot_max\":%d,\"park_max\":%d,\"io_ns\":%d,\"checkpoint_bytes\":%d,\"model_seconds\":%.9f,\"balance\":%.12g,\"generated\":%.9f,\"pending\":%.9f,\"parked\":%.9f,\"overruns\":%d}%n",
                scene,
                ticks,
                parts,
                times[(ticks - 1) / 2],
                times[(int) Math.ceil(.95 * ticks) - 1],
                times[(int) Math.ceil(.99 * ticks) - 1],
                times[ticks - 1],
                cpuNs,
                drainCpu,
                cold,
                cleanRecords,
                allocated,
                gcCount,
                gcTime,
                s.cellCount(),
                nx * nz * 8,
                s.nodeCount(),
                s.edgeCount(),
                s.buildNanos,
                s.rebuildNanos,
                s.contactNanos,
                s.publishNanos,
                s.parkNanos,
                s.snapshotNanos,
                maxSnapshot,
                maxPark,
                s.ioNanos,
                size,
                s.modelTime(),
                s.balanceError(),
                s.generatedMass(),
                s.pendingMass(),
                s.parkedMass(),
                s.budgetOverruns);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("saturated")) {
            run("saturated", 4, 4, 5000, 1);
        } else {
            run("sparse-world", 50, 100, 2200, 20);
        }
    }
}

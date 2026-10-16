package smog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Standalone integration suite; no external dependencies.
 */
public final class SparseTests {

    static long checks;

    static void yes(boolean b, String m) {
        checks++;
        if (!b) {
            throw new AssertionError(m);
        }
    }

    static void near(double a, double b, double tol, String m) {
        yes(Math.abs(a - b) <= tol * Math.max(1, Math.max(Math.abs(a), Math.abs(b))), m + ": " + a + " != " + b);
    }

    static class World implements WorldSnapshotProvider {

        final Map<CubeKey, ImmutableCubeSnapshot> all = new HashMap<>();

        final Set<CubeKey> ready = new HashSet<>();

        long reads;

        public boolean isReady(CubeKey k) {
            return ready.contains(k);
        }

        public ImmutableCubeSnapshot snapshotIfReady(CubeKey k) {
            reads++;
            if (!ready.contains(k)) {
                throw new AssertionError("Unavailable read");
            }
            return all.get(k);
        }

        void put(CubeKey k, byte[] a, long epoch, long rev) {
            all.put(k, new ImmutableCubeSnapshot(k, epoch, rev, a));
            ready.add(k);
        }

        void load(DimensionState s, CubeKey k) {
            ready.add(k);
            ImmutableCubeSnapshot a = all.get(k);
            s.onWorldCubeReady(k, a.epoch, a.revision);
        }

        void unload(DimensionState s, CubeKey k) {
            ready.remove(k);
            s.onWorldCubeUnloading(k, all.get(k).epoch);
        }

        void change(CubeKey k, byte[] a) {
            ImmutableCubeSnapshot old = all.get(k);
            all.put(k, new ImmutableCubeSnapshot(k, old.epoch + 1, old.revision + 1, a));
        }
    }

    static byte[] air() {
        byte[] a = new byte[4096];
        Arrays.fill(a, (byte) 1);
        return a;
    }

    static World world(byte[] a, int nx, int ny, int nz, int mask) {
        World w = new World();
        for (int cz = 0; cz < (nz + 15) / 16; cz++) {
            for (int cy = 0; cy < (ny + 15) / 16; cy++) {
                for (int cx = 0; cx < (nx + 15) / 16; cx++) {
                    byte[] b = new byte[4096], atm = new byte[4096];
                    for (int p = 0; p < 4096; p++) {
                        int x = cx * 16 + p % 16, y = cy * 16 + p / 16 % 16, z = cz * 16 + p / 256;
                        if (x < nx && y < ny && z < nz) {
                            b[p] = a[x + nx * y + nx * ny * z];
                            int m = (x == 0 ? 1 : 0) | (x == nx - 1 ? 2 : 0)
                                | (y == 0 ? 4 : 0)
                                | (y == ny - 1 ? 8 : 0)
                                | (z == 0 ? 16 : 0)
                                | (z == nz - 1 ? 32 : 0);
                            atm[p] = (byte) (m & mask);
                        }
                    }
                    CubeKey k = new CubeKey(0, cx, cy, cz);
                    w.all.put(k, new ImmutableCubeSnapshot(k, 1, 1, b, atm));
                    w.ready.add(k);
                }
            }
        }
        return w;
    }

    static void drain(DimensionState s) {
        for (int n = 0; s.geometryPending() && n < 100000; n++) {
            s.geometry(500000);
        }
        yes(!s.geometryPending(), "Geometry did not settle");
    }

    static void full(DimensionState s, int nx, int ny, int nz) {
        for (int z = 0; z < nz; z += 8) {
            for (int y = 0; y < ny; y += 8) {
                for (int x = 0; x < nx; x += 8) {
                    s.requestBuild(new CellKey(0, x / 8, y / 8, z / 8));
                }
            }
        }
        drain(s);
    }

    static SortedMap<String, Double> denseTopology(SmogGraph g) {
        int[] labels = g.copyLabels();
        Map<Integer, StringBuilder> ids = new HashMap<>();
        for (int z = 0; z < g.nz; z += 8) {
            for (int y = 0; y < g.ny; y += 8) {
                for (int x = 0; x < g.nx; x += 8) {
                    CellKey k = new CellKey(0, x / 8, y / 8, z / 8);
                    for (int p = 0; p < 512; p++) {
                        int xx = x + p % 8, yy = y + p / 8 % 8, zz = z + p / 64;
                        if (xx >= g.nx || yy >= g.ny || zz >= g.nz) {
                            continue;
                        }
                        int id = labels[g.index(xx, yy, zz)];
                        if (id >= 0) {
                            if (!ids.containsKey(id)) {
                                ids.put(id, new StringBuilder(k.toString()).append('/'));
                            }
                            ids.get(id)
                                .append(p)
                                .append(',');
                        }
                    }
                }
            }
        }
        SortedMap<String, Double> out = new TreeMap<>();
        for (StringBuilder b : ids.values()) {
            out.put("N:" + b, 0.0);
        }
        double[] e = g.copyEdges();
        for (int i = 0; i < e.length; i += 3) {
            if (e[i] >= 0) {
                String a = ids.get((int) e[i])
                    .toString(),
                    b = ids.get((int) e[i + 1])
                        .toString();
                out.put("E:" + (a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a), e[i + 2]);
            }
        }
        return out;
    }

    static void topology(DimensionState s, SmogGraph g) {
        yes(s.nodeCount() == g.nodeCount(), "Nodes " + s.nodeCount() + " vs " + g.nodeCount());
        yes(s.edgeCount() == g.edgeCount(), "Edges " + s.edgeCount() + " vs " + g.edgeCount());
        yes(
            s.topology()
                .equals(denseTopology(g)),
            "Spatial topology mismatch");
    }

    static void orders() {
        Random r = new Random(713);
        int n = 64;
        byte[] a = new byte[n * n * n];
        for (int p = 0; p < a.length; p++) {
            a[p] = (byte) (r.nextDouble() < .14 ? 0 : 1);
        }
        SmogGraph g = new SmogGraph(a, n, n, n);
        for (int order = 0; order < 4; order++) {
            World w = world(a, n, n, n, 0);
            List<CubeKey> keys = new ArrayList<>(w.ready);
            Collections.sort(keys);
            if (order == 0) {
                keys.sort(Comparator.comparingInt(k -> (k.x + k.y + k.z) & 1));
            }
            if (order == 1) {
                Collections.reverse(keys);
            }
            if (order == 2) {
                Collections.shuffle(keys, new Random(41));
            }
            w.ready.clear();
            try (DimensionState s = new DimensionState(0, SmogConfig.throat8(), w)) {
                for (int z = 0; z < n / 8; z++) {
                    for (int y = 0; y < n / 8; y++) {
                        for (int x = 0; x < n / 8; x++) {
                            s.requestBuild(new CellKey(0, x, y, z));
                        }
                    }
                }
                for (CubeKey k : keys) {
                    w.load(s, k);
                    int delay = 1 + r.nextInt(100);
                    for (int t = 0; t < delay; t++) {
                        s.geometry(50000);
                    }
                }
                drain(s);
                topology(s, g);
                long before = s.publications;
                for (CubeKey k : keys) {
                    ImmutableCubeSnapshot v = w.all.get(k);
                    s.onWorldCubeReady(k, v.epoch, v.revision);
                }
                drain(s);
                topology(s, g);
                yes(before == s.publications, "Duplicate READY publication");
            }
        }
        byte[] tunnels = new byte[16 * 8 * 8];
        for (int x = 0; x < 16; x++) {
            tunnels[x + 16 + 16 * 8 * 3] = 1;
            tunnels[x + 16 * 6 + 16 * 8 * 3] = 1;
        }
        World w = world(tunnels, 16, 8, 8, 0);
        try (DimensionState s = new DimensionState(0, SmogConfig.throat8(), w)) {
            full(s, 16, 8, 8);
            topology(s, new SmogGraph(tunnels, 16, 8, 8));
            yes(s.edgeCount() >= 2, "Collapsed independent cavities");
        }
        System.out.println("PASS 4x4x4 cube load orders, 1-100 tick delays, duplicate READY, independent cavities");
    }

    static void lazy() {
        World w = new World();
        byte[] a = air();
        for (int i = 0; i < 5000; i++) {
            w.put(new CubeKey(0, i, 0, 0), a, 1, 1);
        }
        try (DimensionState s = new DimensionState(0, SmogConfig.throat8(), w)) {
            for (CubeKey k : w.ready) {
                s.onWorldCubeReady(k, 1, 1);
            }
            yes(s.cubeCount() == 0 && s.nodeCount() == 0, "Eager allocation");
            yes(w.reads == 0, "Read clean READY");
            for (int i : new int[] { 1, 1500, 3500 }) {
                s.setSourceRate(new BlockPos(0, i * 16 + 4, 4, 4), 1);
            }
            for (int t = 0; t < 60; t++) {
                s.tick(500000);
            }
            yes(s.cellCount() < 100, "Recursive build");
            near(s.generatedMass(), 9, 1e-12, "Emission accounting");
            near(s.balanceError(), 0, 1e-11, "lazy ledger");
        }
        World two = new World();
        CubeKey k = new CubeKey(0, 0, 0, 0), b = new CubeKey(0, 1, 0, 0);
        two.put(k, air(), 1, 1);
        two.all.put(b, new ImmutableCubeSnapshot(b, 1, 1, air()));
        try (DimensionState s = new DimensionState(0, SmogConfig.throat8(), two)) {
            s.queueAddMass(new BlockPos(0, 15, 4, 4), 100);
            drain(s);
            s.finishPendingPart(1);
            drain(s);
            yes(s.escapedMass() == 0, "False sink");
            two.load(s, b);
            drain(s);
            yes(s.status(new CellKey(0, 2, 0, 0)) == DimensionState.Status.PUBLISHED, "Front did not resume");
            near(s.sample(new BlockPos(0, 16, 4, 4)), 0, 0, "Publication mixing");
            s.tick(500000, 1, false);
            yes(s.sample(new BlockPos(0, 16, 4, 4)) > 0, "Late transfer absent");
        }
        System.out.println("PASS 5000 clean READY/three sources and late positive frontier");
    }

    static void physics() {
        for (int parts : new int[] { 1, 20 }) {
            for (boolean sinks : new boolean[] { false, true }) {
                int n = 32;
                byte[] a = new byte[n * n * n];
                Arrays.fill(a, (byte) 1);
                SmogConfig cfg = SmogConfig.builder()
                    .openMask(sinks ? 63 : 0)
                    .diffusion(20)
                    .build();
                World w = world(a, n, n, n, cfg.openMask);
                try (DimensionState s = new DimensionState(0, cfg, w)) {
                    full(s, n, n, n);
                    SmogGraph g = new SmogGraph(a, n, n, n, cfg);
                    int count = 2200;
                    int[] positions = new int[count];
                    double[] rates = new double[count];
                    for (int i = 0; i < count; i++) {
                        int p = i * 13 % a.length;
                        positions[i] = p;
                        rates[i] = i % 7 * .01;
                        s.setSourceRate(
                            new BlockPos(0, p % n, p / n % n, p / n / n),
                            rates[i],
                            (int) ((i * 2654435761L) % cfg.cyclePeriod));
                    }
                    g.setSources(positions, rates);
                    s.queueAddMass(new BlockPos(0, 16, 16, 16), 1e-200);
                    g.queueAddMass(g.index(16, 16, 16), 1e-200);
                    for (int t = 0; t < parts * 5; t++) {
                        s.tick(0, parts, false);
                        g.stepPart(parts, false);
                    }
                    while (s.pendingIntervals() > 0) {
                        s.finishPendingPart(parts);
                    }
                    while (g.pendingIntervals() > 0) {
                        g.finishPendingPart(parts);
                    }
                    for (int p = 0; p < a.length; p++) {
                        near(
                            s.sample(new BlockPos(0, p % n, p / n % n, p / n / n)),
                            g.sample(p),
                            1e-11,
                            "law4 parts=" + parts);
                    }
                    near(s.injectedMass(), g.injectedMass(), 1e-11, "injected");
                    near(s.escapedMass(), g.escapedMass(), 1e-11, "escaped");
                    near(s.balanceError(), 0, 1e-10, "physics ledger");
                }
            }
        }
        World w = new World();
        w.put(new CubeKey(0, 0, 0, 0), air(), 1, 1);
        w.put(new CubeKey(0, 1, 0, 0), air(), 1, 1);
        try (DimensionState s = new DimensionState(0, SmogConfig.throat8(), w)) {
            for (int x = 0; x < 3; x++) {
                s.requestBuild(new CellKey(0, x, 0, 0));
            }
            drain(s);
            s.queueAddMass(new BlockPos(0, 1, 1, 1), 10);
            s.finishPendingPart(1);
            s.tick(0, 1, false);
            yes(s.sample(new BlockPos(0, 8, 1, 1)) > 0, "First edge");
            yes(s.sample(new BlockPos(0, 16, 1, 1)) == 0, "Two-edge teleport");
        }
        System.out.println("PASS law4 differential parts=1/20, 2200 sources, physical sinks, synchronous transport");
    }

    static void lifecycle() {
        SmogConfig cfg = SmogConfig.throat8();
        World w = new World();
        CubeKey k = new CubeKey(0, 0, 0, 0);
        w.put(k, air(), 1, 1);
        try (DimensionState s = new DimensionState(0, cfg, w)) {
            BlockPos p = new BlockPos(0, 1, 1, 1);
            s.queueAddMass(p, 100);
            drain(s);
            s.finishPendingPart(1);
            w.unload(s, k);
            near(s.parkedMass(), 100, 1e-12, "park");
            yes(s.edgeCount() == 0, "Stale edges");
            byte[] a = air();
            for (int z = 0; z < 8; z++) {
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 4; x++) {
                        a[x + 16 * y + 256 * z] = 0;
                    }
                }
            }
            w.change(k, a);
            w.load(s, k);
            drain(s);
            near(s.liveMass(), 100, 1e-12, "survivors");
            near(s.trappedMass(), 0, 0, "Partial node trapped");
            w.unload(s, k);
            w.change(k, new byte[4096]);
            w.load(s, k);
            drain(s);
            near(s.trappedMass(), 100, 1e-12, "Full trap");
            w.unload(s, k);
            w.change(k, air());
            w.load(s, k);
            drain(s);
            near(s.liveMass(), 100, 1e-12, "Release trap");
            for (int t = 0; t < 25; t++) {
                s.tick(0);
            }
            long cancels = s.unloadCancellations;
            w.change(k, air());
            s.invalidateBlocks(Arrays.asList(new BlockPos(0, 7, 1, 1)));
            for (int t = 0; t < 15; t++) {
                s.tick(500000);
            }
            yes(s.iteration() >= 1, "Solver starved");
            yes(s.unloadCancellations == cancels, "Rebuild canceled");
            w.unload(s, k);
            w.change(k, air());
            w.load(s, k);
            w.unload(s, k);
            w.change(k, air());
            w.load(s, k);
            drain(s);
            near(s.balanceError(), 0, 1e-10, "rapid lifecycle");
        }
        World v = new World();
        for (int y = -2; y <= 0; y++) {
            v.put(new CubeKey(0, -1, y, 0), air(), 1, 1);
        }
        v.put(new CubeKey(0, -1, 30, 0), air(), 1, 1);
        try (DimensionState s = new DimensionState(0, cfg, v)) {
            for (int y : new int[] { -32, -16, 0, 480 }) {
                s.queueAddMass(new BlockPos(0, -1, y, 1), 7);
            }
            drain(s);
            s.finishPendingPart(1);
            drain(s);
            v.unload(s, new CubeKey(0, -1, -1, 0));
            near(s.parkedMass(), 7, 1e-12, "Middle Y");
            yes(s.sample(new BlockPos(0, -1, -32, 1)) > 0 && s.sample(new BlockPos(0, -1, 480, 1)) > 0, "Other Y");
            v.ready.clear();
            WorldAdapters.columnUnloading(s, -1, 0);
            near(s.parkedMass(), 28, 1e-12, "Column unload");
            v.ready.addAll(v.all.keySet());
            WorldAdapters.columnReady(s, -1, 0);
            drain(s);
            near(s.liveMass(), 28, 1e-12, "Column restore");
            near(s.balanceError(), 0, 1e-12, "column ledger");
        }
        boolean rejected = false;
        try {
            new DimensionState(
                0,
                SmogConfig.builder()
                    .phase(1)
                    .build(),
                w);
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        yes(rejected, "phase restriction");
        System.out.println("PASS remap, trapped restore, rapid lifecycle, negative/high Y, columns, phase rejection");
    }

    static void save(DimensionState s, Path path) throws Exception {
        CompletableFuture<Path> f = s.requestSave(path);
        for (int i = 0; i < 100000 && !f.isDone(); i++) {
            s.finishPendingPart(s.scheduleParts == 0 ? 20 : s.scheduleParts);
            s.geometry(500000);
            if (i % 100 == 0) {
                Thread.yield();
            }
        }
        f.get(20, TimeUnit.SECONDS);
    }

    static void saves() throws Exception {
        Path dir = Files.createTempDirectory(Paths.get("build"), "checkpoint-test-");
        World w = new World();
        CubeKey k = new CubeKey(0, 0, 0, 0);
        w.put(k, air(), 1, 1);
        try (DimensionState s = new DimensionState(0, SmogConfig.throat8(), w)) {
            BlockPos p = new BlockPos(0, 1, 1, 1);
            s.setSourceRate(p, 2);
            for (int i = 0; i < 25; i++) {
                s.tick(100000);
            }
            s.queueAddMass(p, 3);
            save(s, dir);
            double total = s.generatedMass();
            long ticks = s.gameTicks();
            try (DimensionState load = DimensionState.load(dir, w)) {
                yes(load.nodeCount() == 0 && load.edgeCount() == 0, "Restored old edges");
                yes(load.gameTicks() == ticks, "clocks");
                near(load.generatedMass(), total, 1e-12, "pending intervals");
                w.change(k, new byte[4096]);
                w.load(load, k);
                drain(load);
                for (int i = 0; i < 40; i++) {
                    load.tick(500000);
                }
                while (load.pendingIntervals() > 0) {
                    load.finishPendingPart(20);
                }
                near(load.balanceError(), 0, 1e-10, "restart ledger");
                yes(load.trappedMass() > 0, "offline wall mass");
                yes(load.liveMass() == 0, "Offline wall air");
                save(load, dir);
                byte[] broken = Files.readAllBytes(dir.resolve("generation-2.smog"));
                broken[broken.length / 2] ^= 1;
                Files.write(dir.resolve("generation-2.smog"), broken);
                try (DimensionState fallback = DimensionState.load(dir, w)) {
                    near(fallback.generatedMass(), total, 1e-12, "fallback");
                }
            }
            boolean stale = false;
            try {
                save(s, dir);
            } catch (ExecutionException e) {
                stale = true;
            }
            yes(stale, "stale writer");
            Files.write(dir.resolve("interrupted.tmp"), new byte[] { 1, 2 });
            Files.write(dir.resolve("manifest.previous"), new byte[] { 1 });
            boolean failed = false;
            try {
                DimensionState.load(dir, w);
            } catch (IOException e) {
                failed = true;
            }
            yes(failed, "Corruption silently reset");
        }
        System.out.println("PASS checkpoint pending intervals/offline walls/fallback/stale writer/corruption");
    }

    static void edits() {
        Random r = new Random(99);
        int n = 32;
        byte[] a = new byte[n * n * n];
        for (int p = 0; p < a.length; p++) {
            a[p] = (byte) (r.nextDouble() < .15 ? 0 : 1);
        }
        World w = world(a, n, n, n, 0);
        try (DimensionState s = new DimensionState(0, SmogConfig.throat8(), w)) {
            full(s, n, n, n);
            for (int round = 0; round < 8; round++) {
                List<BlockPos> changes = new ArrayList<>();
                for (int i = 0; i < 40; i++) {
                    int x = 7 + r.nextInt(3), y = r.nextInt(n), z = r.nextInt(n), p = x + n * y + n * n * z;
                    a[p] = (byte) (1 - a[p]);
                    changes.add(new BlockPos(0, x, y, z));
                }
                World next = world(a, n, n, n, 0);
                for (CubeKey k : w.all.keySet()) {
                    ImmutableCubeSnapshot old = w.all.get(k), nw = next.all.get(k);
                    byte[] bytes = new byte[4096];
                    for (int p = 0; p < 4096; p++) {
                        bytes[p] = (byte) (nw.airAt(k.x * 16 + p % 16, k.y * 16 + p / 16 % 16, k.z * 16 + p / 256) ? 1
                            : 0);
                    }
                    w.all.put(k, new ImmutableCubeSnapshot(k, old.epoch, old.revision + 1, bytes));
                }
                s.invalidateBlocks(changes);
                drain(s);
                topology(s, new SmogGraph(a, n, n, n));
            }
        }
        System.out.println("PASS bulk boundary edits and THROAT two-block halo");
    }

    public static void main(String[] args) throws Exception {
        orders();
        lazy();
        physics();
        lifecycle();
        saves();
        edits();
        System.out.println("PASS SparseTests checks=" + checks);
    }
}

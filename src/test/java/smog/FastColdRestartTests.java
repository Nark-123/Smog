package smog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Random;
import java.util.SortedMap;
import java.util.concurrent.TimeUnit;

/** Real core/cache/checkpoint integration, synthetic world; no Minecraft server. */
public final class FastColdRestartTests {

    static int checks;

    static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }

    static void near(double a, double b, String why) {
        check(Math.abs(a - b) <= 1e-9 * Math.max(1, Math.max(Math.abs(a), Math.abs(b))), why + ": " + a + " / " + b);
    }

    static final SmogConfig CONFIG = SmogConfig.builder()
        .mode(SmogConfig.COMPONENT)
        .build();
    static final CubeKey CUBE = new CubeKey(0, 0, 0, 0);

    static void options(boolean cache, boolean fast, boolean boot) {
        System.setProperty("smog.geometryCacheEnabled", "" + cache);
        System.setProperty("smog.fastRestoreEnabled", "" + fast);
        System.setProperty("smog.bootstrapEnabled", "" + boot);
    }

    static SparseTests.World world() {
        SparseTests.World w = new SparseTests.World();
        w.put(CUBE, SparseTests.air(), 1, 1);
        return w;
    }

    static void drain(DimensionState s) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (s.geometryPending() && System.nanoTime() < end) {
            s.geometry(2000000);
            Thread.yield();
        }
        check(!s.geometryPending(), "restoration did not finish");
    }

    static Checkpoint.Data snapshot(DimensionState s) {
        Checkpoint.Capture c = new Checkpoint.Capture(s);
        while (!c.advance()) {}
        return c.data;
    }

    static Checkpoint.Data seed(Path cache, SparseTests.World w) throws Exception {
        options(true, false, false);
        try (DimensionState s = new DimensionState(0, CONFIG, w)) {
            s.geometryCache(cache);
            for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++)
                for (int x = 0; x < 2; x++) s.queueAddMass(new CellKey(0, x, y, z).block(0), 10);
            drain(s);
            s.geometryCache.flush()
                .get(15, TimeUnit.SECONDS);
            s.geometryCache.syncMetrics(s.restartMetrics);
            check(s.restartMetrics.get("cache_write_failures") == 0, "cache write failed");
            near(s.liveMass(), 80, "seed mass");
            return snapshot(s);
        }
    }

    static DimensionState restore(Checkpoint.Data data, Path cache, SparseTests.World w, boolean fast,
        boolean bootstrap) {
        options(true, fast, bootstrap);
        DimensionState s = Checkpoint.restore(data, w);
        s.geometryCache(cache);
        long reads = w.reads;
        s.resumeReadyCubes();
        check(w.reads == reads, "resume performed bulk snapshots");
        return s;
    }

    static void directAndFallback(Path cache, Checkpoint.Data data) throws Exception {
        SortedMap<String, Double> topology;
        options(false, false, false);
        try (DimensionState baseline = Checkpoint.restore(data, world())) {
            baseline.resumeReadyCubes();
            drain(baseline);
            topology = baseline.topology();
        }
        try (DimensionState s = restore(data, cache, world(), true, false)) {
            drain(s);
            check(
                s.topology()
                    .equals(topology),
                "batch topology/unique edges differs");
            near(s.liveMass(), 80, "direct mass");
            near(s.balanceError(), 0, "direct ledger");
            check(s.restartMetrics.get("fast_restore_cells") == 8, "not all direct");
            check(s.restartMetrics.get("fast_restore_batches") == 1, "expected Cube16 batch of eight");
            check(s.geometryMetrics.partitionsCompleted == 0, "cache hit executed Partitioner");
            check(s.geometryMetrics.publishCalls == 8, "batch counted once as a cell");
            check(s.geometryMetrics.safeSnapshot()[GeometryMetrics.SAFE_PUBLISHED] == 8, "safe count");
            near(
                s.geometryMetrics.safeSnapshot()[GeometryMetrics.SAFE_PUBLISH_NANOS],
                s.geometryMetrics.publishNanos,
                "publication time counted twice");
            for (int i = 0; i < 40; i++) s.tick(2000000, 20, false);
            near(s.balanceError(), 0, "law4 after direct");
        }
        try (DimensionState s = restore(data, cache, world(), false, false)) {
            drain(s);
            check(
                s.topology()
                    .equals(topology),
                "cache/ordinary-commit topology");
            check(s.geometryMetrics.partitionsCompleted == 0, "cache-only ran partitioner");
            check(s.restartMetrics.get("fast_restore_batches") == 0, "fast switch ignored");
            near(s.liveMass(), 80, "ordinary cached mass");
        }
        System.setProperty("smog.fastRestoreMaxEdges", "1728");
        try (DimensionState s = restore(data, cache, world(), true, false)) {
            drain(s);
            check(s.restartMetrics.get("fast_restore_batches") == 8, "edge cap did not shrink batch before commit");
            near(s.liveMass(), 80, "shrunk batch mass");
            check(
                s.topology()
                    .equals(topology),
                "shrunk batch topology");
        } finally {
            System.clearProperty("smog.fastRestoreMaxEdges");
        }
        Path missing = Files.createTempDirectory(Paths.get("build"), "missing-pack-");
        try (DimensionState s = restore(data, missing, world(), true, false)) {
            drain(s);
            check(s.geometryMetrics.partitionsCompleted >= 8, "miss did not rebuild");
            check(s.restartMetrics.get("cache_misses") == 8, "miss counters");
            near(s.balanceError(), 0, "miss ledger");
        }
        SparseTests.World changed = world();
        byte[] solid = SparseTests.air();
        Arrays.fill(solid, (byte) 0);
        changed.change(CUBE, solid);
        try (DimensionState s = restore(data, cache, changed, true, false)) {
            drain(s);
            check(s.restartMetrics.get("cache_invalid_world") == 8, "world fingerprint accepted stale geometry");
            near(s.trappedMass(), 80, "solid must trap mass");
            near(s.balanceError(), 0, "changed world ledger");
        }
    }

    static void atomicAndHalo(Path cache, Checkpoint.Data data) throws Exception {
        // Preserve coverage of the independently selectable strict legacy path.
        System.setProperty("smog.optimisticRestore", "false");
        options(true, true, false);
        try (DimensionState s = Checkpoint.restore(data, world())) {
            s.geometryCache(cache);
            FastRestorePlan plan = new FastRestorePlan(s, s.cell(data.cells.get(0).key, false));
            int progress = 1;
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (progress != 0 && System.nanoTime() < end) {
                progress = plan.advance();
                if (progress > 0) check(s.nodeCount() == 0, "plan mutated graph before commit");
            }
            check(progress == 0 && s.nodeCount() == 8, "plan commit incomplete");
            near(s.balanceError(), 0, "plan ledger");
        }
        try (DimensionState s = Checkpoint.restore(data, world())) {
            s.geometryCache(cache);
            FastRestorePlan plan = new FastRestorePlan(s, s.cell(data.cells.get(0).key, false));
            java.lang.reflect.Field field = FastRestorePlan.class.getDeclaredField("stage");
            field.setAccessible(true);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (field.getInt(plan) < 3 && System.nanoTime() < end) plan.advance();
            Path dir = Files.createTempDirectory(Paths.get("build"), "plan-save-cut-");
            java.util.concurrent.CompletableFuture<Path> save = s.requestSave(dir);
            check(plan.advance() == -1 && s.nodeCount() == 0, "plan bypassed requested SAFE CUT");
            while (!save.isDone() && System.nanoTime() < end) s.geometry(2000000);
            save.get(5, TimeUnit.SECONDS);
            check(plan.advance() == 0 && s.nodeCount() == 8, "commit after SAFE CUT failed");
            near(s.balanceError(), 0, "save/plan ledger");
            try (DimensionState restored = DimensionState.load(dir, world())) {
                near(restored.parkedMass(), 80, "snapshot mixed plan commit");
            }
        }
        SparseTests.World w = world();
        try (DimensionState s = Checkpoint.restore(data, w)) {
            s.geometryCache(cache);
            DimensionState.Cell c = s.cell(data.cells.get(0).key, false);
            FastRestorePlan plan = new FastRestorePlan(s, c);
            java.lang.reflect.Field field = FastRestorePlan.class.getDeclaredField("stage");
            field.setAccessible(true);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (field.getInt(plan) < 3 && System.nanoTime() < end) plan.advance();
            check(field.getInt(plan) == 3 && s.nodeCount() == 0, "private plan preparation");
            w.put(new CubeKey(0, -1, 0, 0), SparseTests.air(), 1, 1); // formerly absent halo becomes present
            check(plan.advance() == 0, "stale plan not abandoned");
            check(s.nodeCount() == 0, "partial stale commit");
            near(s.parkedMass(), 80, "stale plan lost mass");
        }
        System.clearProperty("smog.optimisticRestore");
    }

    static void epsilon(Path cache, Checkpoint.Data data) throws Exception {
        Checkpoint.Data copy = CheckpointV2RoundTrip(data);
        Checkpoint.CellData c = copy.cells.get(0);
        copy.injected -= c.mass.total();
        c.mass = c.mass.withMasses(new double[] { .4 });
        copy.injected += .4;
        c.trapped.put(0, .3);
        copy.injected += .3;
        c.waiting.put(0, .4);
        DimensionState.Event event = new DimensionState.Event();
        event.add = .5;
        event.remove = .2;
        c.events.put(0, event);
        try (DimensionState s = restore(copy, cache, world(), true, false)) {
            drain(s);
            near(s.graph.nodeMass(s.cell(c.key, false).geometry.ids[0]), 1.4, "epsilon before final contributions");
            near(s.geometryMetrics.epsilonDroppedMass, 0, "premature pruning");
            near(s.balanceError(), 0, "batch contribution ledger");
        }
    }

    static Checkpoint.Data CheckpointV2RoundTrip(Checkpoint.Data d) throws Exception {
        Path p = Files.createTempDirectory(Paths.get("build"), "roundtrip-")
            .resolve("data");
        CheckpointV2.write(p, d);
        return CheckpointV2.read(p);
    }

    static void continuousRestore() throws Exception {
        // Old properties must never pause law4 or create a five-second startup mode.
        options(false, false, true);
        SparseTests.World w = world();
        Checkpoint.Data d = new Checkpoint.Data();
        d.config = CONFIG;
        d.parts = 20;
        d.dimension = 0;
        for (int x = 0; x < 3000; x++) {
            CubeKey k = new CubeKey(0, x, 0, 0);
            w.put(k, SparseTests.air(), 1, 1);
            Checkpoint.CellData c = new Checkpoint.CellData();
            c.key = new CellKey(0, x * 2, 0, 0);
            c.mass = new DimensionState.MassRecord(new int[512], new int[] { 512 }, new double[] { 10 });
            d.cells.add(c);
            d.injected += 10;
        }
        try (DimensionState s = Checkpoint.restore(d, w)) {
            s.resumeReadyCubes();
            s.setSourceRate(new BlockPos(0, 1, 1, 1), 3);
            for (int i = 0; i < 61; i++) s.tick(1000000, 20, false, 1000000);
            check(s.iteration() > 0, "Restoration paused law4");
            check(s.gameTicks() == 61, "Restore changed physical clock");
            near(s.balanceError(), 0, "Continuous restore ledger");
        }
    }

    static void corruption(Path cache, Checkpoint.Data data) throws Exception {
        Path corrupt = Files.createTempDirectory(Paths.get("build"), "bad-pack-");
        try (java.util.stream.Stream<Path> files = Files.list(cache)) {
            for (Path p : (Iterable<Path>) files::iterator) if (p.toString()
                .endsWith(".gpk")) {
                    byte[] bytes = Files.readAllBytes(p);
                    bytes[bytes.length - 1] ^= 1;
                    Files.write(corrupt.resolve(p.getFileName()), bytes);
                }
        }
        try (DimensionState s = restore(data, corrupt, world(), true, false)) {
            drain(s);
            check(s.restartMetrics.get("cache_corrupt") == 8, "corrupt cache accepted");
            near(s.liveMass(), 80, "corrupt cache lost checkpoint mass");
            near(s.balanceError(), 0, "corrupt fallback ledger");
        }
        options(true, true, false);
        try (DimensionState s = new DimensionState(0, SmogConfig.throat8(), world())) {
            GeometryCache different = new GeometryCache(cache, 0, SmogConfig.throat8(), 1048576);
            try {
                check(
                    different.request(CUBE)
                        .get(5, TimeUnit.SECONDS).status == GeometryCache.Status.CONFIG,
                    "wrong config accepted");
            } finally {
                different.close();
            }
        }
    }

    static void randomCachedDifferential() throws Exception {
        Random random = new Random(146902);
        for (int scene = 0; scene < 12; scene++) {
            SparseTests.World w = new SparseTests.World();
            for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) {
                byte[] air = new byte[4096], atmosphere = new byte[4096];
                for (int p = 0; p < air.length; p++) {
                    air[p] = (byte) (random.nextDouble() > .28 ? 1 : 0);
                    if (air[p] != 0 && p / 16 % 16 == 15) atmosphere[p] = SmogConfig.Y_PLUS;
                }
                CubeKey k = new CubeKey(0, x, 0, z);
                w.all.put(k, new ImmutableCubeSnapshot(k, 1, 1, air, atmosphere));
                w.ready.add(k);
            }
            SmogConfig config = SmogConfig.builder()
                .openMask(SmogConfig.Y_PLUS)
                .build();
            Path cache = Files.createTempDirectory(Paths.get("build"), "random-pack-");
            Checkpoint.Data data;
            options(true, false, false);
            try (DimensionState seed = new DimensionState(0, config, w)) {
                seed.geometryCache(cache);
                for (int x = 0; x < 4; x++) for (int y = 0; y < 2; y++)
                    for (int z = 0; z < 4; z++) seed.queueAddMass(new CellKey(0, x, y, z).block(0), 100);
                drain(seed);
                seed.geometryCache.flush()
                    .get(10, TimeUnit.SECONDS);
                data = snapshot(seed);
            }
            options(false, false, false);
            try (DimensionState a = Checkpoint.restore(data, w)) {
                a.resumeReadyCubes();
                drain(a);
                options(true, true, false);
                try (DimensionState b = Checkpoint.restore(data, w)) {
                    b.geometryCache(cache);
                    b.resumeReadyCubes();
                    drain(b);
                    check(
                        a.topology()
                            .equals(b.topology()),
                        "random aggregated contacts/distance/topology scene " + scene);
                    check(
                        b.geometryMetrics.partitionsCompleted == b.publications - data.cells.size(),
                        "only newly expanded frontier cells may partition scene=" + scene
                            + " "
                            + b.restartMetrics.logLine());
                    check(b.restartMetrics.get("fast_restore_cells") == data.cells.size(), "random cache not complete");
                    for (int tick = 0; tick < 61; tick++) {
                        a.tick(0, 20, false);
                        b.tick(0, 20, false);
                    }
                    for (Checkpoint.CellData c : data.cells) for (int p = 0; p < 512; p += 17)
                        near(a.sample(c.key.block(p)), b.sample(c.key.block(p)), "random law4 differential");
                    near(a.balanceError(), 0, "random ordinary ledger");
                    near(b.balanceError(), 0, "random fast ledger");
                    near(a.escapedMass(), b.escapedMass(), "random sinks");
                }
            }
        }
    }

    static void formatBoundsAndManifest(Checkpoint.Data data) throws Exception {
        Path dir = Files.createTempDirectory(Paths.get("build"), "format-bounds-");
        System.setProperty("smog.checkpointV1", "true");
        Checkpoint.write(dir, data, 0);
        System.clearProperty("smog.checkpointV1");
        Checkpoint.write(dir, data, 1);
        check(Checkpoint.read(dir).data.formatVersion == 2, "v1 to v2 upgrade");
        Files.write(dir.resolve("manifest"), new byte[] { 1 });
        Checkpoint.Loaded good = Checkpoint.read(dir);
        check(good.data.formatVersion == 1, "bad manifest did not recover previous v1");
        Checkpoint.write(dir, good.data, good.generation);
        check(Checkpoint.read(dir).data.formatVersion == 2, "could not save after manifest recovery");
        try (DimensionState loaded = DimensionState.load(dir, world())) {
            loaded.resumeReadyCubes();
            drain(loaded);
            check(
                loaded.restartMetrics.get("dimension_to_first_ready_ms")
                    >= loaded.restartMetrics.get("checkpoint_read_ms")
                        + loaded.restartMetrics.get("checkpoint_decode_ms"),
                "dimension startup omitted checkpoint IO");
        }
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw);
        out.writeInt(RecordCodec.MAX + 1);
        out.writeInt(1);
        out.writeInt(0);
        out.writeByte(0);
        out.close();
        boolean rejected = false;
        try {
            RecordCodec.read(new DataInputStream(new ByteArrayInputStream(raw.toByteArray())));
        } catch (IOException expected) {
            rejected = true;
        }
        check(rejected, "oversized record accepted");
        raw.reset();
        RecordCodec.write(new DataOutputStream(raw), new byte[] { 1, 2, 3 });
        byte[] coded = raw.toByteArray();
        coded[11] ^= 1;
        rejected = false;
        try {
            RecordCodec.read(new DataInputStream(new ByteArrayInputStream(coded)));
        } catch (IOException expected) {
            rejected = true;
        }
        check(rejected, "record CRC ignored");
    }

    static void nodeBudget() throws Exception {
        SparseTests.World w = world();
        byte[] air = new byte[4096];
        for (int z = 0; z < 16; z++) for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) air[x + 16 * y + 256 * z] = (byte) (((x + y + z) & 1) == 0 ? 1 : 0);
        w.change(CUBE, air);
        Path cache = Files.createTempDirectory(Paths.get("build"), "many-local-nodes-");
        Checkpoint.Data data = seed(cache, w);
        System.setProperty("smog.fastRestoreMaxNodes", "512");
        try (DimensionState s = restore(data, cache, w, true, false)) {
            drain(s);
            check(s.nodeCount() == 2048, "checkerboard local nodes");
            check(s.restartMetrics.get("fast_restore_batches") == 4, "node cap did not shrink plan");
            check(s.restartMetrics.get("fast_restore_cells") == 8, "node-limited restore fell back");
            near(s.balanceError(), 0, "node-limited ledger");
        } finally {
            System.clearProperty("smog.fastRestoreMaxNodes");
        }
    }

    static void canonicalFingerprint() {
        byte[] air = new byte[4096], atmosphere = new byte[4096];
        Random random = new Random(1769);
        random.nextBytes(air);
        for (int i = 0; i < air.length; i++) atmosphere[i] = (byte) (i % 64);
        java.security.MessageDigest expected = RecordCodec.digest();
        expected.update((byte) 1);
        for (byte b : air) expected.update((byte) (b == 0 ? 0 : 1));
        expected.update(atmosphere);
        ImmutableCubeSnapshot snapshot = new ImmutableCubeSnapshot(CUBE, 1, 1, air, atmosphere);
        check(Arrays.equals(expected.digest(), snapshot.fingerprint()), "bulk SHA changed semantic hash");
        check(
            Arrays
                .equals(snapshot.fingerprint(), new ImmutableCubeSnapshot(CUBE, 999, 7, air, atmosphere).fingerprint()),
            "fingerprint depends on process revisions");
    }

    public static void main(String[] args) throws Exception {
        Path cache = Files.createTempDirectory(Paths.get("build"), "geometry-cache-");
        Checkpoint.Data data = seed(cache, world());
        canonicalFingerprint();
        atomicAndHalo(cache, data);
        epsilon(cache, data);
        corruption(cache, data);
        directAndFallback(cache, data);
        randomCachedDifferential();
        continuousRestore();
        formatBoundsAndManifest(data);
        nodeBudget();
        System.out.println("PASS FastColdRestartTests checks=" + checks);
    }
}

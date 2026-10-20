package smog;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Arrays;

/** Executable cold restoration, final-node epsilon and lifecycle regressions. */
public final class ColdExactMassTests {

    private static int checks;
    private static final boolean FAST = Boolean.parseBoolean(System.getProperty("smog.coldExactMass", "true"));
    private static final CellKey KEY = new CellKey(0, 0, 0, 0);
    private static final SmogConfig CONFIG = SmogConfig.builder()
        .mode(SmogConfig.COMPONENT)
        .build();

    private static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }

    private static void near(double actual, double expected, String why) {
        check(
            Math.abs(actual - expected) <= 2e-12 * Math.max(Math.abs(expected), 1.0),
            why + ": " + actual + " != " + expected);
    }

    private static SparseTests.World world(CellKey key, boolean wall, boolean solid) {
        SparseTests.World w = new SparseTests.World();
        byte[] air = SparseTests.air();
        if (solid) Arrays.fill(air, (byte) 0);
        if (wall) {
            for (int z = 0; z < 8; z++) for (int y = 0; y < 8; y++) {
                air[4 + 16 * y + 256 * z] = 0;
            }
        }
        w.put(key.cube(), air, 1, 1);
        return w;
    }

    private static CellBuild complete(DimensionState s, DimensionState.Cell c) {
        CellBuild b = new CellBuild(s, c);
        for (int n = 0; !b.ready && n < 100000; n++) b.advance();
        check(b.ready && b.validWorld(), "fixture candidate");
        return b;
    }

    private static Checkpoint.Data data(CellKey key, boolean oldWall, double... masses) {
        SparseTests.World w = world(key, oldWall, false);
        Checkpoint.Data data = new Checkpoint.Data();
        data.dimension = 0;
        data.config = CONFIG;
        data.parts = 20;
        try (DimensionState seed = new DimensionState(0, CONFIG, w)) {
            CellBuild b = complete(seed, seed.cell(key, true));
            check(b.geometry.volumes.length == masses.length, "old local node count");
            Checkpoint.CellData d = new Checkpoint.CellData();
            d.key = key;
            d.mass = new DimensionState.MassRecord(b.geometry.labels, b.geometry.volumes.clone(), masses.clone());
            data.cells.add(d);
            for (double m : masses) data.injected += m;
        }
        return data;
    }

    private static DimensionState restored(Checkpoint.Data d, SparseTests.World w) {
        DimensionState s = Checkpoint.restore(d, w);
        check(s.nodeCount() == 0, "restore must not build geometry");
        s.resumeReadyCubes();
        SparseTests.drain(s);
        near(s.balanceError(), 0, "ledger after restore");
        return s;
    }

    private static void exactThresholds() throws Exception {
        for (double mass : new double[] { 0, Double.MIN_VALUE, 0.25, Math.nextDown(1.0), 1, Math.nextUp(1.0), 100,
            1e15 }) {
            try (DimensionState s = restored(data(KEY, false, mass), world(KEY, false, false))) {
                GeometryMetrics m = s.geometryMetrics;
                double retained = mass < 1 ? 0 : mass;
                check(s.liveMass() == retained, "exact final threshold, including exactly one and subnormal");
                check(
                    m.epsilonDroppedMass == (mass > 0 && mass < 1 ? mass : 0),
                    "exact epsilon ledger including subnormal");
                check(m.coldExactCount == (FAST ? 1 : 0), "checkpoint-only exact count");
                check(m.coldFallbackCount == (FAST ? 0 : 1), "fallback switch");
                check(m.coldPublishCount == 1 && m.replacementPublishCount == 0, "publication classes");
                near(m.coldRestoredMass, retained, "cold restored mass");
                check(m.coldExactMassMs >= 0 && m.coldFallbackMassMs >= 0, "mass timers");
                near(s.removedMass(), m.epsilonDroppedMass, "persisted removal ledger");
            }
        }
    }

    private static void remapCases() throws Exception {
        // Per-voxel contributions (100/512) are below one, but the final node is not.
        Checkpoint.Data mismatch = data(KEY, false, 100);
        mismatch.cells.get(0).mass.volumes[0] = 511;
        try (DimensionState s = restored(mismatch, world(KEY, false, false))) {
            near(s.liveMass(), 100, "volumes mismatch must fall back without intermediate pruning");
            check(s.geometryMetrics.coldExactCount == 0 && s.geometryMetrics.coldFallbackCount == 1, "volumes guard");
        }
        for (double mass : new double[] { 1.5, 100, 1e200 }) {
            try (DimensionState s = restored(data(KEY, false, mass), world(KEY, true, false))) {
                check(
                    s.geometryMetrics.coldExactCount == 0 && s.geometryMetrics.coldFallbackCount == 1,
                    "split fallback");
                if (mass == 1.5) {
                    check(s.liveMass() == 0, "two sub-threshold final nodes may be removed");
                    near(s.geometryMetrics.epsilonDroppedMass, 1.5, "split dropped total");
                } else {
                    near(s.liveMass(), mass, "large split mass");
                    check(s.geometryMetrics.epsilonDroppedMass == 0, "large split must not prune voxel shares");
                }
            }
        }
        try (DimensionState s = restored(data(KEY, true, 0.6, 0.6), world(KEY, false, false))) {
            near(s.liveMass(), 1.2, "merge small old nodes before pruning");
            check(s.geometryMetrics.epsilonDroppedMass == 0, "merged final mass survives");
        }
        try (DimensionState s = restored(data(KEY, false, 100), world(KEY, false, true))) {
            check(s.liveMass() == 0, "solid cell has no active mass");
            near(s.trappedMass(), 100, "zero survivors trap all mass");
            check(s.geometryMetrics.epsilonDroppedMass == 0, "trapped mass is not pruned");
        }
        // Same local geometry with exchanged local IDs must not use an index copy.
        Checkpoint.Data permuted = data(KEY, true, 10, 20);
        DimensionState.MassRecord old = permuted.cells.get(0).mass;
        int[] labels = old.labels();
        for (int p = 0; p < 512; p++) if (labels[p] >= 0) labels[p] ^= 1;
        permuted.cells.get(0).mass = new DimensionState.MassRecord(
            labels,
            new int[] { old.volumes[1], old.volumes[0] },
            new double[] { 20, 10 });
        try (DimensionState s = restored(permuted, world(KEY, true, false))) {
            check(s.geometryMetrics.coldExactCount == 0, "local numbering guard");
            near(s.liveMass(), 30, "permuted numbering mass");
        }
    }

    private static void deferredContributions() throws Exception {
        for (int kind = 0; kind < 4; kind++) {
            Checkpoint.Data d = data(KEY, false, 0.6);
            Checkpoint.CellData c = d.cells.get(0);
            if (kind == 0) {
                c.trapped.put(0, 0.6);
                d.injected += 0.6;
            }
            if (kind == 1) c.waiting.put(0, 0.6);
            if (kind >= 2) {
                DimensionState.Event e = new DimensionState.Event();
                e.add = 0.6;
                e.remove = kind == 2 ? 0.1 : 0.7;
                c.events.put(0, e);
            }
            try (DimensionState s = restored(d, world(KEY, false, false))) {
                double expected = kind == 2 ? 1.1 : kind == 3 ? 0 : 1.2;
                near(s.liveMass(), expected, "all ready contributions before epsilon");
                near(s.geometryMetrics.epsilonDroppedMass, kind == 3 ? 0.5 : 0, "post-removal epsilon");
                near(s.balanceError(), 0, "deferred ledger");
            }
        }
        Checkpoint.Data frames = data(KEY, false, 0.6);
        BlockPos point = KEY.block(0);
        frames.sources.add(new DimensionState.Source(point, 4, 0));
        frames.frames.add(new DimensionState.Frame(new BlockPos[] { point }, new double[] { 2 }, 0.25));
        try (DimensionState s = restored(frames, world(KEY, false, false))) {
            check(s.sources.get(point).node >= 0, "restored source must bind");
            check(s.pendingIntervals() == 1, "publication must not consume an emission frame");
            near(s.pendingMass(), 2.25, "pending and rejected emission mass");
            for (int i = 0; s.pendingIntervals() != 0 && i < 100; i++) s.finishPendingPart(20);
            check(s.pendingIntervals() == 0, "emission frame must drain");
            near(s.liveMass(), 2, "emission delivered exactly once");
            near(s.rejected, 0.25, "rejected frame accounting");
            near(s.balanceError(), 0, "source frame balance");
        }
    }

    private static void checkpointAndReset() throws Exception {
        SparseTests.World w = world(KEY, false, false);
        try (DimensionState s = restored(data(KEY, false, 0.25), w)) {
            Path dir = Files.createTempDirectory(Paths.get("build"), "cold-epsilon-save-");
            SparseTests.save(s, dir);
            GeometryMetrics m = s.geometryMetrics;
            check(m.epsilonDroppedMass == 0.25, "before diagnostics reset");
            m.reset();
            check(
                m.epsilonDroppedMass == 0 && m.coldExactCount == 0 && m.coldRestoredMass == 0,
                "reset transient counters only");
            check(s.removedMass() == 0.25 && s.balanceError() == 0, "reset must keep ledger");
            try (DimensionState loaded = DimensionState.load(dir, w)) {
                near(loaded.removedMass(), 0.25, "checkpoint persists permitted removals");
                near(loaded.balanceError(), 0, "checkpoint preserves epsilon balance");
            }
        }
        // SAFE CUT must retain a not-yet-published record, including waiting mass.
        Checkpoint.Data d = data(KEY, false, 0.6);
        d.cells.get(0).waiting.put(0, 0.6);
        try (DimensionState s = Checkpoint.restore(d, w)) {
            Path dir = Files.createTempDirectory(Paths.get("build"), "cold-before-publish-");
            SparseTests.save(s, dir);
            try (DimensionState loaded = DimensionState.load(dir, w)) {
                loaded.resumeReadyCubes();
                SparseTests.drain(loaded);
                near(loaded.liveMass(), 1.2, "save before publication keeps pending contributions");
                near(loaded.balanceError(), 0, "SAFE CUT balance");
            }
        }
    }

    private static void lifecycleAndStale() throws Exception {
        SparseTests.World w = world(KEY, false, false);
        try (DimensionState s = restored(data(KEY, false, 100), w)) {
            long exact = s.geometryMetrics.coldExactCount;
            w.unload(s, KEY.cube());
            near(s.parkedMass(), 100, "unload parks current mass");
            w.load(s, KEY.cube());
            SparseTests.drain(s);
            near(s.liveMass(), 100, "reload retains mass");
            check(
                s.geometryMetrics.coldExactCount == exact && s.geometryMetrics.coldPublishCount == 1,
                "runtime reload is not first checkpoint publication");
            s.invalidateBlocks(Arrays.asList(KEY.block(0)));
            SparseTests.drain(s);
            check(
                s.geometryMetrics.replacementPublishCount > 0 && s.geometryMetrics.coldExactCount == exact,
                "active replacement must not enter cold exact");
            near(s.balanceError(), 0, "replacement balance");
        }
        Field field = DimensionState.class.getDeclaredField("prepared");
        field.setAccessible(true);
        for (boolean unload : new boolean[] { false, true }) {
            SparseTests.World scene = world(KEY, false, false);
            try (DimensionState s = Checkpoint.restore(data(KEY, false, 100), scene)) {
                DimensionState.Cell c = s.cell(KEY, false);
                c.building = true;
                CellBuild b = complete(s, c);
                @SuppressWarnings("unchecked")
                ArrayDeque<CellBuild> prepared = (ArrayDeque<CellBuild>) field.get(s);
                prepared.addLast(b);
                if (unload) {
                    scene.unload(s, KEY.cube());
                    s.geometry(2000000L);
                    check(
                        s.geometryMetrics.coldPublishCount == 0 && s.nodeCount() == 0,
                        "unload cancels prepared candidate");
                    near(s.parkedMass(), 100, "cancellation keeps parked mass");
                    scene.load(s, KEY.cube());
                } else {
                    byte[] changed = SparseTests.air();
                    for (int z = 0; z < 8; z++) for (int y = 0; y < 8; y++) changed[4 + 16 * y + 256 * z] = 0;
                    scene.change(KEY.cube(), changed);
                }
                SparseTests.drain(s);
                near(s.liveMass(), 100, "stale candidate rebuild");
                check(
                    s.geometryMetrics.coldPublishCount == 1 && s.staleJobs > 0,
                    "stale candidate must not publish twice");
                if (!unload) check(s.geometryMetrics.coldExactCount == 0, "changed world uses remap");
            }
        }
        CellKey negative = new CellKey(0, -1, -1, -1);
        try (DimensionState s = restored(data(negative, false, 100), world(negative, false, false))) {
            near(s.liveMass(), 100, "negative and vertical coordinates");
        }
    }

    public static void main(String[] args) throws Exception {
        exactThresholds();
        remapCases();
        deferredContributions();
        checkpointAndReset();
        lifecycleAndStale();
        System.out.println("SMOG_COLD_EXACT_MASS_PASS checks=" + checks + " exactEnabled=" + FAST);
    }
}

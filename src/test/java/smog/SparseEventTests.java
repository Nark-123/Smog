package smog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Adversarial event ordering and recovery cases complement SparseTests.
 */
public final class SparseEventTests {

    static void yes(boolean b, String m) {
        SparseTests.yes(b, m);
    }

    static void near(double a, double b, String m) {
        SparseTests.near(a, b, 1e-10, m);
    }

    static void sourceIntervals() throws Exception {
        SparseTests.World w = new SparseTests.World();
        CubeKey k = new CubeKey(0, 0, 0, 0);
        w.put(k, SparseTests.air(), 1, 1);
        BlockPos p = new BlockPos(0, 1, 1, 1);
        try (DimensionState s = new DimensionState(0, SmogConfig.throat8(), w)) {
            s.setSourceRate(p, 2);
            for (int i = 0; i < 13; i++) {
                s.tick(0);
            }
            s.setSourceRate(p, 0);
            near(s.pendingMass(), 1.3, "Rate stop midinterval");
            yes(s.nodeCount() == 0, "Zero budget built geometry");
            Path path = Files.createTempDirectory(Paths.get("build"), "pending-event-");
            SparseTests.save(s, path);
            try (DimensionState loaded = DimensionState.load(path, w)) {
                near(loaded.pendingMass(), 1.3, "Partial window save");
                w.load(loaded, k);
                SparseTests.drain(loaded);
                for (int i = 0; i < 47; i++) {
                    loaded.tick(500000);
                }
                while (loaded.pendingIntervals() > 0) {
                    loaded.finishPendingPart(20);
                }
                near(loaded.injectedMass(), 1.3, "Partial interval credited twice");
                near(loaded.balanceError(), 0, "Partial interval ledger");
            }
        }
        try (DimensionState s = new DimensionState(0, SmogConfig.throat8(), w)) {
            s.setSourceRate(p, 2);
            SparseTests.drain(s);
            for (int i = 0; i < 25; i++) {
                s.tick(0);
            }
            yes(s.graph.isStepInProgress(), "No partial candidate");
            w.unload(s, k);
            s.setSourceRate(p, 0);
            for (int i = 0; i < 35; i++) {
                s.tick(0);
            }
            while (s.pendingIntervals() > 0) {
                s.finishPendingPart(20);
            }
            near(s.generatedMass(), 2.5, "Unload emitted more mass");
            w.change(k, SparseTests.air());
            w.load(s, k);
            SparseTests.drain(s);
            s.finishPendingPart(20);
            near(s.injectedMass(), 2.5, "Replay lost/doubled accepted mass");
            near(s.balanceError(), 0, "Partial unload ledger");
            int count = s.cellCount();
            long generation = s.generation;
            s.onWorldCubeUnloading(k, 1);
            yes(s.cellCount() == count && s.generation == generation, "Old unload removed new epoch");
        }
    }

    static void readinessAndMerge() {
        SparseTests.World w = new SparseTests.World();
        for (int z = 0; z < 4; z++) {
            for (int x = 0; x < 4; x++) {
                w.put(new CubeKey(0, x, 0, z), SparseTests.air(), 1, 1);
            }
        }
        w.ready.removeIf(k -> k.x >= 2 || k.z >= 2);
        try (DimensionState s = new DimensionState(0, SmogConfig.throat8(), w)) {
            s.queueAddMass(new BlockPos(0, 31, 4, 31), 100);
            SparseTests.drain(s);
            s.finishPendingPart(20);
            SparseTests.drain(s);
            int before = s.cellCount();
            for (CubeKey k : w.all.keySet()) {
                if (!w.ready.contains(k)) {
                    w.load(s, k);
                }
            }
            yes(s.cellCount() == before, "4->16 event built immediately");
            s.geometry(1);
            yes(s.cellCount() < 128, "4->16 eagerly built all cells");
            SparseTests.drain(s);
            yes(s.cellCount() < 128, "Front recursively filled clean region");
            near(s.balanceError(), 0, "Expansion ledger");
        }
        int nx = 24, ny = 8, nz = 8;
        byte[] a = new byte[nx * ny * nz];
        Arrays.fill(a, (byte) 1);
        for (int z = 0; z < nz; z++) {
            for (int y = 0; y < ny; y++) {
                a[15 + nx * y + nx * ny * z] = 0;
            }
        }
        w = SparseTests.world(a, nx, ny, nz, 0);
        try (DimensionState s = new DimensionState(0, SmogConfig.throat8(), w)) {
            SparseTests.full(s, nx, ny, nz);
            BlockPos left = new BlockPos(0, 9, 3, 3), right = new BlockPos(0, 20, 3, 3);
            s.queueAddMass(left, 10);
            s.queueAddMass(right, 100);
            s.finishPendingPart(20);
            double concentration = s.sample(right);
            int edges = s.edgeCount();
            a[15 + nx * 3 + nx * ny * 3] = 1;
            SparseTests.World updated = SparseTests.world(a, nx, ny, nz, 0);
            CubeKey k = new CubeKey(0, 0, 0, 0);
            ImmutableCubeSnapshot next = updated.all.get(k);
            byte[] bytes = new byte[4096];
            for (int p = 0; p < 4096; p++) {
                bytes[p] = (byte) (next.airAt(p % 16, p / 16 % 16, p / 256) ? 1 : 0);
            }
            w.change(k, bytes);
            s.invalidateBlocks(Collections.singletonList(new BlockPos(0, 15, 3, 3)));
            SparseTests.drain(s);
            yes(s.edgeCount() > edges, "Late factory connection absent");
            near(s.sample(right), concentration, "Factories mixed during publication");
            near(s.liveMass(), 110, "Factory merge mass");
            near(s.balanceError(), 0, "Factory ledger");
        }
    }

    static void saveInterleave() throws Exception {
        SparseTests.World w = new SparseTests.World();
        CubeKey k = new CubeKey(0, 0, 0, 0);
        w.put(k, SparseTests.air(), 1, 1);
        BlockPos p = new BlockPos(0, 1, 1, 1);
        try (DimensionState s = new DimensionState(0, SmogConfig.throat8(), w)) {
            s.queueAddMass(p, 10);
            SparseTests.drain(s);
            s.finishPendingPart(20);
            Path path = Files.createTempDirectory(Paths.get("build"), "capture-event-");
            CompletableFuture<Path> f = s.requestSave(path);
            s.geometry(1);
            s.queueAddMass(p, 7);
            for (int i = 0; i < 100000 && !f.isDone(); i++) {
                s.geometry(500000);
            }
            f.get(20, TimeUnit.SECONDS);
            try (DimensionState load = DimensionState.load(path, w)) {
                near(load.generatedMass(), 17, "Mutation during capture cut");
                w.load(load, k);
                SparseTests.drain(load);
                load.finishPendingPart(20);
                near(load.liveMass(), 17, "Snapshot event restore");
                near(load.balanceError(), 0, "Snapshot ledger");
            }
            // Well-checksummed but unsupported payload version must fail explicitly.
            byte[] data = Files.readAllBytes(path.resolve("generation-1.smog"));
            data[7] = 99;
            Files.write(path.resolve("generation-1.smog"), data);
            byte[] manifest = Files.readAllBytes(path.resolve("manifest"));
            System.arraycopy(
                MessageDigest.getInstance("SHA-256")
                    .digest(data),
                0,
                manifest,
                16,
                32);
            Files.write(path.resolve("manifest"), manifest);
            boolean refused = false;
            try {
                DimensionState.load(path, w);
            } catch (IOException e) {
                refused = true;
            }
            yes(refused, "Unknown checkpoint version accepted");
        }
    }

    static void recoverAndSave() throws Exception {
        SparseTests.World w = new SparseTests.World();
        CubeKey k = new CubeKey(0, 0, 0, 0);
        w.put(k, SparseTests.air(), 1, 1);
        Path path = Files.createTempDirectory(Paths.get("build"), "recovery-write-");
        try (DimensionState s = new DimensionState(0, SmogConfig.throat8(), w)) {
            s.queueAddMass(new BlockPos(0, 1, 1, 1), 9);
            SparseTests.drain(s);
            s.finishPendingPart(20);
            SparseTests.save(s, path);
            SparseTests.save(s, path);
        }
        Files.write(path.resolve("generation-2.smog"), new byte[] { 0 });
        try (DimensionState recovered = DimensionState.load(path, w)) {
            near(recovered.parkedMass(), 9, "Fallback mass");
            recovered.resumeReadyCubes();
            SparseTests.drain(recovered);
            SparseTests.save(recovered, path);
        }
        try (DimensionState check = DimensionState.load(path, w)) {
            near(check.parkedMass(), 9, "Save after fallback");
        }
    }

    static void cycles() {
        int n = 16;
        byte[] a = SparseTests.air();
        SparseTests.World w = SparseTests.world(a, n, n, n, 0);
        SmogConfig config = SmogConfig.builder()
            .cycle(7, 3, 2)
            .build();
        try (DimensionState s = new DimensionState(0, config, w)) {
            SparseTests.full(s, n, n, n);
            SmogGraph g = new SmogGraph(a, n, n, n, config);
            int[] positions = { g.index(1, 1, 1), g.index(9, 1, 1) };
            g.setSources(positions, new double[] { 2, 3 });
            BlockPos p = new BlockPos(0, 1, 1, 1), q = new BlockPos(0, 9, 1, 1);
            s.setSourceRate(p, 2, 0);
            s.setSourceRate(q, 3, (int) (2654435761L % 7));
            for (int i = 0; i < 500; i++) {
                if (i == 13 || i == 137) {
                    double rate = i == 13 ? 4 : 1;
                    s.setSourceRate(p, rate);
                    g.setSourceRate(0, rate);
                }
                s.tick(0, 20, true);
                g.stepPart(20, true);
            }
            while (s.pendingIntervals() > 0) {
                s.finishPendingPart(20);
            }
            while (g.pendingIntervals() > 0) {
                g.finishPendingPart(20);
            }
            near(s.injectedMass(), g.injectedMass(), "Cycle/rate accounting");
            for (int i = 0; i < a.length; i++) {
                near(s.sample(new BlockPos(0, i % n, i / n % n, i / n / n)), g.sample(i), "Cycle field");
            }
            s.queueAddMass(p, 5);
            s.queueRemoveMass(p, 1e10);
            s.finishPendingPart(20);
            yes(s.removedMass() > 0, "Removal absent");
            near(s.balanceError(), 0, "Removal ledger");
        }
        try (DimensionState s = new DimensionState(0, config, w)) {
            s.queueAddMass(new BlockPos(0, 1, 1, 1), 1e-200);
            SparseTests.drain(s);
            for (int i = 0; i < 100; i++) {
                s.tick(500000, 1, false);
            }
            yes(s.liveMass() > 0, "Tiny positive mass truncated");
        }
    }

    public static void main(String[] args) throws Exception {
        sourceIntervals();
        readinessAndMerge();
        saveInterleave();
        recoverAndSave();
        cycles();
        System.out.println("PASS SparseEventTests checks=" + SparseTests.checks);
    }
}

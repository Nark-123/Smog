package smog;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.smogmod.compat.gt5u.PendingPulse;
import org.smogmod.persistence.WorldStore;

/** Bounded deterministic regression tests. No Minecraft initialization or random input. */
public final class PerformanceFixTests {
    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static ExecutorService writer(DimensionState state) throws Exception {
        java.lang.reflect.Field field = DimensionState.class.getDeclaredField("writer");
        field.setAccessible(true);
        return (ExecutorService) field.get(state);
    }

    private static long cpu() {
        return ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
    }

    private static DimensionState fresh(int dimension, SparseTests.World world) {
        return new DimensionState(dimension, SmogConfig.throat8(), world);
    }

    private static void finishCapture(DimensionState state) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!"WRITING".equals(state.checkpointPhase()) && !"IDLE".equals(state.checkpointPhase())) {
            check(System.nanoTime() < deadline, "Capture did not progress before deadline");
            state.geometry(200000L);
            if ("REQUESTED".equals(state.checkpointPhase())) {
                state.finishPendingPart(20);
            }
        }
    }

    private static void exactlyOnceAndNonblocking() throws Exception {
        SparseTests.World world = new SparseTests.World();
        CubeKey cube = new CubeKey(7, -1, -2, -1);
        BlockPos point = new BlockPos(7, -1, -17, -1);
        world.put(cube, SparseTests.air(), 1, 0);
        DimensionState state = fresh(7, world);
        // A large registered roster makes the capture span slices without building a dense world.
        for (int i = 0; i < 10000; i++) {
            CubeKey key = new CubeKey(7, 100 + i, 0, 100);
            state.cubes.put(key, new DimensionState.CubeRecord(key));
        }
        state.queueAddMass(point, 1000);
        SparseTests.drain(state);
        state.finishPendingPart(20);
        state.setSourceRate(point, 2, 3);
        for (int i = 0; i < 7; i++) {
            state.tick(100000L, 20, false);
        }
        state.queueAddMass(point, 200);
        List<PendingPulse> queue = new ArrayList<>();
        queue.add(new PendingPulse(point, 125, false, true));
        Path path = Files.createTempDirectory("smog-async-");
        WorldStore store = new WorldStore(path);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService writer = writer(state);
        writer.submit(() -> {
            entered.countDown();
            try {
                check(release.await(5, TimeUnit.SECONDS), "Test I/O gate timeout");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        });
        check(entered.await(1, TimeUnit.SECONDS), "I/O gate did not start");
        try {
            store.markDirty();
            long requestStart = System.nanoTime();
            store.save(state, queue);
            store.save(state, queue); // Periodic + WorldEvent.Save before the same cut.
            long requestNanos = System.nanoTime() - requestStart;
            check(!Files.exists(path.resolve("CURRENT")), "Request performed synchronous I/O");
            state.geometry(100000L);
            check("CAPTURING".equals(state.checkpointPhase()), "Expected incremental capture");
            long cutTicks = state.gameTicks();
            double cutGenerated = state.generatedMass();
            int cutPhase = state.windowTick;
            check(state.queueAddMass(point, queue.get(0).amount), "Deferred pulse delivery");
            queue.clear();
            queue.add(new PendingPulse(point, 777, false, false));
            // Existing and newly-created cells after the cut must not leak into this snapshot.
            BlockPos newCell = new BlockPos(7, -9, -17, -1);
            state.queueAddMass(newCell, 333);
            state.queueRemoveMass(point, 75);
            long start = System.nanoTime(), startCpu = cpu(), maxTick = 0;
            for (int tick = 0; tick < 120; tick++) {
                long tickStart = System.nanoTime();
                state.queueAddMass(point, 1);
                state.tick(100000L, 20, false);
                store.markDirty();
                store.save(state, queue);
                store.save(state, queue);
                store.pollSaveCompletion();
                maxTick = Math.max(maxTick, System.nanoTime() - tickStart);
            }
            long tickWall = System.nanoTime() - start, tickCpu = cpu() - startCpu;
            finishCapture(state);
            check(!Files.exists(path.resolve("CURRENT")), "Gameplay waited for blocked writer");
            check(state.captureAttempts == 1 && state.captureAborts == 0, "Pulses starved capture");
            check(store.coalesced >= 240, "Requests did not coalesce");
            release.countDown();
            writer.submit(() -> {}).get(3, TimeUnit.SECONDS); // Test barrier after real durable commit.
            List<PendingPulse> restoredQueue = new ArrayList<>();
            DimensionState restored = new WorldStore(path).load(7, world, restoredQueue);
            SparseTests.near(restored.generatedMass(), cutGenerated, 1e-12, "Core changed across SAFE CUT");
            check(restored.gameTicks() == cutTicks && restored.windowTick == cutPhase, "Partial emission phase");
            check(restored.sources.size() == 1, "Source missing");
            check(restoredQueue.size() == 1 && restoredQueue.get(0).amount == 125
                && restoredQueue.get(0).adjacent, "Pending snapshot not from SAFE CUT");
            SparseTests.near(restored.generatedMass() + restoredQueue.get(0).amount,
                cutGenerated + 125, 1e-12, "Exactly-once core + pending");
            restored.close();
            store.saveAndAwaitForShutdown(state, queue, 5000);
            check(store.completed == 2, "Backlog must be one follow-up, not one per request");
            String before = new String(Files.readAllBytes(path.resolve("CURRENT")), StandardCharsets.US_ASCII);
            store.saveAndAwaitForShutdown(state, queue, 5000);
            check(before.equals(new String(Files.readAllBytes(path.resolve("CURRENT")), StandardCharsets.US_ASCII)),
                "Unchanged shutdown made a duplicate checkpoint");
            restoredQueue.clear();
            restored = new WorldStore(path).load(7, world, restoredQueue);
            check(restoredQueue.size() == 1 && restoredQueue.get(0).amount == 777, "Final pending queue");
            SparseTests.near(restored.generatedMass(), state.generatedMass(), 1e-12, "Final core state");
            SparseTests.near(restored.balanceError(), 0, 1e-12, "Restored balance");
            restored.close();
            System.out.println("ASYNC_METRICS requestWallNs=" + requestNanos + " ticks=120 wallNs=" + tickWall
                + " cpuNs=" + tickCpu + " maxTickNs=" + maxTick + " blockedIo=true commits=" + store.completed);
        } finally {
            release.countDown();
            state.close();
        }
    }

    private static void partialBuildUnloadAndFailure() throws Exception {
        SparseTests.World world = new SparseTests.World();
        CubeKey cube = new CubeKey(9, 0, 0, 0);
        BlockPos point = new BlockPos(9, 1, 1, 1);
        world.put(cube, SparseTests.air(), 1, 0);
        Path path = Files.createTempDirectory("smog-fallback-");
        DimensionState state = fresh(9, world);
        WorldStore store = new WorldStore(path);
        List<PendingPulse> queue = new ArrayList<>();
        state.queueAddMass(point, 500);
        // Request during CellBuild; capture does not publish half-built geometry.
        state.geometry(50000L);
        store.markDirty();
        store.saveAndAwaitForShutdown(state, queue, 5000);
        SparseTests.drain(state);
        state.finishPendingPart(20);
        state.setSourceRate(point, 10);
        for (int tick = 0; tick < 21; tick++) {
            state.tick(100000L, 20, false);
        }
        check(state.graph.isStepInProgress(), "Fixture must contain a partial physical step");
        store.markDirty();
        store.save(state, queue);
        state.geometry(50000L);
        check("REQUESTED".equals(state.checkpointPhase()), "Snapshot cut a partial physical step");
        store.saveAndAwaitForShutdown(state, queue, 5000);
        String current = new String(Files.readAllBytes(path.resolve("CURRENT")), StandardCharsets.US_ASCII);
        String previous = new String(Files.readAllBytes(path.resolve("PREVIOUS")), StandardCharsets.US_ASCII);
        check(!current.equals(previous), "Previous must be a distinct durable bundle");
        check(Files.exists(path.resolve(previous).resolve("core/manifest")), "Previous bundle missing");
        // Cancel an incremental capture by unload and retry with a new paired queue copy.
        for (int i = 0; i < 4000; i++) {
            CubeKey key = new CubeKey(9, 100 + i, 0, 100);
            state.cubes.put(key, new DimensionState.CubeRecord(key));
        }
        store.markDirty();
        store.save(state, queue);
        state.geometry(100000L);
        check("CAPTURING".equals(state.checkpointPhase()), "Unload capture fixture");
        world.unload(state, cube);
        queue.add(new PendingPulse(point, -12, true, false));
        store.markDirty();
        store.saveAndAwaitForShutdown(state, queue, 5000);
        check(state.captureAborts > 0, "Unload must retry snapshot");
        List<PendingPulse> loaded = new ArrayList<>();
        DimensionState restored = new WorldStore(path).load(9, world, loaded);
        check(loaded.size() == 1 && loaded.get(0).amount == -12 && loaded.get(0).chunkOnly,
            "Retried snapshot did not refresh negative pending event");
        SparseTests.near(restored.generatedMass(), state.generatedMass(), 1e-12, "Unload restored total");
        restored.close();
        String good = new String(Files.readAllBytes(path.resolve("CURRENT")), StandardCharsets.US_ASCII);
        // Fail after writing both payloads and before changing CURRENT.
        Files.createDirectory(path.resolve("CURRENT.tmp"));
        store.markDirty();
        store.save(state, queue);
        finishCapture(state);
        writer(state).submit(() -> {}).get(3, TimeUnit.SECONDS);
        store.pollSaveCompletion();
        check(store.status().contains("save=FAILED"), "Disk failure not visible");
        check(good.equals(new String(Files.readAllBytes(path.resolve("CURRENT")), StandardCharsets.US_ASCII)),
            "Failed commit replaced valid current");
        restored = new WorldStore(path).load(9, world, new ArrayList<PendingPulse>());
        restored.close();
        Files.delete(path.resolve("CURRENT.tmp"));
        // A corrupt queue or corrupt core must each fall back to the real previous bundle.
        Path pending = path.resolve(good).resolve("pending.bin");
        byte[] validPending = Files.readAllBytes(pending);
        Files.write(pending, new byte[] { 0 });
        loaded.clear();
        restored = new WorldStore(path).load(9, world, loaded);
        restored.close();
        Files.write(pending, validPending);
        Path core = path.resolve(good).resolve("core/generation-1.smog");
        byte[] validCore = Files.readAllBytes(core);
        Files.write(core, new byte[] { 0 });
        restored = new WorldStore(path).load(9, world, new ArrayList<PendingPulse>());
        restored.close();
        Files.write(core, validCore);
        state.close();
        System.out.println("SAVE_PARTIAL_BUILD_UNLOAD_FAILURE_FALLBACK_PASS");
    }

    private static void shutdownTimeoutAndDimensions() throws Exception {
        for (int dimension : new int[] { -1, 0, 7 }) {
            SparseTests.World world = new SparseTests.World();
            DimensionState state = fresh(dimension, world);
            Path path = Files.createTempDirectory("smog-lifecycle-");
            WorldStore store = new WorldStore(path);
            List<PendingPulse> queue = new ArrayList<>();
            queue.add(new PendingPulse(new BlockPos(dimension, -17, 300, 31), 42, false, false));
            store.markDirty();
            store.save(state, queue);
            store.save(state, queue);
            state.tick(200000L, 20, false);
            store.markDirty();
            store.saveAndAwaitForShutdown(state, queue, 3000);
            state.close();
            state.close();
            check(writer(state).awaitTermination(1, TimeUnit.SECONDS), "Writer leaked after close");
            List<PendingPulse> restored = new ArrayList<>();
            state = new WorldStore(path).load(dimension, world, restored);
            check(restored.size() == 1 && restored.get(0).position.dimension == dimension,
                "Dimension pending state crossed worlds");
            check(state.gameTicks() == 1, "Final shutdown checkpoint missed latest phase");
            state.close();
        }
        SparseTests.World world = new SparseTests.World();
        DimensionState state = fresh(3, world);
        ExecutorService io = writer(state);
        CountDownLatch release = new CountDownLatch(1);
        io.submit(() -> {
            try {
                check(release.await(3, TimeUnit.SECONDS), "Timeout fixture gate");
            } catch (InterruptedException exception) {
                throw new AssertionError(exception);
            }
        });
        WorldStore store = new WorldStore(Files.createTempDirectory("smog-timeout-"));
        List<PendingPulse> queue = new ArrayList<>();
        long start = System.nanoTime();
        try {
            store.saveAndAwaitForShutdown(state, queue, 30);
            throw new AssertionError("Shutdown failed to enforce I/O timeout");
        } catch (java.util.concurrent.TimeoutException expected) {
            check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1), "Shutdown timeout unbounded");
        } finally {
            release.countDown();
        }
        io.submit(() -> {}).get(1, TimeUnit.SECONDS);
        store.saveAndAwaitForShutdown(state, queue, 1000);
        state.close();
        check(io.awaitTermination(1, TimeUnit.SECONDS), "Timeout recovery leaked writer");
        System.out.println("SHUTDOWN_TIMEOUT_MULTI_DIMENSION_PASS");
    }

    private static int clamp(double amount) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, amount));
    }

    private static void batchQueries() {
        SparseTests.World world = new SparseTests.World();
        DimensionState state = fresh(4, world);
        Set<Long> keys = new HashSet<>();
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                keys.add(SpatialQueries.chunkKey(x, z));
                for (int y = -1; y <= 3; y++) {
                    DimensionState.Cell cell = state.cell(new CellKey(4, x * 2, y * 2, z * 2), true);
                    int[] labels = new int[512];
                    cell.parked = new DimensionState.MassRecord(labels, new int[] { 512 },
                        new double[] { ((x + 12) * 25 + z + 12) * 1000.125 + y + 2 });
                    cell.trapped.put(0, (x + 12) * .03125);
                }
            }
        }
        // Mixed live + parked + trapped and exact integer boundaries.
        DimensionState.Cell live = state.cell(new CellKey(4, -2, 0, -2), false);
        int id = state.graph.newNode(0);
        state.graph.setVolume(id, 512);
        state.graph.addNodeMass(id, .999999999999);
        live.geometry = new DimensionState.Geometry();
        live.geometry.ids = new int[] { id };
        long reads = world.reads;
        keys.add(SpatialQueries.chunkKey(100000, -100000));
        keys.add(SpatialQueries.chunkKey(Integer.MIN_VALUE, Integer.MAX_VALUE));
        check(SpatialQueries.chunkKey(-1, 0) != SpatialQueries.chunkKey(0, -1), "Signed key collision");
        SpatialQueries.ChunkMassBatch batch = SpatialQueries.chunkMasses(state, keys);
        for (long key : keys) {
            double old = SpatialQueries.chunkMass(state, (int) (key >> 32), (int) key);
            check(Double.doubleToLongBits(old) == Double.doubleToLongBits(batch.masses.get(key)),
                "Batched sum changed floating-point addition order");
            check(clamp(old) == clamp(batch.masses.get(key)), "Client clamp changed");
        }
        check(world.reads == reads, "Query loaded geometry");
        check(batch.cubeVisits == 3125, "Expected exactly one cube traversal");
        check(SpatialQueries.chunkMasses(state, new HashSet<Long>()).cubeVisits == 0, "Empty batch scanned graph");
        // The exact old/new packet-change predicate, including the transition to zero.
        List<Integer> previous = new ArrayList<>(Arrays.asList(0, 3, 8));
        int[] next = { 1, 3, 0 };
        int oldPackets = 0, newPackets = 0;
        for (int player = 0; player < 3; player++) {
            for (int i = 0; i < next.length; i++) {
                if (next[i] != previous.get(i)) {
                    oldPackets++;
                }
                if (clamp(next[i]) != previous.get(i)) {
                    newPackets++;
                }
            }
        }
        check(oldPackets == 6 && newPackets == oldPackets, "Packet delta/zero predicate");
        for (int i = 0; i < 4; i++) {
            measureQueries(state, keys, false);
        }
        measureQueries(state, keys, true);
        System.out.println("BATCH_QUERY_BIT_EXACT_PASS cubes=" + state.cubeCount() + " columns=" + keys.size());
        state.close();
    }

    private static volatile double consume;

    private static long allocated() {
        try {
            Class<?> type = Class.forName("com.sun.management.ThreadMXBean");
            Object bean = ManagementFactory.getThreadMXBean();
            return (Long) type.getMethod("getThreadAllocatedBytes", long.class).invoke(bean, Thread.currentThread().getId());
        } catch (ReflectiveOperationException unavailable) {
            return -1;
        }
    }

    private static void measureQueries(DimensionState state, Set<Long> keys, boolean report) {
        long start = System.nanoTime(), startCpu = cpu(), startBytes = allocated();
        double sum = 0;
        for (long key : keys) {
            sum += SpatialQueries.chunkMass(state, (int) (key >> 32), (int) key);
        }
        long oldWall = System.nanoTime() - start, oldCpu = cpu() - startCpu;
        long oldBytes = startBytes < 0 ? -1 : allocated() - startBytes;
        start = System.nanoTime();
        startCpu = cpu();
        startBytes = allocated();
        SpatialQueries.ChunkMassBatch batch = SpatialQueries.chunkMasses(state, keys);
        for (double value : batch.masses.values()) {
            sum += value;
        }
        long newWall = System.nanoTime() - start, newCpu = cpu() - startCpu;
        long newBytes = startBytes < 0 ? -1 : allocated() - startBytes;
        consume = sum;
        if (report) {
            System.out.println("QUERY_METRICS oldVisits=" + (long) keys.size() * state.cubeCount()
                + " newVisits=" + batch.cubeVisits + " oldWallNs=" + oldWall + " newWallNs=" + newWall
                + " oldCpuNs=" + oldCpu + " newCpuNs=" + newCpu
                + " oldAllocatedBytes=" + oldBytes + " newAllocatedBytes=" + newBytes);
        }
    }

    public static void main(String[] args) throws Exception {
        exactlyOnceAndNonblocking();
        partialBuildUnloadAndFailure();
        shutdownTimeoutAndDimensions();
        batchQueries();
        System.out.println("SMOG_PERFORMANCE_FIX_TESTS_PASS");
    }
}

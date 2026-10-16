package smog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.smogmod.compat.gt5u.PendingPulse;
import org.smogmod.persistence.WorldStore;

/** Same bounded fixture compiled against the attached baseline and the corrected implementation. */
public final class SaveRequestBenchmark {
    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static WorldSnapshotProvider provider() {
        byte[] air = new byte[4096];
        Arrays.fill(air, (byte) 1);
        CubeKey key = new CubeKey(0, 0, 0, 0);
        ImmutableCubeSnapshot cube = new ImmutableCubeSnapshot(key, 1, 0, air);
        return new WorldSnapshotProvider() {
            public boolean isReady(CubeKey test) {
                return key.equals(test);
            }

            public ImmutableCubeSnapshot snapshotIfReady(CubeKey test) {
                check(key.equals(test), "Unloaded cube read");
                return cube;
            }
        };
    }

    public static void main(String[] args) throws Exception {
        Path path = Paths.get(args[1]);
        Files.createDirectories(path);
        List<PendingPulse> pulses = new ArrayList<>();
        if ("load".equals(args[0])) {
            DimensionState state = new WorldStore(path).load(0, provider(), pulses);
            check(pulses.size() == 2 && pulses.get(0).amount == 123 && pulses.get(1).amount == -19,
                "Legacy pending queue changed");
            check(state.gameTicks() == 7 && state.windowTick == 7 && state.sources.size() == 1,
                "Legacy clocks/sources changed");
            double expected = 500 + 4 * state.config.timeStep * 7 / 20;
            check(Math.abs(state.generatedMass() - expected) < 1e-10, "Legacy mass changed");
            state.resumeReadyCubes();
            int attempts = 0;
            while (state.geometryPending() && attempts++ < 1000) {
                state.geometry(2000000L);
            }
            check(!state.geometryPending(), "Legacy geometry did not restore");
            System.out.println("LEGACY_SAVE_COMPATIBILITY_PASS generated=" + state.generatedMass()
                + " cubes=" + state.cubeCount() + " cells=" + state.cellCount() + " nodes=" + state.nodeCount()
                + " edges=" + state.edgeCount() + " live=" + state.liveMass() + " parked=" + state.parkedMass()
                + " trapped=" + state.trappedMass() + " balance=" + state.balanceError());
            state.close();
            return;
        }
        DimensionState state = new DimensionState(0, SmogConfig.throat8(), provider());
        BlockPos point = new BlockPos(0, 1, 1, 1);
        state.queueAddMass(point, 500);
        for (int i = 0; i < 1000 && state.geometryPending(); i++) {
            state.geometry(2000000L);
        }
        state.finishPendingPart(20);
        state.setSourceRate(point, 4);
        for (int i = 0; i < 7; i++) {
            state.tick(2000000L, 20, false);
        }
        for (int i = 0; i < 1000 && state.geometryPending(); i++) {
            state.geometry(2000000L);
        }
        check(!state.geometryPending(), "Benchmark geometry did not settle");
        pulses.add(new PendingPulse(point, 123, false, true));
        pulses.add(new PendingPulse(point, -19, true, false));
        WorldStore store = new WorldStore(path);
        java.lang.reflect.Field field = DimensionState.class.getDeclaredField("writer");
        field.setAccessible(true);
        ExecutorService writer = (ExecutorService) field.get(state);
        CountDownLatch release = new CountDownLatch(1), entered = new CountDownLatch(1);
        writer.submit(() -> {
            entered.countDown();
            try {
                check(release.await(3, TimeUnit.SECONDS), "Benchmark I/O gate timeout");
            } catch (InterruptedException exception) {
                throw new AssertionError(exception);
            }
        });
        check(entered.await(1, TimeUnit.SECONDS), "Benchmark writer not started");
        Thread unlock = new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        });
        unlock.start();
        java.util.UUID.randomUUID();
        java.lang.management.ThreadMXBean cpu = java.lang.management.ManagementFactory.getThreadMXBean();
        long startCpu = cpu.getCurrentThreadCpuTime(), start = System.nanoTime();
        store.save(state, pulses);
        long wall = System.nanoTime() - start, cpuNanos = cpu.getCurrentThreadCpuTime() - startCpu;
        unlock.join(1000);
        try {
            WorldStore.class.getMethod("saveAndAwaitForShutdown", DimensionState.class, List.class, long.class)
                .invoke(store, state, pulses, 3000L);
        } catch (NoSuchMethodException baseline) {
            // Attached WorldStore.save already performed its blocking flush.
        }
        System.out.println("SAVE_REQUEST_METRICS wallNs=" + wall + " cpuNs=" + cpuNanos
            + " ioGateMs=150 generated=" + state.generatedMass() + " cubes=" + state.cubeCount()
            + " cells=" + state.cellCount() + " nodes=" + state.nodeCount() + " edges=" + state.edgeCount()
            + " live=" + state.liveMass() + " parked=" + state.parkedMass() + " trapped=" + state.trappedMass()
            + " balance=" + state.balanceError());
        state.close();
        check(writer.awaitTermination(1, TimeUnit.SECONDS), "Benchmark writer leaked");
    }
}

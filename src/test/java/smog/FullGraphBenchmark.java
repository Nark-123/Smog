package smog;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/**
 * Fully built sparse runtime, same scene/seed/sources/model time as PerfProbe part20.
 */
public final class FullGraphBenchmark {

    static final com.sun.management.ThreadMXBean MX = (com.sun.management.ThreadMXBean) ManagementFactory
        .getThreadMXBean();

    public static void main(String[] args) {
        String scene = args[0], kind = args[1];
        int nx = Integer.parseInt(args[2]), ny = Integer.parseInt(args[3]), nz = Integer.parseInt(args[4]);
        long setup = System.nanoTime();
        byte[] world = new BenchmarkScene().world(kind, nx, ny, nz);
        SparseTests.World provider = SparseTests.world(world, nx, ny, nz, 0);
        long start = System.nanoTime();
        try (DimensionState s = new DimensionState(0, SmogConfig.throat8(), provider)) {
            SparseTests.full(s, nx, ny, nz);
            long build = System.nanoTime() - start;
            setup = System.nanoTime() - setup;
            int[] positions = new BenchmarkScene().positions(world, 10000);
            for (int p : positions) {
                BlockPos pos = new BlockPos(0, p % nx, p / nx % ny, p / nx / ny);
                DimensionState.Source old = s.sources.get(pos);
                s.setSourceRate(pos, old == null ? 1 : old.rate + 1);
            }
            s.graph.seedRandom(713);
            s.injected = s.liveMass();
            for (int i = 0; i < 1000; i++) {
                s.tick(500000, 20, false);
            }
            while (s.pendingIntervals() > 0) {
                s.finishPendingPart(20);
            }
            long[] ns = new long[2000];
            double model = s.modelTime();
            long cpu = MX.getCurrentThreadCpuTime(), alloc = MX.getThreadAllocatedBytes(
                Thread.currentThread()
                    .getId());
            for (int i = 0; i < ns.length; i++) {
                long t = System.nanoTime();
                s.tick(500000, 20, false);
                ns[i] = System.nanoTime() - t;
            }
            cpu = MX.getCurrentThreadCpuTime() - cpu;
            alloc = MX.getThreadAllocatedBytes(
                Thread.currentThread()
                    .getId())
                - alloc;
            long drain = MX.getCurrentThreadCpuTime();
            while (s.pendingIntervals() > 0) {
                s.finishPendingPart(20);
            }
            drain = MX.getCurrentThreadCpuTime() - drain;
            for (int i = 0; i < ns.length; i++) {
                System.out.printf(
                    "{\"kind\":\"timing\",\"scene\":\"%s\",\"op\":\"sparse_part20\",\"tick\":%d,\"ns\":%d}%n",
                    scene,
                    i,
                    ns[i]);
            }
            Arrays.sort(ns);
            System.out.printf(
                Locale.ROOT,
                "{\"kind\":\"summary\",\"scene\":\"%s\",\"op\":\"sparse_part20\",\"p50\":%d,\"p95\":%d,\"p99\":%d,\"max\":%d,\"cpu_ns\":%d,\"drain_cpu_ns\":%d,\"allocated\":%d,\"build_ns\":%d,\"setup_ns\":%d,\"cells\":%d,\"nodes\":%d,\"edges\":%d,\"model_seconds\":%.9f,\"balance\":%.12g}%n",
                scene,
                ns[999],
                ns[1899],
                ns[1979],
                ns[1999],
                cpu,
                drain,
                alloc,
                build,
                setup,
                s.cellCount(),
                s.nodeCount(),
                s.edgeCount(),
                s.modelTime() - model,
                s.balanceError());
        }
    }
}

package smog;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

/**
 * End-to-end single-thread benchmark, to be run in multiple fresh JVM processes.
 */
public final class SmogBenchmark {

    private static volatile double blackhole;

    private SmogBenchmark() {}

    public static void main(String[] args) {
        String scene = args.length > 0 ? args[0] : "factory";
        int nx = integer(args, 1, 192), ny = integer(args, 2, 64), nz = integer(args, 3, 192);
        int warmup = integer(args, 4, 500), steps = integer(args, 5, 60), repeats = integer(args, 6, 7);
        int sourceCount = integer(args, 7, 10000), queryCount = integer(args, 8, 10000);
        if (warmup < 1 || steps < 1 || repeats < 1 || sourceCount < 0 || queryCount < 0) {
            throw new IllegalArgumentException("Invalid benchmark counts");
        }
        BenchmarkScene generator = new BenchmarkScene();
        byte[] air = generator.world(scene, nx, ny, nz);
        SmogGraph graph = new SmogGraph(air, nx, ny, nz);
        int[] sources = generator.positions(air, sourceCount), queries = generator.positions(air, queryCount);
        double[] rates = new double[sourceCount];
        Arrays.fill(rates, 1);
        graph.setSources(sources, rates);
        graph.seedRandom(123456789);
        blackhole = batch(graph, warmup, true, queries);
        graph.reset();
        graph.seedRandom(123456789);
        com.sun.management.ThreadMXBean bean = allocationBean();
        long allocatedBefore = allocated(bean);
        double checksum = 0;
        double[] full = new double[repeats];
        for (int r = 0; r < repeats; r++) {
            long start = System.nanoTime();
            double sum = batch(graph, steps, true, queries);
            full[r] = (System.nanoTime() - start) * 1e-6 / steps;
            checksum += sum;
        }
        // Includes the measurement result array; subtract its allocation by measuring a separate pass below.
        long allocatedTotal = allocated(bean) - allocatedBefore;
        double fullMass = graph.mass(), fullInjected = graph.injectedMass(), balance = graph.balanceError();
        long before = allocated(bean);
        blackhole = batch(graph, steps, true, queries);
        long hotAllocated = bean == null ? -1 : allocated(bean) - before;
        graph.setSources(new int[0], new double[0]);
        graph.reset();
        graph.seedRandom(123456789);
        graph.runSteps(warmup);
        graph.reset();
        graph.seedRandom(123456789);
        double[] kernel = new double[repeats];
        for (int r = 0; r < repeats; r++) {
            long start = System.nanoTime();
            graph.runSteps(steps);
            kernel[r] = (System.nanoTime() - start) * 1e-6 / steps;
        }
        blackhole = graph.mass();
        graph.reset();
        graph.seedRandom(123456789);
        graph.setSources(sources, rates);
        // Warm the budget path independently, including its source/sink calls.
        for (int i = 0; i < 2000; i++) {
            blackhole = budgetPart(graph, queries);
        }
        double[] budget = new double[200];
        for (int i = 0; i < budget.length; i++) {
            long start = System.nanoTime();
            double sum = budgetPart(graph, queries);
            budget[i] = (System.nanoTime() - start) * 1e-6;
            blackhole = sum;
        }
        int[] single = { graph.index(nx / 2, ny / 2, nz / 2) };
        byte[] old = { air[single[0]] }, changed = { (byte) (1 - old[0]) };
        for (int i = 0; i < 80; i++) {
            graph.patch(single, changed);
            graph.patch(single, old);
        }
        double[] patch = new double[100];
        for (int i = 0; i < patch.length; i += 2) {
            patch[i] = graph.patch(single, changed);
            patch[i + 1] = graph.patch(single, old);
        }
        int[] burst = new int[100];
        byte[] burstOld = new byte[100], burstNew = new byte[100];
        for (int i = 0; i < burst.length; i++) {
            burst[i] = generator.next(air.length);
            burstOld[i] = air[burst[i]];
            burstNew[i] = (byte) (1 - burstOld[i]);
        }
        double burstChange = graph.patch(burst, burstNew), burstRestore = graph.patch(burst, burstOld);
        for (int i = 0; i < 5; i++) {
            graph.patch(burst, burstNew);
            graph.patch(burst, burstOld);
        }
        double[] burstSteady = new double[20];
        for (int i = 0; i < burstSteady.length; i += 2) {
            burstSteady[i] = graph.patch(burst, burstNew);
            burstSteady[i + 1] = graph.patch(burst, burstOld);
        }
        System.out.println(
            "{\"runtime\":\"Java\",\"java_version\":\"" + System.getProperty("java.version")
                + "\",\"vm\":\""
                + System.getProperty("java.vm.name")
                + "\",\"scene\":\""
                + scene
                + "\",\"nx\":"
                + nx
                + ",\"ny\":"
                + ny
                + ",\"nz\":"
                + nz
                + ",\"warmup_steps\":"
                + warmup
                + ",\"measurement_steps\":"
                + steps
                + ",\"repeats\":"
                + repeats
                + ",\"sources\":"
                + sourceCount
                + ",\"queries\":"
                + queryCount
                + ",\"nodes\":"
                + graph.nodeCount()
                + ",\"edges\":"
                + graph.edgeCount()
                + ",\"build_ms\":"
                + graph.buildMillis()
                + ",\"array_bytes_after_patches\":"
                + graph.estimatedArrayBytes()
                + ",\"full_step_ms\":"
                + Arrays.toString(full)
                + ",\"kernel_step_ms\":"
                + Arrays.toString(kernel)
                + ",\"budget_part_ms\":"
                + Arrays.toString(budget)
                + ",\"patch_ms\":"
                + Arrays.toString(patch)
                + ",\"burst_100_change_ms\":"
                + burstChange
                + ",\"burst_100_restore_ms\":"
                + burstRestore
                + ",\"burst_100_steady_ms\":"
                + Arrays.toString(burstSteady)
                + ",\"checksum\":"
                + checksum
                + ",\"full_mass\":"
                + fullMass
                + ",\"full_injected\":"
                + fullInjected
                + ",\"balance_error\":"
                + balance
                + ",\"hot_loop_allocated_bytes\":"
                + hotAllocated
                + ",\"measured_batch_allocated_bytes\":"
                + allocatedTotal
                + "}");
    }

    private static int integer(String[] args, int i, int fallback) {
        return args.length > i ? Integer.parseInt(args[i]) : fallback;
    }

    private static double batch(SmogGraph graph, int steps, boolean cycling, int[] queries) {
        double sum = 0;
        for (int i = 0; i < steps; i++) {
            graph.step(cycling);
            for (int q : queries) {
                sum += graph.sample(q);
            }
        }
        return sum;
    }

    private static double budgetPart(SmogGraph graph, int[] queries) {
        graph.stepPart(20, true);
        double sum = 0;
        for (int q : queries) {
            sum += graph.sample(q);
        }
        return sum;
    }

    private static com.sun.management.ThreadMXBean allocationBean() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean)) {
            return null;
        }
        com.sun.management.ThreadMXBean extended = (com.sun.management.ThreadMXBean) bean;
        if (!extended.isThreadAllocatedMemorySupported()) {
            return null;
        }
        extended.setThreadAllocatedMemoryEnabled(true);
        return extended;
    }

    private static long allocated(com.sun.management.ThreadMXBean bean) {
        return bean == null ? -1
            : bean.getThreadAllocatedBytes(
                Thread.currentThread()
                    .getId());
    }
}

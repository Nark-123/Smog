package smog;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

/**
 * Same API-only harness runs against v1 and v2. Deliberately short revision comparison.
 */
public final class EditBenchmark {

    private static volatile boolean sampling;

    private static volatile long sampledPeak;

    public static void main(String[] args) throws Exception {
        BenchmarkScene generator = new BenchmarkScene();
        int nx = 192, ny = 64, nz = 192;
        byte[] air = generator.world("factory", nx, ny, nz);
        System.gc();
        long before = heap();
        sampledPeak = before;
        sampling = true;
        Thread sampler = new Thread(() -> {
            while (sampling) {
                sampledPeak = Math.max(sampledPeak, heap());
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampler.setDaemon(true);
        sampler.start();
        SmogGraph g = new SmogGraph(air, nx, ny, nz);
        sampledPeak = Math.max(sampledPeak, heap());
        sampling = false;
        sampler.join();
        System.gc();
        long after = heap();
        int[] sources = generator.positions(air, 10000);
        double[] rates = new double[sources.length];
        Arrays.fill(rates, 1);
        g.setSources(sources, rates);
        g.seedRandom(17);
        System.out.print(
            "{\"heap_before_build\":" + before
                + ",\"heap_after_build_gc\":"
                + after
                + ",\"sampled_peak_heap_5ms\":"
                + sampledPeak
                + ",\"build_ms\":"
                + g.buildMillis()
                + ",\"array_payload_estimate\":"
                + g.estimatedArrayBytes()
                + ",\"cases\":{");
        String[] names = { "inside", "face", "edge", "corner", "spread10", "spread100", "spread1000",
            "same_container" };
        for (int c = 0; c < names.length; c++) {
            int count = c == 4 ? 10 : c == 5 ? 100 : c == 6 ? 1000 : c == 7 ? 128 : 1;
            int[] positions = new int[count];
            byte[] old = new byte[count], changed = new byte[count];
            for (int i = 0; i < count; i++) {
                positions[i] = c < 4 ? g.index(c == 0 ? 4 : 7, c < 2 ? 4 : 7, c < 3 ? 4 : 7)
                    : c == 7 ? g.index(i % 8, 2 + i / 64, (i / 8) % 8) : generator.next(air.length);
                old[i] = air[positions[i]];
                changed[i] = (byte) (1 - old[i]);
            }
            double first = g.patch(positions, changed);
            g.patch(positions, old);
            for (int i = 0; i < 4; i++) {
                g.patch(positions, changed);
                g.patch(positions, old);
            }
            double[] times = new double[30];
            for (int i = 0; i < times.length; i += 2) {
                times[i] = g.patch(positions, changed);
                times[i + 1] = g.patch(positions, old);
            }
            if (c != 0) {
                System.out.print(',');
            }
            System.out
                .print("\"" + names[c] + "\":{\"first_ms\":" + first + ",\"times_ms\":" + Arrays.toString(times) + "}");
        }
        System.out.println("},\"balance_error\":" + g.balanceError() + "}");
    }

    private static long heap() {
        return ManagementFactory.getMemoryMXBean()
            .getHeapMemoryUsage()
            .getUsed();
    }
}

package smog;

import java.util.Arrays;

public final class QueuedEditBenchmark {

    public static void main(String[] args) {
        BenchmarkScene gen = new BenchmarkScene();
        byte[] air = gen.world("factory", 192, 64, 192);
        SmogGraph g = new SmogGraph(air, 192, 64, 192);
        g.seedRandom(17);
        int[] src = gen.positions(air, 10000);
        double[] rates = new double[10000];
        Arrays.fill(rates, 1);
        g.setSources(src, rates);
        int[] positions = new int[1000];
        byte[] values = new byte[1000];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = gen.next(air.length);
            values[i] = (byte) (1 - air[positions[i]]);
        }
        g.stepPart(20, true);
        g.queuePatch(positions, values);
        double[] times = new double[(g.pendingChanges() + 7) / 8];
        int i = 0;
        while (g.pendingChanges() != 0) {
            long t = System.nanoTime();
            g.tick(8, 20, true);
            times[i++] = (System.nanoTime() - t) * 1e-6;
        }
        if (g.iteration() != 0) {
            throw new AssertionError("Stale geometry was used for transport");
        }
        System.out.println(
            "{\"max_changes_per_tick\":8,\"times_ms\":" + Arrays.toString(times)
                + ",\"balance_error\":"
                + g.balanceError()
                + ",\"model_steps_during_drain\":"
                + g.iteration()
                + "}");
    }
}

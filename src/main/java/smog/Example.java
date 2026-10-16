package smog;

import java.util.Arrays;
import java.util.Locale;

public final class Example {

    private Example() {}

    public static void main(String[] args) {
        int nx = 64, ny = 32, nz = 64;
        byte[] air = new byte[nx * ny * nz];
        Arrays.fill(air, (byte) 1);
        SmogConfig settings = SmogConfig.builder()
            .containerSize(8)
            .mode(SmogConfig.THROAT)
            .radius(12)
            .law(SmogConfig.QUADRATIC_FAST)
            .diffusion(1e6)
            .timeStep(1)
            .build();
        SmogGraph graph = new SmogGraph(air, nx, ny, nz, settings);
        graph.setSources(new int[] { graph.index(16, 8, 32) }, new double[] { 1 });
        graph.runSteps(60);
        System.out.printf(
            Locale.ROOT,
            "nodes=%d edges=%d c(32,8,32)=%.12g balance=%.3g%n",
            graph.nodeCount(),
            graph.edgeCount(),
            graph.sample(32, 8, 32),
            graph.balanceError());
        graph.patch(new int[] { graph.index(24, 8, 32) }, new byte[] { 0 });
        // Collect one completed interval, then drain it for this offline demonstration.
        for (int tick = 0; tick < 20; tick++) {
            graph.stepPart(20, false);
        }
        while (graph.pendingIntervals() != 0) {
            graph.finishPendingPart(20);
        }
        System.out.printf(
            Locale.ROOT,
            "after edit: mass=%.12g trapped=%.12g balance=%.3g%n",
            graph.mass(),
            graph.trappedMass(),
            graph.balanceError());
    }
}

package smog;

import java.util.Arrays;

/**
 * Runs unchanged against both v1 and v2 JARs.
 */
public final class P0Probe {

    public static void main(String[] args) {
        byte[] air = new byte[128 * 64 * 128];
        Arrays.fill(air, (byte) 1);
        SmogGraph a = new SmogGraph(air, 128, 64, 128), b = new SmogGraph(air, 128, 64, 128);
        int source = a.index(64, 8, 64), probe = a.index(64, 8, 72);
        a.setSources(new int[] { source }, new double[] { 1 });
        b.setSources(new int[] { source }, new double[] { 1 });
        a.runSteps(60);
        b.runSteps(60);
        a.patch(new int[] { a.index(24, 24, 24) }, new byte[] { 1 });
        a.runSteps(60);
        b.runSteps(60);
        double error = 0, sum = 0;
        for (int p = 0; p < air.length; p++) {
            error += Math.abs(a.sample(p) - b.sample(p));
            sum += b.sample(p);
        }
        b.runSteps(180);
        double[] readings = new double[4];
        for (int i = 0; i < readings.length; i++) {
            readings[i] = b.sample(probe);
            b.step();
        }
        b.stepPart(20, false);
        boolean throwsOnPatch = false;
        try {
            b.patch(new int[] { source + 1 }, new byte[] { 0 });
        } catch (IllegalStateException e) {
            throwsOnPatch = true;
        }
        System.out.println(
            "{\"readings_300_to_303\":" + Arrays.toString(readings)
                + ",\"noop_relative_L1\":"
                + error / sum
                + ",\"partial_patch_throws\":"
                + throwsOnPatch
                + "}");
    }
}

package smog;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Random;

/**
 * Property tests for corrected law4; historical C++ agreement is deliberately not the oracle.
 */
public final class StabilityTests {

    private static int checks;

    private static double maxStepJump, permutationError;

    private static double[] repeatedTimes;

    private StabilityTests() {}

    private static void check(boolean v, String why) {
        checks++;
        if (!v) {
            throw new AssertionError(why);
        }
    }

    private static void near(double a, double b, double tolerance, String why) {
        check(
            Double.isFinite(a) && Double.isFinite(b) && Math.abs(a - b) <= tolerance * Math.max(1, Math.abs(b)),
            why + ": " + a + " / " + b);
    }

    private static byte[] air(int n) {
        byte[] a = new byte[n];
        Arrays.fill(a, (byte) 1);
        return a;
    }

    private static void healthy(SmogGraph g) {
        near(g.balanceError(), 0, 2e-10 * Math.max(1, g.injectedMass()), "ledger");
        for (double c : g.copyField()) {
            check(c >= 0 && Double.isFinite(c), "finite nonnegative field");
        }
    }

    private static SmogGraph open() {
        SmogGraph g = new SmogGraph(air(128 * 64 * 128), 128, 64, 128);
        g.setSources(new int[] { g.index(64, 8, 64) }, new double[] { 1 });
        return g;
    }

    public static void main(String[] args) throws Exception {
        temporalAndNoop();
        permutationAndSymmetry();
        concurrentChanges();
        parameterRange();
        geometryAndHistory();
        queueCoalescing();
        sourceLocality();
        repeatedPatches();
        System.out.println(
            "{\"status\":\"PASS\",\"checks\":" + checks
                + ",\"max_relative_step_jump_300_340\":"
                + maxStepJump
                + ",\"max_permutation_absolute_error\":"
                + permutationError
                + ",\"repeated_patch_count\":20000,\"repeated_patch_median_ms\":"
                + repeatedTimes[10000]
                + ",\"repeated_patch_p95_ms\":"
                + repeatedTimes[19000]
                + ",\"repeated_patch_p99_ms\":"
                + repeatedTimes[19800]
                + ",\"repeated_patch_max_ms\":"
                + repeatedTimes[19999]
                + "}");
    }

    private static void temporalAndNoop() {
        SmogGraph a = open(), b = open();
        a.runSteps(60);
        b.runSteps(60);
        int p = a.index(64, 8, 64);
        // final value is unchanged
        a.patch(new int[] { p, p, p }, new byte[] { 0, 1, 1 });
        a.runSteps(240);
        b.runSteps(240);
        check(Arrays.equals(a.copyField(), b.copyField()), "no-op is bit exact");
        int probe = a.index(64, 8, 72);
        double last = a.sample(probe);
        for (int i = 0; i < 40; i++) {
            a.step();
            double v = a.sample(probe);
            maxStepJump = Math.max(maxStepJump, Math.abs(v - last) / last);
            last = v;
        }
        check(maxStepJump < .01, "steady source must not flicker");
        healthy(a);
        for (int t = 0; t < 20; t++) {
            a.stepPart(20, false);
        }
        a.stepPart(20, false);
        a.patch(new int[] { p }, new byte[] { 1 });
        a.setSourceRate(0, 1);
        a.configure(a.diffusion(), a.timeStep());
        a.setLaw(a.law());
        check(a.isStepInProgress(), "no-op preserves pending progress");
        a.cancelStep();
    }

    private static Object field(SmogGraph g, String name) throws Exception {
        Field f = SmogGraph.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(g);
    }

    // Relabel a real graph, preserving its complete adjacency; reverse the active edge enumeration.
    private static void permute(SmogGraph g) throws Exception {
        int n = g.allocatedNodeSlots();
        for (String name : new String[] { "volume", "seed", "adjacency", "sinkSlot" }) {
            int[] a = (int[]) field(g, name);
            for (int i = 0; i < n / 2; i++) {
                int t = a[i];
                a[i] = a[n - 1 - i];
                a[n - 1 - i] = t;
            }
        }
        double[] state = (double[]) field(g, "state");
        for (int i = 0; i < n / 2; i++) {
            for (int k = 0; k < 2; k++) {
                double t = state[2 * i + k];
                state[2 * i + k] = state[2 * (n - 1 - i) + k];
                state[2 * (n - 1 - i) + k] = t;
            }
        }
        for (String name : new String[] { "boundary", "sinkFactor" }) {
            double[] a = (double[]) field(g, name);
            for (int i = 0; i < n / 2; i++) {
                double t = a[i];
                a[i] = a[n - 1 - i];
                a[n - 1 - i] = t;
            }
        }
        for (String name : new String[] { "label", "sourceNodes" }) {
            int[] a = (int[]) field(g, name);
            for (int i = 0; i < a.length; i++) {
                if (a[i] >= 0) {
                    a[i] = n - 1 - a[i];
                }
            }
        }
        int[] ends = (int[]) field(g, "endpoints");
        for (int i = 0; i < 2 * g.allocatedEdgeSlots(); i++) {
            if (ends[i] >= 0) {
                ends[i] = n - 1 - ends[i];
            }
        }
        IntList sinks = (IntList) field(g, "sinkNodes");
        for (int i = 0; i < sinks.size; i++) {
            sinks.data[i] = n - 1 - sinks.data[i];
        }
        IntList edges = (IntList) field(g, "activeEdges");
        int[] slots = (int[]) field(g, "edgeActiveSlot");
        for (int i = 0; i < edges.size / 2; i++) {
            int t = edges.data[i];
            edges.data[i] = edges.data[edges.size - 1 - i];
            edges.data[edges.size - 1 - i] = t;
        }
        for (int i = 0; i < edges.size; i++) {
            slots[edges.data[i]] = i;
        }
    }

    private static void permutationAndSymmetry() throws Exception {
        SmogGraph a = open(), b = open();
        permute(b);
        a.runSteps(340);
        b.runSteps(340);
        double[] af = a.copyField(), bf = b.copyField();
        for (int i = 0; i < af.length; i++) {
            permutationError = Math.max(permutationError, Math.abs(af[i] - bf[i]));
            near(af[i], bf[i], 2e-14, "permutation");
        }
        for (int r : new int[] { 8, 16, 24, 32 }) {
            near(a.sample(64 + r, 8, 64), a.sample(64, 8, 64 + r), 1e-14, "x/z symmetry +");
            near(a.sample(64 - r, 8, 64), a.sample(64, 8, 64 - r), 1e-14, "x/z symmetry -");
        }
    }

    private static void concurrentChanges() {
        byte[] world = air(24 * 8 * 8);
        for (int phase = 1; phase < 20; phase++) {
            SmogGraph a = new SmogGraph(world, 24, 8, 8), b = new SmogGraph(world, 24, 8, 8);
            a.setSources(new int[] { 0 }, new double[] { 2 });
            b.setSources(new int[] { 0 }, new double[] { 2 });
            a.runSteps(10);
            b.runSteps(10);
            double[] committed = a.copyField();
            double injected = a.injectedMass();
            for (int i = 0; i < phase; i++) {
                a.stepPart(20, false);
            }
            check(Arrays.equals(committed, a.copyField()), "partial result not published");
            near(a.injectedMass(), injected, 0, "provisional source not published");
            a.patch(new int[] { 7 }, new byte[] { 0 });
            b.patch(new int[] { 7 }, new byte[] { 0 });
            check(!a.isStepInProgress(), "geometry cancels candidate");
            a.setSourceRate(0, 3);
            for (int i = phase; i < 20; i++) {
                a.stepPart(20, false);
            }
            while (a.pendingIntervals() != 0) {
                a.finishPendingPart(20);
            }
            near(
                a.injectedMass(),
                injected + phase * .1 + (20 - phase) * .15,
                1e-12,
                "cancelled transport retains elapsed emission");
            check(a.iteration() == 11, "deadline progress after closure");
            healthy(a);
        }
    }

    private static void parameterRange() {
        Random r = new Random(122);
        byte[] world = air(32 * 16 * 32);
        for (double q : new double[] { 1e-12, 1e-3, 1, 1e6 }) {
            for (double dt : new double[] { .01, 1, 10 }) {
                SmogConfig c = SmogConfig.builder()
                    .diffusion(1e6)
                    .timeStep(dt)
                    .openMask(43)
                    .build();
                SmogGraph g = new SmogGraph(world, 32, 16, 32, c);
                int[] pos = new int[2000];
                double[] rates = new double[pos.length];
                for (int i = 0; i < pos.length; i++) {
                    pos[i] = r.nextInt(world.length);
                    rates[i] = q * (1 + i % 7);
                }
                g.setSources(pos, rates);
                g.runSteps(200, true);
                healthy(g);
            }
        }
    }

    private static byte[] chambers(int width) {
        byte[] a = air(16 * 8 * 8);
        for (int z = 0; z < 8; z++) {
            for (int y = 0; y < 8; y++) {
                a[(z * 8 + y) * 16 + 7] = 0;
            }
        }
        for (int z = 2; z < 2 + width; z++) {
            for (int y = 2; y < 2 + width; y++) {
                a[(z * 8 + y) * 16 + 7] = 1;
            }
        }
        return a;
    }

    private static double rightMass(SmogGraph g) {
        double m = 0;
        for (int z = 0; z < 8; z++) {
            for (int y = 0; y < 8; y++) {
                for (int x = 8; x < 16; x++) {
                    m += g.sample(x, y, z);
                }
            }
        }
        return m;
    }

    private static void geometryAndHistory() {
        double prior = -1;
        for (int width : new int[] { 0, 1, 2, 4 }) {
            SmogGraph g = new SmogGraph(chambers(width), 16, 8, 8);
            g.setSources(new int[] { g.index(2, 3, 3) }, new double[] { 1 });
            g.runSteps(20);
            double mass = rightMass(g);
            if (width == 0) {
                near(mass, 0, 0, "sealed chamber");
            } else {
                check(mass > prior, "wider passage at t=20: width=" + width + " mass=" + mass + " previous=" + prior);
            }
            prior = mass;
            if (width == 1) {
                int door = g.index(7, 2, 2);
                g.stepPart(20, false);
                g.queuePatch(new int[] { door }, new byte[] { 0 });
                g.drainPatches(1);
                double before = rightMass(g);
                for (int i = 1; i < 20; i++) {
                    g.stepPart(20, false);
                }
                g.runSteps(100);
                near(rightMass(g), before, 1e-12, "closed gate no transport");
            }
            healthy(g);
        }
        SmogGraph a = new SmogGraph(chambers(1), 16, 8, 8), b = new SmogGraph(chambers(1), 16, 8, 8);
        int door = a.index(7, 2, 2);
        a.patch(new int[] { door }, new byte[] { 0 });
        a.patch(new int[] { door }, new byte[] { 1 });
        a.setSources(new int[] { 0 }, new double[] { 1 });
        b.setSources(new int[] { 0 }, new double[] { 1 });
        a.runSteps(300);
        b.runSteps(300);
        double[] af = a.copyField(), bf = b.copyField();
        for (int i = 0; i < af.length; i++) {
            near(af[i], bf[i], 1e-13, "history of rebuilding");
        }
    }

    private static void queueCoalescing() {
        SmogGraph g = new SmogGraph(air(32 * 8 * 8), 32, 8, 8);
        g.seedRandom(1);
        long iteration = g.iteration();
        int[] p = new int[1000];
        byte[] v = new byte[1000];
        for (int i = 0; i < 1000; i++) {
            p[i] = i % 100;
            v[i] = (byte) (i / 100 % 2);
        }
        g.queuePatch(p, v);
        check(g.pendingChanges() == 0, "final no-op edits removed before cancellation");
        while (g.pendingChanges() != 0) {
            g.stepPart(20, false);
            check(g.iteration() == iteration, "transport paused behind geometry");
            check(g.drainPatches(7) <= 7, "patch budget");
        }
        check(g.nodeCount() == 4, "all queued edits ended at original air");
        healthy(g);
        g.queuePatch(new int[] { 0 }, new byte[] { 0 });
        g.patch(new int[] { 0 }, new byte[] { 1 });
        g.drainPatches(8);
        check(g.copyAir()[0] == 1, "last write across queue and patch");
        for (int i = 0; i < 100; i++) {
            g.queuePatch(new int[] { 0 }, new byte[] { (byte) (i % 2) });
            check(g.tick(8, 1, false), "parts=1 progresses under continuous bounded edits");
        }
        healthy(g);
    }

    private static void sourceLocality() {
        SmogGraph g = new SmogGraph(air(64 * 16 * 64), 64, 16, 64);
        int[] p = new int[10000];
        double[] q = new double[p.length];
        Arrays.fill(p, g.index(56, 8, 56));
        Arrays.fill(q, 1);
        p[0] = g.index(2, 2, 2);
        g.setSources(p, q);
        g.patch(new int[] { p[0] }, new byte[] { 0 });
        check(g.lastReboundSourceCount() == 1, "local source rebinding");
        check(g.sourceStatus(0) == SmogGraph.SOURCE_BLOCKED, "explicit blocked outlet");
        g.step();
        near(g.blockedEmissionMass(), 1, 0, "blocked emission recorded separately");
        healthy(g);
        g.setSourceRate(0, 0);
        check(g.sourceStatus(0) == SmogGraph.SOURCE_DISABLED, "source stopped");
        g.patch(new int[] { p[0] }, new byte[] { 1 });
        g.setSourceRate(0, 2);
        check(g.sourceStatus(0) == SmogGraph.SOURCE_ACTIVE, "source resumed");
    }

    private static void repeatedPatches() {
        SmogGraph g = new SmogGraph(chambers(1), 16, 8, 8);
        g.seedRandom(981);
        int[] p = { g.index(7, 2, 2) };
        byte[] v = { 0 };
        int nodes = 0, edges = 0;
        repeatedTimes = new double[20000];
        for (int i = 0; i < 20000; i++) {
            v[0] = (byte) (i % 2);
            repeatedTimes[i] = g.patch(p, v);
            if (i < 200) {
                nodes = Math.max(nodes, g.allocatedNodeSlots());
                edges = Math.max(edges, g.allocatedEdgeSlots());
            } else {
                check(
                    g.allocatedNodeSlots() <= nodes && g.allocatedEdgeSlots() <= edges,
                    "slot reuse has no cumulative growth");
            }
        }
        healthy(g);
        Arrays.sort(repeatedTimes);
    }
}

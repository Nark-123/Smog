package smog;

import java.util.Arrays;
import java.util.Random;

public final class OptimizationTests {

    static int checks;

    static void ok(boolean v, String m) {
        checks++;
        if (!v) {
            throw new AssertionError(m);
        }
    }

    static void near(double a, double b, String m) {
        ok(Double.isFinite(a) && Math.abs(a - b) <= 2e-9 * Math.max(1, Math.abs(b)), m + ": " + a + " != " + b);
    }

    static byte[] open(int n) {
        byte[] a = new byte[n];
        Arrays.fill(a, (byte) 1);
        return a;
    }

    static void drain(SmogGraph g, int parts) {
        int calls = 0;
        while (g.pendingIntervals() != 0) {
            g.finishPendingPart(parts);
            ok(++calls < 100000, "drain bounded");
        }
    }

    static void healthy(SmogGraph g) {
        near(g.balanceError(), 0, "ledger");
        for (double x : g.copyField()) {
            ok(Double.isFinite(x) && x >= 0, "finite nonnegative: " + x);
        }
    }

    static void rates() {
        for (int mode = 0; mode < 3; mode++) {
            SmogGraph g = new SmogGraph(open(32 * 8 * 8), 32, 8, 8);
            g.setSources(new int[] { 0 }, new double[] { 1 });
            for (int t = 0; t < 200; t++) {
                g.setSourceRate(0, mode == 0 ? 1 : mode == 1 ? 1 + (t & 1) : (t & 1));
                g.stepPart(20, false);
            }
            near(g.generatedEmissionMass(), mode == 0 ? 10 : mode == 1 ? 15 : 5, "T1 integrated");
            near(g.modelTime(), 9, "intentional one-interval lag");
            ok(g.cancelledCandidates() == 0 && g.catchUpSteps() == 0, "no retries");
            ok(g.sample(16, 0, 0) > 0, "transport really happened");
            drain(g, 20);
            near(g.pendingEmissionMass(), 0, "drained");
            near(g.modelTime(), 10, "T1 drained time");
            healthy(g);
        }
    }

    static void blockedIntervals() {
        for (boolean closing : new boolean[] { false, true }) {
            SmogGraph g = new SmogGraph(new byte[] { (byte) (closing ? 1 : 0) }, 1, 1, 1);
            g.setSources(new int[] { 0 }, new double[] { 2 });
            for (int t = 0; t < 10; t++) {
                g.stepPart(20, false);
            }
            g.patch(new int[] { 0 }, new byte[] { (byte) (closing ? 0 : 1) });
            for (int t = 10; t < 20; t++) {
                g.stepPart(20, false);
            }
            drain(g, 20);
            near(g.blockedEmissionMass(), 1, "half blocked interval");
            near(g.injectedMass(), 1, "half accepted interval");
            near(g.trappedMass(), closing ? 1 : 0, "accepted before closure retained");
            healthy(g);
        }
    }

    static void equivalence() {
        for (int parts : new int[] { 1, 5, 20 }) {
            for (boolean cycling : new boolean[] { false, true }) {
                SmogGraph a = new SmogGraph(open(64 * 8 * 8), 64, 8, 8), b = new SmogGraph(open(64 * 8 * 8), 64, 8, 8);
                a.setSources(new int[] { 0 }, new double[] { 1 });
                b.setSources(new int[] { 0 }, new double[] { 1 });
                a.seedRandom(71);
                b.seedRandom(71);
                for (int w = 0; w < 10; w++) {
                    double total = 0;
                    for (int t = 0; t < parts; t++) {
                        double q = 1 + (t * 3 + w) % 7;
                        total += q / parts;
                        b.setSourceRate(0, q);
                        b.stepPart(parts, cycling);
                    }
                    a.setSourceRate(0, total);
                    a.step(cycling);
                }
                drain(b, parts);
                near(a.modelTime(), b.modelTime(), "T3 time");
                double[] af = a.copyField(), bf = b.copyField();
                for (int i = 0; i < af.length; i++) {
                    near(af[i], bf[i], "T3 same actual integral");
                }
                healthy(b);
            }
        }
    }

    static void events() {
        SmogGraph g = new SmogGraph(open(24 * 8 * 8), 24, 8, 8);
        int[] pos = new int[10000];
        double[] q = new double[10000];
        g.setSources(pos, q);
        for (int i = 0; i < 10000; i++) {
            ok(g.queueAddMass(0, .01), "event accepted");
            ok(g.queueRemoveMass(0, .004), "filter accepted");
        }
        near(g.pendingAddedMass(), 100, "T2 pending");
        near(g.mass(), 0, "no early mutation");
        g.finishPendingPart(20);
        near(g.mass(), 60, "T2 aggregated");
        near(g.removedMass(), 40, "actual removed");
        healthy(g);
        for (int t = 0; t < 25; t++) {
            g.stepPart(20, false);
        }
        double[] visible = g.copyField();
        g.queueAddMass(0, 2);
        g.queueRemoveMass(0, 1e6);
        ok(Arrays.equals(visible, g.copyField()), "candidate invisible");
        g.cancelStep();
        drain(g, 20);
        healthy(g);
        g.patch(new int[] { 0 }, new byte[] { 0 });
        ok(!g.queueAddMass(0, 1), "solid rejected");
        ok(!g.queueRemoveMass(0, 1), "solid filter rejected");
        for (double invalid : new double[] { -1, Double.NaN, Double.POSITIVE_INFINITY }) {
            boolean fail = false;
            try {
                g.queueAddMass(1, invalid);
            } catch (IllegalArgumentException e) {
                fail = true;
            }
            ok(fail, "bad add");
            fail = false;
            try {
                g.queueRemoveMass(1, invalid);
            } catch (IllegalArgumentException e) {
                fail = true;
            }
            ok(fail, "bad remove");
        }
    }

    static void backlog() {
        byte[] a = open(64 * 8 * 8);
        for (int z = 0; z < 8; z++) {
            for (int y = 0; y < 8; y++) {
                a[(z * 8 + y) * 64 + 24] = 0;
            }
        }
        SmogGraph g = new SmogGraph(a, 64, 8, 8);
        g.setSources(new int[] { 32 }, new double[] { 1 });
        for (int t = 0; t < 200; t++) {
            if (t > 0) {
                g.queuePatch(new int[] { 1 + (t - 1) % 15 }, new byte[] { 1 });
            }
            g.queuePatch(new int[] { 1 + t % 15 }, new byte[] { 0 });
            g.stepPart(20, false);
            ok(g.pendingChanges() == 1, "backlog exactly one");
            ok(g.oldestPatchAgeTicks() == 1, "oldest edit exactly one tick");
        }
        near(g.modelTime(), 9, "T4 continuous progress");
        ok(g.sample(48, 0, 0) > 0, "remote receiver polluted");
        near(g.generatedEmissionMass(), 10, "T4 integral");
        healthy(g);
        drain(g, 20);
    }

    static int[] points(int boxes) {
        int[] p = new int[20];
        for (int i = 0; i < 20; i++) {
            int box = i % boxes;
            p[i] = ((10 + (i / boxes) % 3) * 32 + 9 + ((box >> 1) & 1) * 8 + ((i / boxes) / 5) % 4) * 64 + 9
                + (box & 1) * 8
                + (i / boxes) % 5;
        }
        return p;
    }

    static void churn() {
        for (int boxes : new int[] { 1, 4 }) {
            for (boolean netzero : new boolean[] { true, false }) {
                SmogGraph g = new SmogGraph(open(64 * 32 * 64), 64, 32, 64);
                g.setSources(new int[] { 0 }, new double[] { 1 });
                int[] p = points(boxes);
                byte[] v = new byte[20];
                for (int t = 0; t < 2000; t++) {
                    Arrays.fill(v, (byte) 0);
                    g.queuePatch(p, v);
                    Arrays.fill(v, (byte) (netzero ? 1 : (t / 21) & 1));
                    g.queuePatch(p, v);
                    g.tick(40, 20, false);
                }
                if (netzero) {
                    ok(g.rebuildCount() == 0 && g.cancelledCandidates() == 0, "G1 zero rebuild and rollback");
                } else {
                    ok(g.rebuildCount() < 110, "G2 bounded rebuild frequency");
                }
                near(g.modelTime(), 99, "G1/G2 model progress");
                healthy(g);
                drain(g, 20);
                g.drainPatches(100);
                SmogGraph fresh = new SmogGraph(g.copyAir(), 64, 32, 64);
                ok(
                    OuterRegression.signature(g)
                        .equals(OuterRegression.signature(fresh)),
                    "G4 topology");
            }
        }
    }

    static void door() {
        // Fine nodes here make the gate-crossing oracle independent of coarse geometry.
        for (int phase = 1; phase < 20; phase++) {
            SmogConfig c = SmogConfig.builder()
                .containerSize(1)
                .mode(0)
                .build();
            SmogGraph g = new SmogGraph(open(4), 4, 1, 1, c);
            g.setSources(new int[] { 0 }, new double[] { 20 });
            g.runSteps(10);
            g.stepPart(20, false);
            for (int t = 1; t < 20 + phase; t++) {
                g.stepPart(20, false);
            }
            double before = g.sample(3);
            g.queuePatch(new int[] { 2 }, new byte[] { 0 });
            for (int t = phase; t < 20; t++) {
                g.stepPart(20, false);
            }
            near(g.sample(3), before, "G3 undo flux to isolated receiver phase=" + phase);
            healthy(g);
            g.drainPatches(1);
            drain(g, 20);
            double after = g.sample(3);
            g.runSteps(10);
            near(g.sample(3), after, "G3 sealed");
            healthy(g);
        }
    }

    static void coarseClosure() {
        SmogGraph g = new SmogGraph(open(512), 8, 8, 8);
        g.setSources(new int[] { 0 }, new double[] { 1 });
        for (int t = 0; t < 35; t++) {
            g.stepPart(20, false);
        }
        int[] wall = new int[64];
        for (int z = 0, k = 0; z < 8; z++) {
            for (int y = 0; y < 8; y++) {
                wall[k++] = (z * 8 + y) * 8 + 4;
            }
        }
        g.queuePatch(wall, new byte[64]);
        for (int t = 35; t < 40; t++) {
            g.stepPart(20, false);
        }
        near(g.sample(7, 0, 0), 0, "no fresh source mixing through a pending wall inside one old node");
        g.drainPatches(64);
        drain(g, 20);
        near(g.sample(7, 0, 0), 0, "coarse closed wall stays sealed");
        healthy(g);
        g.queuePatch(wall, open(64));
        g.drainPatches(64);
        g.runSteps(10);
        ok(g.sample(7, 0, 0) > 0, "reopened wall transports");
        healthy(g);
        // Multiple sources, late quarantine, exactly empty receivers: no negative residuals.
        Random r = new Random(73);
        for (int trial = 0; trial < 100; trial++) {
            SmogConfig c = SmogConfig.builder()
                .containerSize(1)
                .mode(0)
                .build();
            g = new SmogGraph(open(8 * 8 * 8), 8, 8, 8, c);
            int[] ps = new int[100];
            double[] q = new double[100];
            for (int i = 0; i < 100; i++) {
                ps[i] = r.nextInt(512);
                q[i] = r.nextDouble() * 10;
            }
            g.setSources(ps, q);
            for (int t = 0; t < 39; t++) {
                g.stepPart(20, false);
            }
            int[] edits = new int[trial < 40 ? 512 : 256];
            for (int i = 0; i < edits.length; i++) {
                edits[i] = trial < 40 ? i : r.nextInt(512);
            }
            g.queuePatch(edits, new byte[edits.length]);
            g.stepPart(20, false);
            healthy(g);
        }
    }

    static void history() {
        SmogGraph a = new SmogGraph(open(16), 16, 1, 1), b = new SmogGraph(open(16), 16, 1, 1);
        a.setSources(new int[] { 0 }, new double[] { 1 });
        b.setSources(new int[] { 0 }, new double[] { 1 });
        for (int t = 0; t < 200; t++) {
            double rate = t < 100 ? 2 : 0;
            a.setSourceRate(0, rate);
            b.setSourceRate(0, rate);
            a.stepPart(20, false);
            b.stepPart(20, false);
            b.cancelStep();
        }
        ok(b.pendingIntervals() > 1, "explicit cancellation backlog exists");
        drain(a, 20);
        drain(b, 20);
        for (int p = 0; p < 16; p++) {
            near(a.sample(p), b.sample(p), "history retained per interval");
        }
        near(b.modelTime(), 10, "history model time");
        healthy(b);
    }

    static void fuzz() {
        Random r = new Random(814);
        SmogGraph g = new SmogGraph(open(32 * 8 * 8), 32, 8, 8);
        int[] pos = { 0, 8, 16, 24 };
        double[] rates = { 1, 2, 3, 4 };
        g.setSources(pos, rates);
        double expected = 0;
        for (int t = 0; t < 8000; t++) {
            int i = r.nextInt(4);
            rates[i] = r.nextInt(4);
            g.setSourceRate(i, rates[i]);
            if (t % 3 == 0) {
                g.queuePatch(new int[] { r.nextInt(32 * 8 * 8) }, new byte[] { (byte) r.nextInt(2) });
            }
            if (t % 7 == 0 && g.queueAddMass(1, .1)) {
                expected += .1;
            }
            if (t % 11 == 0) {
                g.queueRemoveMass(1, .05);
            }
            for (double rate : rates) {
                expected += rate / 20;
            }
            g.tick(40, 20, false);
            near(g.generatedEmissionMass(), expected, "fuzz generated");
            near(g.balanceError(), 0, "fuzz ledger");
            if (t % 101 == 0) {
                healthy(g);
            }
        }
        drain(g, 20);
        healthy(g);
        near(g.generatedEmissionMass(), expected, "fuzz final");
    }

    public static void main(String[] args) {
        rates();
        blockedIntervals();
        equivalence();
        events();
        backlog();
        churn();
        door();
        coarseClosure();
        history();
        fuzz();
        System.out.println("PASS OptimizationTests checks=" + checks);
    }
}

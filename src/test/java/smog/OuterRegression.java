package smog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

public final class OuterRegression {

    static int checks;

    static boolean strict;

    static void ok(boolean v, String why) {
        checks++;
        if (!v) {
            throw new AssertionError(why);
        }
    }

    static void near(double a, double b, String why) {
        ok(
            Double.isFinite(a) && Math.abs(a - b) < 1e-8 * Math.max(1, Math.abs(b)),
            why + " actual=" + a + " expected=" + b);
    }

    static double metric(SmogGraph g, String name) {
        try {
            return ((Number) g.getClass()
                .getMethod(name)
                .invoke(g)).doubleValue();
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    static byte[] open(int n) {
        byte[] a = new byte[n];
        Arrays.fill(a, (byte) 1);
        return a;
    }

    static void healthy(SmogGraph g) {
        near(g.balanceError(), 0, "mass balance");
        for (double x : g.copyField()) {
            ok(x >= 0 && Double.isFinite(x), "nonnegative finite");
        }
    }

    static int p(int x, int y, int z, int nx, int ny) {
        return (z * ny + y) * nx + x;
    }

    static void sources() {
        for (int n : new int[] { 1, 100, 1000, 10000 }) {
            for (int mode = 0; mode < 4; mode++) {
                SmogGraph g = new SmogGraph(open(2048), 32, 8, 8);
                int[] pos = new int[n];
                double[] q = new double[n];
                Arrays.fill(q, 1);
                g.setSources(pos, q);
                double total = 0, rate = n;
                for (int t = 0; t < 200; t++) {
                    int i = t % n;
                    double nq = mode == 1 ? (t % 2 == 0 ? 2 : 1) : mode == 2 ? t % 2 : 1;
                    rate += nq - q[i];
                    q[i] = nq;
                    g.setSourceRate(i, nq);
                    if (mode == 3) {
                        g.queuePatch(new int[] { 1, 1 }, new byte[] { 0, 1 });
                    }
                    total += rate / 20;
                    g.tick(8, 20, false);
                }
                System.out.println(
                    "{\"test\":\"sources\",\"n\":" + n
                        + ",\"mode\":"
                        + mode
                        + ",\"expected\":"
                        + total
                        + ",\"emitted\":"
                        + g.injectedMass()
                        + ",\"steps\":"
                        + g.iteration()
                        + "}");
                if (strict) {
                    near(g.injectedMass(), total, "frame integrated rate");
                    ok(g.iteration() == 10, "source progress");
                    near(metric(g, "pendingEmissionMass"), 0, "no remaining emission");
                }
                healthy(g);
            }
        }
    }

    static byte[] chambers(int width) {
        byte[] a = open(512);
        for (int z = 0; z < 8; z++) {
            for (int y = 0; y < 8; y++) {
                a[p(4, y, z, 8, 8)] = 0;
            }
        }
        for (int z = 2; z < Math.min(8, 2 + width); z++) {
            for (int y = 2; y < Math.min(8, 2 + width); y++) {
                a[p(4, y, z, 8, 8)] = 1;
            }
        }
        return a;
    }

    static void doors() {
        for (int period : new int[] { 1, 5, 10, 20 }) {
            for (int n : new int[] { 100, 1000, 10000 }) {
                SmogGraph g = new SmogGraph(chambers(1), 8, 8, 8);
                int[] ps = new int[n];
                Arrays.fill(ps, g.index(1, 2, 2));
                double[] q = new double[n];
                Arrays.fill(q, 1);
                g.setSources(ps, q);
                double expected = 0, rate = n;
                for (int t = 0; t < 200; t++) {
                    if (t % period == 0) {
                        g.queuePatch(new int[] { g.index(4, 2, 2) }, new byte[] { (byte) ((t / period) % 2) });
                    }
                    int i = t % n;
                    double v = t % 2;
                    rate += v - q[i];
                    q[i] = v;
                    g.setSourceRate(i, v);
                    expected += rate / 20;
                    g.tick(1, 20, false);
                }
                System.out.println(
                    "{\"test\":\"doors\",\"period\":" + period
                        + ",\"sources\":"
                        + n
                        + ",\"emitted\":"
                        + g.injectedMass()
                        + ",\"expected\":"
                        + expected
                        + ",\"steps\":"
                        + g.iteration()
                        + ",\"catchups\":"
                        + metric(g, "catchUpSteps")
                        + "}");
                if (strict) {
                    near(g.injectedMass(), expected, "door source integral");
                    ok(g.iteration() == 10, "door progress");
                }
                healthy(g);
            }
        }
        // Close after each possible candidate phase: no candidate from the open version may publish.
        for (int phase = 1; phase < 20; phase++) {
            SmogGraph g = new SmogGraph(chambers(1), 8, 8, 8);
            g.setSources(new int[] { g.index(1, 2, 2) }, new double[] { 1 });
            for (int i = 0; i < phase; i++) {
                g.stepPart(20, false);
            }
            g.queuePatch(new int[] { g.index(4, 2, 2) }, new byte[] { 0 });
            for (int i = phase; i < 20; i++) {
                g.tick(1, 20, false);
            }
            near(g.sample(6, 2, 2), 0, "no stale closed-door transport");
            healthy(g);
        }
    }

    static void backlog() {
        SmogGraph g = new SmogGraph(open(2048), 32, 8, 8);
        g.setSources(new int[] { 0 }, new double[] { 1 });
        for (int t = 0; t < 200; t++) {
            int[] ps = { 1 + t * 4, 2 + t * 4, 3 + t * 4, 4 + t * 4 };
            g.queuePatch(ps, new byte[4]);
            g.tick(1, 20, false);
        }
        System.out.println(
            "{\"test\":\"backlog\",\"pending\":" + g.pendingChanges()
                + ",\"age\":"
                + metric(g, "oldestPatchAgeTicks")
                + ",\"lag\":"
                + metric(g, "modelLag")
                + ",\"emission_debt\":"
                + metric(g, "pendingEmissionMass")
                + "}");
        if (strict) {
            near(metric(g, "generatedEmissionMass"), 10, "backlog retains emission");
            ok(metric(g, "oldestPatchAgeTicks") > 0, "queue age");
        }
        int t = 200;
        while (g.pendingChanges() > 0 && t < 2000) {
            g.tick(64, 20, false);
            t++;
        }
        for (int i = 0; i < 220; i++) {
            g.tick(64, 20, false);
            t++;
        }
        if (strict) {
            near(metric(g, "generatedEmissionMass"), t / 20.0, "catchup generated budget");
            ok(metric(g, "modelLag") < 1.001, "bounded catchup clears time debt");
        }
        healthy(g);
    }

    static int[] snake(int nx, int ny, int ox, int oy, int oz, int axis) {
        ArrayList<Integer> ps = new ArrayList<>();
        for (int x = 1; x <= 6; x++) {
            ps.add(p(ox + (axis == 0 ? x : 2), oy + (axis == 1 ? x : 2), oz + 2, nx, ny));
        }
        for (int z = 3; z <= 4; z++) {
            ps.add(p(ox + (axis == 0 ? 6 : 2), oy + (axis == 1 ? 6 : 2), oz + z, nx, ny));
        }
        for (int x = 5; x >= 1; x--) {
            ps.add(p(ox + (axis == 0 ? x : 2), oy + (axis == 1 ? x : 2), oz + 4, nx, ny));
        }
        for (int z = 5; z <= 6; z++) {
            ps.add(p(ox + (axis == 0 ? 1 : 2), oy + (axis == 1 ? 1 : 2), oz + z, nx, ny));
        }
        for (int x = 2; x <= 6; x++) {
            ps.add(p(ox + (axis == 0 ? x : 2), oy + (axis == 1 ? x : 2), oz + 6, nx, ny));
        }
        int[] r = new int[ps.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = ps.get(i);
        }
        return r;
    }

    static void geometry() {
        for (int width : new int[] { 0, 1, 2, 3, 4, 5, 6 }) {
            SmogGraph g = new SmogGraph(chambers(width), 8, 8, 8);
            int[] labels = g.copyLabels();
            g.setSources(new int[] { g.index(1, 3, 3) }, new double[] { 1 });
            g.runSteps(100);
            System.out.println(
                "{\"test\":\"opening\",\"width\":" + width
                    + ",\"nodes\":"
                    + g.nodeCount()
                    + ",\"edges\":"
                    + g.edgeCount()
                    + ",\"same\":"
                    + (labels[g.index(1, 3, 3)] == labels[g.index(6, 3, 3)])
                    + ",\"far100\":"
                    + g.sample(6, 3, 3)
                    + "}");
            if (width == 0) {
                near(g.sample(6, 3, 3), 0, "sealed wall");
            }
            if (width == 3 || width == 4) {
                ok(labels[g.index(1, 3, 3)] != labels[g.index(6, 3, 3)], "keep outer rooms");
            }
            healthy(g);
        }
        for (int axis = 0; axis < 2; axis++) {
            for (int shift = 0; shift < 17; shift++) {
                for (int phase = 0; phase < 8; phase++) {
                    int[] ps = snake(32, 32, shift, 0, shift, axis);
                    byte[] a = new byte[32 * 32 * 32];
                    for (int v : ps) {
                        a[v] = 1;
                    }
                    SmogGraph g = new SmogGraph(
                        a,
                        32,
                        32,
                        32,
                        SmogConfig.builder()
                            .phase(phase)
                            .build());
                    g.setSources(new int[] { ps[0] }, new double[] { 1 });
                    g.step();
                    if (strict) {
                        ok(g.nodeCount() >= 3 && g.nodeCount() <= 20, "bounded narrow path partition");
                        near(g.sample(ps[ps.length - 1]), 0, "19-edge path first-step separation");
                    }
                    healthy(g);
                }
            }
        }
    }

    static Map<String, Double> signature(SmogGraph g) {
        int[] labels = g.copyLabels(), canonical = new int[g.allocatedNodeSlots()];
        Arrays.fill(canonical, Integer.MAX_VALUE);
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] >= 0) {
                canonical[labels[i]] = Math.min(canonical[labels[i]], i);
            }
        }
        Map<String, Double> result = new TreeMap<>();
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] >= 0) {
                result.put("v" + i, (double) canonical[labels[i]]);
            }
        }
        double[] ed = g.copyEdges();
        for (int i = 0; i < ed.length; i += 3) {
            if (ed[i] >= 0) {
                int a = canonical[(int) ed[i]], b = canonical[(int) ed[i + 1]];
                result.put("e" + Math.min(a, b) + ":" + Math.max(a, b), ed[i + 2]);
            }
        }
        return result;
    }

    static void patches() {
        Random random = new Random(761);
        for (int phase = 0; phase < 8; phase++) {
            byte[] a = new byte[24 * 16 * 24];
            for (int i = 0; i < a.length; i++) {
                a[i] = (byte) (random.nextDouble() < .6 ? 1 : 0);
            }
            SmogConfig c = SmogConfig.builder()
                .phase(phase)
                .build();
            SmogGraph g = new SmogGraph(a, 24, 16, 24, c);
            g.seedRandom(71);
            double mass = g.mass();
            for (int t = 0; t < 30; t++) {
                int[] ps = new int[1 + t % 10];
                byte[] v = new byte[ps.length];
                for (int i = 0; i < ps.length; i++) {
                    ps[i] = random.nextInt(a.length);
                    v[i] = (byte) (1 - a[ps[i]]);
                    a[ps[i]] = v[i];
                }
                g.patch(ps, v);
                SmogGraph rebuilt = new SmogGraph(a, 24, 16, 24, c);
                ok(
                    signature(g).equals(signature(rebuilt)),
                    "patch == rebuild partition and G phase=" + phase + " t=" + t);
                near(g.mass() + g.trappedMass(), mass, "patch conserved");
            }
        }
        byte[] a = new byte[512];
        a[0] = 1;
        SmogGraph g = new SmogGraph(a, 8, 8, 8);
        g.setSources(new int[] { 0 }, new double[] { 1 });
        g.step();
        g.patch(new int[] { 0 }, new byte[] { 0 });
        near(g.trappedMass(), 1, "closed singleton trapped");
        g.patch(new int[] { 1 }, new byte[] { 1 });
        near(g.trappedMass(), 1, "other voxel does not release trapped");
        g.patch(new int[] { 0 }, new byte[] { 1 });
        near(g.trappedMass(), 0, "original voxel releases");
        near(g.mass(), 1, "trapped restored");
        healthy(g);
    }

    static void blocked() {
        if (!strict) {
            return;
        }
        SmogGraph g = new SmogGraph(new byte[512], 8, 8, 8);
        g.setSources(new int[] { 0 }, new double[] { 2 });
        for (int t = 0; t < 10; t++) {
            g.stepPart(20, false);
        }
        g.patch(new int[] { 0 }, new byte[] { 1 });
        for (int t = 10; t < 20; t++) {
            g.stepPart(20, false);
        }
        near(g.blockedEmissionMass(), 1, "first blocked half");
        near(g.injectedMass(), 1, "second open half");
        healthy(g);
        g.reset();
        g.setSourceRate(0, 2);
        for (int t = 0; t < 10; t++) {
            g.stepPart(20, false);
        }
        g.patch(new int[] { 0 }, new byte[] { 0 });
        for (int t = 10; t < 20; t++) {
            g.stepPart(20, false);
        }
        near(g.blockedEmissionMass(), 1, "second blocked half");
        near(g.trappedMass(), 1, "pending air emission traps on closure");
        near(g.injectedMass(), 1, "accepted before closure");
        healthy(g);
    }

    public static void main(String[] args) {
        strict = args.length > 0 && args[0].equals("strict");
        sources();
        doors();
        backlog();
        geometry();
        patches();
        blocked();
        System.out.println("{\"status\":\"PASS\",\"checks\":" + checks + ",\"strict\":" + strict + "}");
    }
}

package smog;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Dependency-free correctness suite. Assertions are explicit (also work without -ea).
 */
public final class SmogTests {

    private static int checks, oracleCases, oracleSnapshots;

    private static double maxOracleError, maxOracleScaledError;

    private SmogTests() {}

    public static void main(String[] args) throws Exception {
        analyticTwoCells();
        budgetEquivalence();
        trapsAndSources();
        randomizedPatches();
        independentConnectivity();
        configurationAndValidation();
        customCycle();
        String fixture = args.length == 0 ? "reference/cpp-fixtures.bin" : args[0];
        if (!Files.isRegularFile(Paths.get(fixture))) {
            throw new IllegalStateException("Missing C++ fixtures: run python3 tools/export_reference.py");
        }
        cppOracle(fixture);
        System.out.println(
            "{\"status\":\"PASS\",\"checks\":" + checks
                + ",\"cpp_cases\":"
                + oracleCases
                + ",\"cpp_snapshots\":"
                + oracleSnapshots
                + ",\"max_cpp_absolute_error\":"
                + maxOracleError
                + ",\"max_cpp_scaled_error\":"
                + maxOracleScaledError
                + "}");
    }

    private static SmogConfig config(int h, int mode, int phase, int law, int mask, double d, double dt) {
        return SmogConfig.builder()
            .containerSize(h)
            .mode(mode)
            .phase(phase)
            .law(law)
            .openMask(mask)
            .diffusion(d)
            .timeStep(dt)
            .build();
    }

    private static byte[] open(int n) {
        byte[] a = new byte[n];
        Arrays.fill(a, (byte) 1);
        return a;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void close(double a, double b, double tolerance, String message) {
        check(
            Double.isFinite(a) && Double.isFinite(b) && Math.abs(a - b) <= tolerance * Math.max(1, Math.abs(b)),
            message + ": " + a + " vs " + b);
    }

    private static void balance(SmogGraph g) {
        close(g.balanceError(), 0, 2e-10 * Math.max(1, g.injectedMass()), "mass ledger");
        for (double value : g.copyField()) {
            check(Double.isFinite(value) && value >= -1e-13, "nonnegative finite concentration");
        }
    }

    private static void rejects(Runnable call) {
        boolean rejected = false;
        try {
            call.run();
        } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException e) {
            rejected = true;
        }
        check(rejected, "Expected validation failure");
    }

    private static void analyticTwoCells() {
        SmogGraph g = new SmogGraph(open(2), 2, 1, 1, config(1, 0, 0, 0, 0, 1, .01));
        g.setSources(new int[] { 0 }, new double[] { 1 });
        g.runSteps(500);
        close(g.sample(0), 2.5 + (1 - Math.exp(-10)) / 4, 4e-6, "linear analytical source");
        close(g.sample(1), 2.5 - (1 - Math.exp(-10)) / 4, 4e-6, "linear analytical receiver");
        balance(g);
        for (int law : new int[] { 0, 2 }) {
            g = new SmogGraph(open(2), 2, 1, 1, config(1, 0, 0, law, 0, .7, .03));
            g.seedRandom(91);
            double left = g.sample(0), right = g.sample(1);
            g.runSteps(70);
            double difference = (left - right) * Math.exp(-2 * .7 * .03 * 70 * (law == 0 ? 1 : left + right));
            close(g.sample(0), (left + right + difference) / 2, 3e-14, "isolated pair analytical decay");
            balance(g);
        }
    }

    private static void budgetEquivalence() {
        Random random = new Random(144);
        byte[] air = new byte[17 * 9 * 13];
        for (int i = 0; i < air.length; i++) {
            air[i] = random.nextDouble() > .25 ? (byte) 1 : 0;
        }
        air[0] = 1;
        for (int law : new int[] { 0, 2, 3, 4 }) {
            for (int parts : new int[] { 1, 7, 20, 700 }) {
                SmogConfig c = config(4, 3, 2, law, 63, 20, .1);
                SmogGraph a = new SmogGraph(air, 17, 9, 13, c), b = new SmogGraph(air, 17, 9, 13, c);
                a.setSources(new int[] { 0 }, new double[] { 1 });
                b.setSources(new int[] { 0 }, new double[] { 1 });
                a.seedRandom(123);
                b.seedRandom(123);
                int[] edits = { 10, 91, 110 };
                byte[] values = { 0, 0, 0 };
                a.patch(edits, values);
                b.patch(edits, values);
                a.runSteps(19, true);
                for (int step = 0; step < 19; step++) {
                    for (int p = 0; p < parts; p++) {
                        check(
                            b.stepPart(parts, true) == (p == parts - 1 && (step > 0 || parts == 1)),
                            "budget completion");
                    }
                }
                while (b.pendingIntervals() != 0) {
                    b.finishPendingPart(parts);
                }
                check(Arrays.equals(a.copyField(), b.copyField()), "bit-exact budget equivalence at equal model time");
                check(a.injectedMass() == b.injectedMass() && a.escapedMass() == b.escapedMass(), "budget ledger");
                balance(a);
                balance(b);
            }
        }
    }

    private static void trapsAndSources() {
        byte[] air = open(27);
        SmogGraph g = new SmogGraph(air, 3, 3, 3, config(1, 0, 0, 4, 0, 5, .5));
        g.setSources(new int[] { 13 }, new double[] { 1 });
        g.seedRandom(92);
        g.runSteps(10);
        double before = g.mass();
        int[] all = new int[27];
        for (int i = 0; i < 27; i++) {
            all[i] = i;
        }
        g.patch(all, new byte[27]);
        check(g.nodeCount() == 0 && g.edgeCount() == 0, "all solid");
        close(g.trappedMass(), before, 1e-13, "all mass trapped");
        double injected = g.injectedMass();
        g.runSteps(12);
        check(g.injectedMass() == injected, "blocked source suspended");
        for (int i = 26; i >= 0; i--) {
            g.patch(new int[] { i }, new byte[] { 1 });
        }
        close(g.trappedMass(), 0, 0, "all trapped mass released");
        close(g.mass(), before, 1e-13, "released mass");
        g.runSteps(2);
        close(g.injectedMass(), injected + 1, 1e-13, "source rebound");
        balance(g);
        g.reset();
        check(g.mass() == 0 && g.iteration() == 0 && g.injectedMass() == 0, "reset");
        g.runSteps(1);
        close(g.mass(), .5, 1e-14, "reset preserves sources");
        // Unequal old/new volumes: closing one voxel redistributes, opening an empty voxel dilutes.
        g = new SmogGraph(open(4), 4, 1, 1, config(4, 0, 0, 4, 0, 1, 1));
        g.setSources(new int[] { 0 }, new double[] { 4 });
        g.runSteps(1);
        g.patch(new int[] { 3 }, new byte[] { 0 });
        close(g.sample(0), 4.0 / 3, 1e-14, "survivor redistribution");
        g.patch(new int[] { 3 }, new byte[] { 1 });
        close(g.sample(0), 1, 1e-14, "new air dilution");
    }

    private static void randomizedPatches() {
        Random random = new Random(818);
        for (int mode = 0; mode <= 3; mode++) {
            for (int phase : new int[] { 0, 3, 7 }) {
                byte[] air = new byte[23 * 13 * 19];
                for (int i = 0; i < air.length; i++) {
                    air[i] = random.nextDouble() > .4 ? (byte) 1 : 0;
                }
                SmogConfig c = config(8, mode, phase, 4, 63, 32, .2);
                SmogGraph g = new SmogGraph(air, 23, 13, 19, c);
                g.seedRandom(919);
                for (int step = 0; step < 25; step++) {
                    int[] positions = new int[step % 7 + 1];
                    byte[] values = new byte[positions.length];
                    for (int j = 0; j < positions.length; j++) {
                        positions[j] = random.nextInt(air.length);
                        values[j] = (byte) (1 - air[positions[j]]);
                    }
                    g.patch(positions, values);
                    for (int j = 0; j < positions.length; j++) {
                        air[positions[j]] = values[j];
                    }
                    SmogGraph fresh = new SmogGraph(air, 23, 13, 19, c);
                    compareTopology(
                        g.copyLabels(),
                        g.copyEdges(),
                        g.allocatedNodeSlots(),
                        fresh.copyLabels(),
                        fresh.copyEdges(),
                        fresh.allocatedNodeSlots());
                    g.runSteps(2, true);
                    balance(g);
                }
            }
        }
    }

    private static void independentConnectivity() {
        Random random = new Random(717);
        int nx = 13, ny = 10, nz = 11, n = nx * ny * nz;
        for (int phase = 0; phase < 8; phase++) {
            byte[] air = new byte[n];
            for (int i = 0; i < n; i++) {
                air[i] = random.nextDouble() > .65 ? (byte) 1 : 0;
            }
            SmogGraph g = new SmogGraph(air, nx, ny, nz, config(8, 3, phase, 4, 0, 1, 1));
            int[] fine = parents(n), labels = g.copyLabels(), coarse = parents(g.allocatedNodeSlots());
            for (int p = 0; p < n; p++) {
                if (air[p] != 0) {
                    int x = p % nx, y = p / nx % ny, z = p / (nx * ny);
                    if (x + 1 < nx && air[p + 1] != 0) {
                        union(fine, p, p + 1);
                    }
                    if (y + 1 < ny && air[p + nx] != 0) {
                        union(fine, p, p + nx);
                    }
                    if (z + 1 < nz && air[p + nx * ny] != 0) {
                        union(fine, p, p + nx * ny);
                    }
                }
            }
            double[] edges = g.copyEdges();
            for (int i = 0; i < edges.length; i += 3) {
                if (edges[i] >= 0) {
                    union(coarse, (int) edges[i], (int) edges[i + 1]);
                }
            }
            Map<Integer, Integer> fToC = new HashMap<>(), cToF = new HashMap<>();
            int[] nodeFine = new int[coarse.length];
            Arrays.fill(nodeFine, -1);
            for (int p = 0; p < n; p++) {
                if (air[p] != 0) {
                    int f = root(fine, p), node = labels[p], c = root(coarse, node);
                    check(nodeFine[node] < 0 || nodeFine[node] == f, "node must not merge disconnected air");
                    nodeFine[node] = f;
                    Integer oldC = fToC.put(f, c), oldF = cToF.put(c, f);
                    check(oldC == null || oldC == c, "no lost connectivity");
                    check(oldF == null || oldF == f, "no false connectivity");
                }
            }
        }
    }

    private static int[] parents(int n) {
        int[] p = new int[n];
        for (int i = 0; i < n; i++) {
            p[i] = i;
        }
        return p;
    }

    private static int root(int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }

    private static void union(int[] p, int a, int b) {
        p[root(p, a)] = root(p, b);
    }

    private static void configurationAndValidation() {
        SmogGraph a = new SmogGraph(open(64), 4, 4, 4, config(1, 0, 0, 0, 63, 1, .2));
        SmogGraph b = new SmogGraph(open(64), 4, 4, 4, config(1, 0, 0, 4, 63, 1, .2));
        a.setLaw(4);
        a.seedRandom(777);
        b.seedRandom(777);
        a.runSteps(9);
        b.runSteps(9);
        check(Arrays.equals(a.copyField(), b.copyField()), "law change refreshes weights");
        a.stepPart(3, true);
        rejects(() -> a.stepPart(4, true));
        a.cancelStep();
        byte[] before = a.copyAir();
        rejects(() -> a.patch(new int[] { 0, -1 }, new byte[] { 0, 0 }));
        check(Arrays.equals(before, a.copyAir()), "invalid patch has no partial effects");
        rejects(() -> a.configure(Double.NaN, 1));
        rejects(() -> a.configure(1, Double.POSITIVE_INFINITY));
        rejects(() -> a.setSources(new int[] { 0 }, new double[] { -1 }));
        rejects(() -> a.setSources(new int[] { 0 }, new double[] { Double.NaN }));
        rejects(() -> new SmogGraph(open(1), 1, 0, 1));
        rejects(
            () -> SmogConfig.builder()
                .law(1)
                .build());
        rejects(
            () -> SmogConfig.builder()
                .phase(8)
                .build());
        rejects(
            () -> SmogConfig.builder()
                .cycle(0, 0, 1)
                .build());
        SmogGraph empty = new SmogGraph(new byte[8], 2, 2, 2);
        empty.runSteps(3);
        empty.stepPart(2, false);
        empty.stepPart(2, false);
        balance(empty);
        check(empty.nodeCount() == 0 && empty.sample(-1) == 0 && empty.sample(8) == 0, "empty world");
    }

    private static void customCycle() {
        SmogConfig c = SmogConfig.builder()
            .cycle(7, 3, 2)
            .timeStep(.5)
            .build();
        SmogGraph g = new SmogGraph(open(1), 1, 1, 1, c);
        g.setSources(new int[] { 0, 0, 0 }, new double[] { 1, 2, 3 });
        g.runSteps(14, true);
        close(g.mass(), 18, 1e-14, "custom source schedule");
    }

    private static void cppOracle(String path) throws Exception {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(Paths.get(path))))) {
            byte[] magic = new byte[8];
            in.readFully(magic);
            check(Arrays.equals(magic, "SMOGREF1".getBytes("US-ASCII")), "fixture version");
            int count = in.readInt();
            for (int test = 0; test < count; test++) {
                int nx = in.readInt(), ny = in.readInt(), nz = in.readInt();
                int h = in.readInt(), mode = in.readInt(), radius = in.readInt(), phase = in.readInt();
                int mask = in.readInt(), law = in.readInt();
                if (law == 4) {
                    law = SmogConfig.QUADRATIC_LEGACY;
                }
                double d = in.readDouble(), dt = in.readDouble();
                long random = in.readLong();
                int steps = in.readInt(), cycling = in.readInt(), sources = in.readInt();
                byte[] air = new byte[nx * ny * nz];
                in.readFully(air);
                int[] positions = ints(in, sources);
                double[] rates = doubles(in, sources);
                SmogConfig c = SmogConfig.builder()
                    .containerSize(h)
                    .mode(mode)
                    .radius(radius)
                    .phase(phase)
                    .openMask(mask)
                    .law(law)
                    .diffusion(d)
                    .timeStep(dt)
                    .narrowPathRefinement(false)
                    .build();
                SmogGraph g = new SmogGraph(air, nx, ny, nz, c);
                g.setSources(positions, rates);
                g.seedRandom(random);
                g.runSteps(steps, cycling != 0);
                try {
                    readSnapshot(in, g, true);
                    int patches = in.readInt();
                    for (int patch = 0; patch < patches; patch++) {
                        int size = in.readInt();
                        int[] ps = ints(in, size);
                        byte[] vs = new byte[size];
                        in.readFully(vs);
                        g.patch(ps, vs);
                        readSnapshot(in, g, false);
                    }
                } catch (AssertionError error) {
                    throw new AssertionError(
                        "C++ case " + test
                            + " h="
                            + h
                            + " mode="
                            + mode
                            + " phase="
                            + phase
                            + " law="
                            + law
                            + ": "
                            + error.getMessage(),
                        error);
                }
                oracleCases++;
            }
            check(in.read() == -1, "all fixture bytes consumed");
        }
    }

    private static void readSnapshot(DataInputStream in, SmogGraph g, boolean exactIds) throws Exception {
        int nodeLength = in.readInt(), edgeLength = in.readInt();
        double[] info = doubles(in, 6);
        int[] labels = ints(in, g.nx * g.ny * g.nz);
        double[] nodes = doubles(in, nodeLength), edges = doubles(in, edgeLength), field = doubles(in, labels.length);
        check(info[0] == g.nodeCount() && info[1] == g.edgeCount(), "C++ node/edge counts");
        double[] actualInfo = { g.nodeCount(), g.edgeCount(), g.mass(), g.injectedMass(), g.escapedMass(),
            g.trappedMass() };
        for (int i = 2; i < info.length; i++) {
            oracleClose(actualInfo[i], info[i], "C++ ledger");
        }
        int[] actualLabels = g.copyLabels();
        if (exactIds) {
            check(Arrays.equals(actualLabels, labels), "C++ initial labels");
            double[] actualNodes = g.copyNodeData();
            check(actualNodes.length == nodes.length, "C++ initial node slots");
            for (int i = 0; i < nodes.length; i++) {
                oracleClose(actualNodes[i], nodes[i], "C++ node data");
            }
            double[] actualEdges = g.copyEdges();
            check(Arrays.equals(actualEdges, edges), "C++ initial ordered edges");
        }
        compareTopology(actualLabels, g.copyEdges(), g.allocatedNodeSlots(), labels, edges, nodes.length / 3);
        for (int i = 0; i < field.length; i++) {
            oracleClose(g.sample(i), field[i], "C++ field at " + i);
        }
        balance(g);
        oracleSnapshots++;
    }

    private static void oracleClose(double actual, double expected, String message) {
        double error = Math.abs(actual - expected);
        maxOracleError = Math.max(maxOracleError, error);
        maxOracleScaledError = Math.max(maxOracleScaledError, error / Math.max(1, Math.abs(expected)));
        close(actual, expected, 2e-11, message);
    }

    private static int[] ints(DataInputStream in, int n) throws Exception {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = in.readInt();
        }
        return a;
    }

    private static double[] doubles(DataInputStream in, int n) throws Exception {
        double[] a = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = in.readDouble();
        }
        return a;
    }

    private static int[] minimumVoxels(int[] labels, int nodes) {
        int[] mins = new int[nodes];
        Arrays.fill(mins, Integer.MAX_VALUE);
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] >= 0) {
                mins[labels[i]] = Math.min(mins[labels[i]], i);
            }
        }
        return mins;
    }

    private static Map<Long, Double> edgeSignature(double[] edges, int[] mins) {
        Map<Long, Double> result = new HashMap<>();
        for (int i = 0; i < edges.length; i += 3) {
            if (edges[i] >= 0) {
                int a = mins[(int) edges[i]], b = mins[(int) edges[i + 1]];
                long key = ((long) Math.min(a, b) << 32) | Math.max(a, b);
                check(result.put(key, edges[i + 2]) == null, "no duplicate edges");
            }
        }
        return result;
    }

    private static void compareTopology(int[] a, double[] ae, int an, int[] b, double[] be, int bn) {
        int[] am = minimumVoxels(a, an), bm = minimumVoxels(b, bn);
        check(a.length == b.length, "same voxel count");
        for (int i = 0; i < a.length; i++) {
            check((a[i] < 0 ? -1 : am[a[i]]) == (b[i] < 0 ? -1 : bm[b[i]]), "canonical membership at " + i);
        }
        check(edgeSignature(ae, am).equals(edgeSignature(be, bm)), "canonical edge conductances");
    }
}

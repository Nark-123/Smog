package smog;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Matched prebuilt checkpoint fixtures. Times publication, ready events and final epsilon only. */
public final class ColdMassBenchmark {
    private static final Method PUBLISH, EVENTS, FINISH;
    private static long lastExact = -1, lastFallback = -1;
    private static long counter(GeometryMetrics m, String name) {
        try { return GeometryMetrics.class.getField(name).getLong(m); }
        catch (Exception baseline) { return -1; }
    }
    static {
        try {
            PUBLISH = DimensionState.class.getDeclaredMethod("publish", CellBuild.class); PUBLISH.setAccessible(true);
            EVENTS = DimensionState.class.getDeclaredMethod("applyEvents"); EVENTS.setAccessible(true);
            Method f;
            try { f = DimensionState.class.getDeclaredMethod("finishPublicationMass", DimensionState.Cell.class, int.class); f.setAccessible(true); }
            catch (NoSuchMethodException oldVersion) { f = null; }
            FINISH = f;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static CellBuild complete(DimensionState s, DimensionState.Cell c) {
        CellBuild b = new CellBuild(s, c);
        for (int n = 0; !b.ready && n < 100000; n++) b.advance();
        if (!b.ready || !b.validWorld()) throw new AssertionError("candidate");
        return b;
    }
    private static String hash(DimensionState s) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Map.Entry<String,Double> e : s.topology().entrySet()) {
            digest.update(e.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update(Long.toHexString(Double.doubleToLongBits(e.getValue())).getBytes(StandardCharsets.US_ASCII));
        }
        StringBuilder h = new StringBuilder();
        for (byte b : digest.digest()) h.append(String.format("%02x", b & 255));
        return h.toString();
    }
    private static final class Scene {
        final SparseTests.World world = new SparseTests.World();
        final Checkpoint.Data data = new Checkpoint.Data();
        Scene(boolean fragmented) {
            data.dimension = 0; data.config = SmogConfig.throat8(); data.parts = 20;
            Random random = new Random(111);
            for (int z = 0; z < 8; z++) for (int x = 0; x < 16; x++) {
                byte[] air = SparseTests.air();
                if (fragmented) for (int p = 0; p < 4096; p++) air[p] = (byte) (random.nextInt(7) == 0 ? 0 : 1);
                world.put(new CubeKey(0, x, 0, z), air, 1, 1);
            }
            try (DimensionState s = new DimensionState(0, data.config, world)) {
                for (int z = 0; z < 8; z++) for (int x = 0; x < 16; x++) for (int slot = 0; slot < 8; slot++) {
                    CellKey k = new CellKey(0, x * 2 + (slot & 1), (slot >> 1) & 1, z * 2 + ((slot >> 2) & 1));
                    CellBuild b = complete(s, s.cell(k, true));
                    double[] masses = new double[b.geometry.volumes.length]; Arrays.fill(masses, 100.0);
                    Checkpoint.CellData d = new Checkpoint.CellData(); d.key = k;
                    d.mass = new DimensionState.MassRecord(b.geometry.labels.clone(), b.geometry.volumes.clone(), masses);
                    data.cells.add(d); data.injected += masses.length * 100.0;
                }
            }
        }
    }
    private static long round(Scene scene, List<Long> samples, Set<String> topologies, List<double[]> physics) throws Exception {
        try (DimensionState s = Checkpoint.restore(scene.data, scene.world)) {
            List<CellBuild> prepared = new ArrayList<>();
            for (Checkpoint.CellData d : scene.data.cells) prepared.add(complete(s, s.cell(d.key, false)));
            long sum = 0;
            for (CellBuild b : prepared) {
                if (!b.validWorld()) throw new AssertionError("stale world");
                if (!b.validContacts()) { b.refreshContacts(); while (!b.ready) b.advance(); }
                if (s.graph.isStepInProgress()) throw new AssertionError("unsafe publish");
                long start = System.nanoTime();
                Object policy = PUBLISH.invoke(s, b);
                EVENTS.invoke(s);
                if (FINISH != null) FINISH.invoke(s, b.cell, ((Integer) policy).intValue());
                long time = System.nanoTime() - start; sum += time;
                if (samples != null) samples.add(time);
            }
            if (Math.abs(s.liveMass() - scene.data.injected) > scene.data.injected * 1e-10 || s.parkedMass() != 0)
                throw new AssertionError("mass");
            if (topologies != null) topologies.add(hash(s));
            if (physics != null) {
                CellBuild first = prepared.get(0); int p = 0;
                while (first.geometry.labels[p] < 0) p++;
                BlockPos point = first.cell.key.block(p);
                for (int step = 0; step < 2; step++) {
                    s.frames.add(new DimensionState.Frame(new BlockPos[] {point}, new double[] {step == 0 ? 64 : 0}, 0));
                    for (int part = 0; s.pendingIntervals() != 0 && part < 100; part++) s.finishPendingPart(20);
                    if (s.pendingIntervals() != 0) throw new AssertionError("law4 did not finish");
                }
                double[] values = new double[33]; values[0] = s.liveMass();
                for (int i = 0; i < 32; i++) {
                    CellBuild b = prepared.get(i); int q = 0; while (b.geometry.labels[q] < 0) q++;
                    values[i + 1] = s.sample(b.cell.key.block(q));
                }
                physics.add(values);
            }
            lastExact = counter(s.geometryMetrics, "coldExactCount");
            lastFallback = counter(s.geometryMetrics, "coldFallbackCount");
            return sum;
        }
    }
    public static void main(String[] args) throws Exception {
        String variant = args.length == 0 ? "unspecified" : args[0];
        for (boolean fragmented : new boolean[] {false, true}) {
            Scene scene = new Scene(fragmented);
            for (int warm = 0; warm < 3; warm++) round(scene, null, null, null);
            List<Long> totals = new ArrayList<>(), samples = new ArrayList<>(); Set<String> hashes = new HashSet<>();
            List<double[]> physics = new ArrayList<>();
            for (int repeat = 0; repeat < 5; repeat++) totals.add(round(scene, samples, hashes, physics));
            if (hashes.size() != 1) throw new AssertionError("topology nondeterminism");
            Collections.sort(totals); Collections.sort(samples);
            System.out.println("{\"variant\":\"" + variant + "\",\"scene\":\"" + (fragmented ? "fragmented" : "open")
                    + "\",\"cellsPerRound\":1024,\"warmups\":3,\"rounds\":5,\"medianRoundNs\":" + totals.get(2)
                    + ",\"roundsNs\":" + totals + ",\"medianCallNs\":" + samples.get(samples.size()/2)
                    + ",\"p99CallNs\":" + samples.get((samples.size()-1)*99/100)
                    + ",\"coldExactCount\":" + lastExact + ",\"coldFallbackCount\":" + lastFallback
                    + ",\"physicsSamples\":" + Arrays.toString(physics.get(0))
                    + ",\"topologySha256\":\"" + hashes.iterator().next() + "\"}");
        }
    }
}

package smog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Standalone v1 checkpoint. Single generation payload prevents mixed cube cuts. The payload
 * contains spatial memberships and masses, never runtime topology.
 */
final class Checkpoint {

    private static final int MAGIC = 0x534D4738,
            VERSION = 1,
            MAX_BYTES = 256 * 1024 * 1024,
            MAX_RECORDS = 1000000;

    static final class Data {

        int dimension, windowTick, parts;

        boolean cycling;

        SmogConfig config;

        long ticks, steps, windows, generation;
        double injected, escaped, rejected, removed, openRejected;

        final List<DimensionState.Source> sources = new ArrayList<>();

        final List<DimensionState.Frame> frames = new ArrayList<>();

        final LinkedHashMap<BlockPos, Double> open = new LinkedHashMap<>();

        final List<CellData> cells = new ArrayList<>();
    }

    static final class CellData {

        CellKey key;

        DimensionState.MassRecord mass;

        final TreeMap<Integer, Double> trapped = new TreeMap<>(), waiting = new TreeMap<>();

        final TreeMap<Integer, DimensionState.Event> events = new TreeMap<>();
    }

    static final class Capture {

        final Data data = new Data();

        private final DimensionState state;

        private final Iterator<DimensionState.CubeRecord> cubes;

        private final Set<CellKey> sourceCells = new HashSet<>();

        private DimensionState.CubeRecord cube;

        private int slot;

        private final long lastCell;

        private static final CellData COPIED = new CellData();

        private final Map<DimensionState.Cell, CellData> frozen = new java.util.IdentityHashMap<>();

        Capture(DimensionState s) {
            s.settleSources();
            state = s;
            lastCell = s.cellSequence();
            data.dimension = s.dimension;
            data.config = s.config;
            data.ticks = s.ticks;
            data.steps = s.steps;
            data.windows = s.emissionWindows;
            data.generation = s.generation;
            data.windowTick = s.windowTick;
            data.parts = s.scheduleParts;
            data.cycling = s.scheduleCycling;
            data.injected = s.injected;
            data.escaped = s.escapedMass();
            data.rejected = s.rejected;
            data.removed = s.removed;
            data.openRejected = s.openRejected;
            // Frames are immutable and contain spatial positions only.
            data.open.putAll(s.openAccepted);
            // Frames are immutable and contain spatial positions only.
            for (DimensionState.Frame f : s.frames) {
                data.frames.add(new DimensionState.Frame(f.positions, f.amounts, f.rejected));
            }
            for (DimensionState.Source source : s.sources.values()) {
                data.sources.add(
                        new DimensionState.Source(source.position, source.rate, source.phase));
                sourceCells.add(source.position.cell());
            }
            cubes = new ArrayList<>(s.cubes.values()).iterator();
        }

        boolean advance() {
            if (cube == null || slot == 8) {
                if (!cubes.hasNext()) {
                    return true;
                }
                cube = cubes.next();
                slot = 0;
            }
            DimensionState.Cell c = cube.cells[slot++];
            if (c != null && c.created <= lastCell) {
                CellData snapshot = frozen.containsKey(c) ? frozen.get(c) : copy(c);
                frozen.put(c, COPIED);
                if (snapshot != null && snapshot != COPIED) {
                    // Keep the original cube/slot order even when mutations copied cells early.
                    data.cells.add(snapshot);
                }
            }
            return false;
        }

        void preserve(DimensionState.Cell c) {
            // Cells created after SAFE CUT belong to the next checkpoint.
            if (c != null && c.created <= lastCell && !frozen.containsKey(c)) {
                frozen.put(c, copy(c));
            }
        }

        private CellData copy(DimensionState.Cell c) {
            DimensionState.MassRecord mass = state.massRecord(c);
            if ((mass == null || mass.total() == 0)
                    && c.trapped.isEmpty()
                    && c.waiting.isEmpty()
                    && c.events.isEmpty()
                    && !sourceCells.contains(c.key)) {
                return null;
            }
            CellData d = new CellData();
            d.key = c.key;
            d.mass = mass;
            d.trapped.putAll(c.trapped);
            d.waiting.putAll(c.waiting);
            for (Map.Entry<Integer, DimensionState.Event> e : c.events.entrySet()) {
                DimensionState.Event v = new DimensionState.Event();
                v.add = e.getValue().add;
                v.remove = e.getValue().remove;
                d.events.put(e.getKey(), v);
            }
            return d;
        }
    }

    static final class Loaded {

        final Data data;

        final long generation;

        Loaded(Data data, long generation) {
            this.data = data;
            this.generation = generation;
        }
    }

    private static final class Manifest {

        long generation;

        byte[] hash;
    }

    static long write(Path dir, Data data, long expected) throws IOException {
        Files.createDirectories(dir);
        try (FileChannel lockChannel =
                        FileChannel.open(
                                dir.resolve("writer.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                FileLock lock = lockChannel.tryLock()) {
            if (lock == null) {
                throw new IOException("Checkpoint writer already active");
            }
            Path manifest = dir.resolve("manifest"), previous = dir.resolve("manifest.previous");
            Manifest current = Files.exists(manifest) ? readManifest(manifest) : null;
            long actual = current == null ? 0 : current.generation;
            if (actual != expected) {
                throw new IOException(
                        "Stale checkpoint writer: expected generation "
                                + expected
                                + ", current "
                                + actual);
            }
            long gen = Math.addExact(actual, 1);
            checkSize(data);
            byte[] payload = encode(data);
            if (payload.length > MAX_BYTES) {
                throw new IOException("Checkpoint exceeds 256 MiB format limit");
            }
            byte[] hash = sha(payload);
            Path target = dir.resolve("generation-" + gen + ".smog");
            atomicWrite(target, payload);
            // Retain only a verified previous generation. Corrupt current state is never promoted.
            long previousGeneration = 0;
            if (current != null) {
                try {
                    readGeneration(dir, current);
                    atomicWrite(previous, Files.readAllBytes(manifest));
                    previousGeneration = current.generation;
                } catch (IOException brokenCurrent) {
                    Manifest good = readManifest(previous);
                    readGeneration(dir, good);
                    previousGeneration = good.generation;
                }
            }
            forceDirectory(dir);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeLong(gen);
                out.write(hash);
            }
            atomicWrite(manifest, bytes.toByteArray());
            forceDirectory(dir);
            // Cleanup follows the durable manifest; failure only retains old files.
            try (DirectoryStream<Path> old = Files.newDirectoryStream(dir, "generation-*.smog")) {
                for (Path p : old) {
                    String name = p.getFileName().toString();
                    try {
                        long id = Long.parseLong(name.substring(11, name.length() - 5));
                        if (id != gen && id != previousGeneration) {
                            Files.deleteIfExists(p);
                        }
                    } catch (NumberFormatException ignored) {
                        /* unrelated name */
                    }
                }
            } catch (IOException ignored) {
                /* retain older valid generations */
            }
            return gen;
        } catch (OverlappingFileLockException e) {
            throw new IOException("Checkpoint writer already active", e);
        }
    }

    private static void atomicWrite(Path path, byte[] bytes) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (FileChannel out =
                    FileChannel.open(
                            tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer b = ByteBuffer.wrap(bytes);
                while (b.hasRemaining()) {
                    out.write(b);
                }
                out.force(true);
            }
            Files.move(
                    tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void forceDirectory(Path dir) throws IOException {
        // Windows cannot open directories through FileChannel. Data files are still
        // fsynced and manifest updates still use atomic rename; directory metadata
        // durability across abrupt power loss is platform-dependent on Windows.
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            return;
        }
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (AccessDeniedException | UnsupportedOperationException unsupported) {
            // Some filesystems do not support directory fsync. Never weaken the
            // fsync or atomic-rename requirements for actual checkpoint files.
        }
    }

    static Loaded read(Path dir) throws IOException {
        Manifest current = null;
        IOException failure;
        try {
            current = readManifest(dir.resolve("manifest"));
            return new Loaded(readGeneration(dir, current), current.generation);
        } catch (IOException e) {
            failure = e;
        }
        try {
            Manifest previous = readManifest(dir.resolve("manifest.previous"));
            return new Loaded(
                    readGeneration(dir, previous),
                    current == null ? previous.generation : current.generation);
        } catch (IOException e) {
            failure.addSuppressed(e);
            throw new IOException("No valid checkpoint generation in " + dir, failure);
        }
    }

    private static Manifest readManifest(Path path) throws IOException {
        if (Files.size(path) != 48) {
            throw new IOException("Invalid manifest size");
        }
        try (DataInputStream in = new DataInputStream(Files.newInputStream(path))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                throw new IOException("Unsupported manifest version");
            }
            Manifest m = new Manifest();
            m.generation = in.readLong();
            if (m.generation <= 0) {
                throw new IOException("Invalid generation");
            }
            m.hash = new byte[32];
            in.readFully(m.hash);
            return m;
        }
    }

    private static Data readGeneration(Path dir, Manifest m) throws IOException {
        Path path = dir.resolve("generation-" + m.generation + ".smog");
        long size = Files.size(path);
        if (size < 16 || size > MAX_BYTES) {
            throw new IOException("Invalid payload size");
        }
        byte[] bytes = Files.readAllBytes(path);
        if (!MessageDigest.isEqual(sha(bytes), m.hash)) {
            throw new IOException("Checkpoint checksum mismatch");
        }
        try {
            return decode(bytes);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new IOException("Invalid checkpoint values", e);
        }
    }

    private static byte[] sha(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void checkSize(Data d) throws IOException {
        long size = 256L + 24L * d.sources.size() + 20L * d.open.size();
        if (d.sources.size() > MAX_RECORDS
                || d.frames.size() > MAX_RECORDS
                || d.cells.size() > MAX_RECORDS) {
            throw new IOException("Too many checkpoint records");
        }
        for (DimensionState.Frame f : d.frames) {
            size += 12L + 20L * f.positions.length;
        }
        for (CellData c : d.cells) {
            size +=
                    25L
                            + (c.mass == null ? 0 : 1028L + 10L * c.mass.masses.length)
                            + 10L * (c.trapped.size() + c.waiting.size())
                            + 18L * c.events.size();
        }
        if (size > MAX_BYTES) {
            throw new IOException("Checkpoint exceeds 256 MiB format limit");
        }
    }

    private static byte[] encode(Data d) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(d.dimension);
            config(out, d.config);
            out.writeLong(d.ticks);
            out.writeLong(d.steps);
            out.writeLong(d.windows);
            out.writeLong(d.generation);
            out.writeInt(d.windowTick);
            out.writeInt(d.parts);
            out.writeBoolean(d.cycling);
            out.writeDouble(d.injected);
            out.writeDouble(d.escaped);
            out.writeDouble(d.rejected);
            out.writeDouble(d.removed);
            out.writeDouble(d.openRejected);
            out.writeInt(d.sources.size());
            for (DimensionState.Source s : d.sources) {
                pos(out, s.position);
                out.writeDouble(s.rate);
                out.writeInt(s.phase);
            }
            positions(out, d.open);
            out.writeInt(d.frames.size());
            for (DimensionState.Frame f : d.frames) {
                out.writeInt(f.positions.length);
                for (int i = 0; i < f.positions.length; i++) {
                    pos(out, f.positions[i]);
                    out.writeDouble(f.amounts[i]);
                }
                out.writeDouble(f.rejected);
            }
            out.writeInt(d.cells.size());
            for (CellData c : d.cells) {
                out.writeInt(c.key.x);
                out.writeInt(c.key.y);
                out.writeInt(c.key.z);
                out.writeBoolean(c.mass != null);
                if (c.mass != null) {
                    out.writeInt(c.mass.masses.length);
                    for (int i = 0; i < c.mass.masses.length; i++) {
                        out.writeShort(c.mass.volumes[i]);
                        out.writeDouble(c.mass.masses[i]);
                    }
                    for (int label : c.mass.labels) {
                        out.writeShort(label);
                    }
                }
                local(out, c.trapped);
                local(out, c.waiting);
                out.writeInt(c.events.size());
                for (Map.Entry<Integer, DimensionState.Event> e : c.events.entrySet()) {
                    out.writeShort(e.getKey());
                    out.writeDouble(e.getValue().add);
                    out.writeDouble(e.getValue().remove);
                }
            }
        }
        return bytes.toByteArray();
    }

    private static Data decode(byte[] bytes) throws IOException {
        Data d = new Data();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                throw new IOException("Unsupported checkpoint version");
            }
            d.dimension = in.readInt();
            d.config = config(in);
            d.ticks = counter(in);
            d.steps = counter(in);
            d.windows = counter(in);
            d.generation = counter(in);
            d.windowTick = in.readInt();
            d.parts = in.readInt();
            d.cycling = in.readBoolean();
            if (d.parts < 0
                    || (d.parts > 0
                            && d.ticks
                                    != Math.addExact(
                                            Math.multiplyExact(d.windows, (long) d.parts),
                                            d.windowTick))
                    || d.windowTick < 0
                    || (d.parts == 0 ? d.windowTick != 0 : d.windowTick >= d.parts)
                    || d.steps > d.windows) {
                throw new IOException("Invalid clocks");
            }
            d.injected = mass(in);
            d.escaped = mass(in);
            d.rejected = mass(in);
            d.removed = mass(in);
            d.openRejected = mass(in);
            Set<BlockPos> seen = new HashSet<>();
            int n = count(in, 24);
            for (int i = 0; i < n; i++) {
                BlockPos p = pos(in, d.dimension);
                if (!seen.add(p)) {
                    throw new IOException("Duplicate source");
                }
                double rate = mass(in);
                int phase = in.readInt();
                if (phase < 0 || phase >= d.config.cyclePeriod) {
                    throw new IOException("Invalid source phase");
                }
                d.sources.add(new DimensionState.Source(p, rate, phase));
            }
            positions(in, d.dimension, d.open);
            n = count(in, 12);
            for (int i = 0; i < n; i++) {
                int k = count(in, 20);
                BlockPos[] p = new BlockPos[k];
                double[] a = new double[k];
                seen.clear();
                for (int j = 0; j < k; j++) {
                    p[j] = pos(in, d.dimension);
                    a[j] = mass(in);
                    if (!seen.add(p[j])) {
                        throw new IOException("Duplicate frame position");
                    }
                }
                d.frames.add(new DimensionState.Frame(p, a, mass(in)));
            }
            if (d.frames.size() != d.windows - d.steps) {
                throw new IOException("Missing emission interval");
            }
            Set<CellKey> cells = new HashSet<>();
            n = count(in, 25);
            double accounted = d.escaped + d.removed;
            for (int i = 0; i < n; i++) {
                CellData c = new CellData();
                c.key = new CellKey(d.dimension, in.readInt(), in.readInt(), in.readInt());
                c.key.block(511);
                if (!cells.add(c.key)) {
                    throw new IOException("Duplicate cell");
                }
                if (in.readBoolean()) {
                    int size = in.readInt();
                    if (size < 0 || size > 512) {
                        throw new IOException("Invalid local node count");
                    }
                    int[] volumes = new int[size], counts = new int[size], labels = new int[512];
                    double[] masses = new double[size];
                    for (int j = 0; j < size; j++) {
                        volumes[j] = in.readUnsignedShort();
                        if (volumes[j] < 1 || volumes[j] > 512) {
                            throw new IOException("Invalid volume");
                        }
                        masses[j] = mass(in);
                        accounted += masses[j];
                    }
                    for (int p = 0; p < 512; p++) {
                        int label = in.readShort();
                        if (label < -1 || label >= size) {
                            throw new IOException("Invalid membership");
                        }
                        labels[p] = label;
                        if (label >= 0) {
                            counts[label]++;
                        }
                    }
                    if (!Arrays.equals(volumes, counts)) {
                        throw new IOException("Volume/membership mismatch");
                    }
                    c.mass = new DimensionState.MassRecord(labels, volumes, masses);
                }
                local(in, c.trapped);
                for (double m : c.trapped.values()) {
                    accounted += m;
                }
                local(in, c.waiting);
                int events = count(in, 18);
                if (events > 512) {
                    throw new IOException("Too many events");
                }
                for (int j = 0; j < events; j++) {
                    int p = in.readUnsignedShort();
                    if (p >= 512 || c.events.containsKey(p)) {
                        throw new IOException("Invalid event position");
                    }
                    DimensionState.Event e = new DimensionState.Event();
                    e.add = mass(in);
                    e.remove = mass(in);
                    c.events.put(p, e);
                }
                d.cells.add(c);
            }
            if (!Double.isFinite(accounted)
                    || Math.abs(accounted - d.injected) > 1e-8 * Math.max(1, d.injected)) {
                throw new IOException("Checkpoint ledger mismatch");
            }
            if (in.read() != -1) {
                throw new IOException("Trailing checkpoint bytes");
            }
        }
        return d;
    }

    private static int count(DataInputStream in, int minimumBytes) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX_RECORDS || n > (in.available() / Math.max(1, minimumBytes))) {
            throw new IOException("Invalid record count");
        }
        return n;
    }

    private static long counter(DataInputStream in) throws IOException {
        long n = in.readLong();
        if (n < 0) {
            throw new IOException("Negative clock");
        }
        return n;
    }

    private static double mass(DataInputStream in) throws IOException {
        double n = in.readDouble();
        if (n < 0 || !Double.isFinite(n)) {
            throw new IOException("Invalid mass");
        }
        return n;
    }

    private static void pos(DataOutputStream out, BlockPos p) throws IOException {
        out.writeInt(p.x);
        out.writeInt(p.y);
        out.writeInt(p.z);
    }

    private static BlockPos pos(DataInputStream in, int d) throws IOException {
        return new BlockPos(d, in.readInt(), in.readInt(), in.readInt());
    }

    private static void positions(DataOutputStream out, Map<BlockPos, Double> map)
            throws IOException {
        out.writeInt(map.size());
        for (Map.Entry<BlockPos, Double> e : map.entrySet()) {
            pos(out, e.getKey());
            out.writeDouble(e.getValue());
        }
    }

    private static void positions(DataInputStream in, int d, Map<BlockPos, Double> map)
            throws IOException {
        int n = count(in, 20);
        for (int i = 0; i < n; i++) {
            BlockPos p = pos(in, d);
            double m = mass(in);
            if (map.put(p, m) != null) {
                throw new IOException("Duplicate position");
            }
        }
    }

    private static void local(DataOutputStream out, Map<Integer, Double> map) throws IOException {
        out.writeInt(map.size());
        for (Map.Entry<Integer, Double> e : map.entrySet()) {
            out.writeShort(e.getKey());
            out.writeDouble(e.getValue());
        }
    }

    private static void local(DataInputStream in, Map<Integer, Double> map) throws IOException {
        int n = count(in, 10);
        if (n > 512) {
            throw new IOException("Too many local values");
        }
        for (int i = 0; i < n; i++) {
            int p = in.readUnsignedShort();
            double m = mass(in);
            if (p >= 512 || map.put(p, m) != null) {
                throw new IOException("Invalid local position");
            }
        }
    }

    private static void config(DataOutputStream o, SmogConfig c) throws IOException {
        o.writeInt(c.containerSize);
        o.writeInt(c.mode);
        o.writeInt(c.radius);
        o.writeInt(c.phase);
        o.writeInt(c.openMask);
        o.writeInt(c.law);
        o.writeDouble(c.diffusion);
        o.writeDouble(c.timeStep);
        o.writeDouble(c.maxMixing);
        o.writeDouble(c.maxDiffusivity);
        o.writeInt(c.cyclePeriod);
        o.writeInt(c.cycleOn);
        o.writeInt(c.cycleSteps);
        o.writeBoolean(c.narrowPathRefinement);
    }

    private static SmogConfig config(DataInputStream i) throws IOException {
        SmogConfig c =
                SmogConfig.builder()
                        .containerSize(i.readInt())
                        .mode(i.readInt())
                        .radius(i.readInt())
                        .phase(i.readInt())
                        .openMask(i.readInt())
                        .law(i.readInt())
                        .diffusion(i.readDouble())
                        .timeStep(i.readDouble())
                        .maxMixing(i.readDouble())
                        .maxDiffusivity(i.readDouble())
                        .cycle(i.readInt(), i.readInt(), i.readInt())
                        .narrowPathRefinement(i.readBoolean())
                        .build();
        if (c.containerSize != 8 || c.phase != 0 || c.law != SmogConfig.QUADRATIC_FAST) {
            throw new IOException("Unsupported sparse configuration");
        }
        return c;
    }

    static DimensionState restore(Data d, WorldSnapshotProvider provider) {
        DimensionState s = new DimensionState(d.dimension, d.config, provider);
        s.ticks = d.ticks;
        s.steps = d.steps;
        s.emissionWindows = d.windows;
        s.generation = d.generation;
        s.windowTick = d.windowTick;
        s.scheduleParts = d.parts;
        s.scheduleCycling = d.cycling;
        s.injected = d.injected;
        s.rejected = d.rejected;
        s.removed = d.removed;
        s.openRejected = d.openRejected;
        s.graph.sparseClocks(d.steps, d.escaped);
        s.frames.addAll(d.frames);
        s.openAccepted.putAll(d.open);
        for (CellData saved : d.cells) {
            DimensionState.Cell c = s.cell(saved.key, true);
            c.parked = saved.mass;
            c.trapped.putAll(saved.trapped);
            c.waiting.putAll(saved.waiting);
            c.events.putAll(saved.events);
            c.status = DimensionState.Status.RESTORE_PENDING;
            s.restoreEvents(c);
        }
        for (DimensionState.Source source : d.sources) {
            source.stamp = s.gameTime();
            s.sources.put(source.position, source);
            s.attachSource(source, s.cell(source.position.cell(), true));
        }
        for (DimensionState.Frame frame : d.frames) {
            for (BlockPos p : frame.positions) {
                s.cell(p.cell(), true);
            }
        }
        for (BlockPos p : d.open.keySet()) {
            s.cell(p.cell(), true);
        }
        s.frames.clear();
        for (DimensionState.Frame f : d.frames) {
            DimensionState.Source[] handles = new DimensionState.Source[f.positions.length];
            for (int i = 0; i < handles.length; i++) {
                handles[i] = s.sources.get(f.positions[i]);
            }
            s.frames.addLast(new DimensionState.Frame(f.positions, f.amounts, f.rejected, handles));
        }
        // No world snapshots, readiness probes, geometry, or topology at load time.
        return s;
    }
}

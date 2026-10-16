package smog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One sparse graph per dimension. All methods except returned save futures are confined to one
 * world/simulation thread. READY belongs to the provider; a clean READY cube has no record here.
 * Runtime IDs never escape into events.
 */
public final class DimensionState implements AutoCloseable {

    public enum Status {
        ABSENT,
        QUEUED,
        BUILDING,
        PUBLISHED,
        DIRTY,
        UNLOADING,
        PARKED,
        RESTORE_PENDING
    }

    public final int dimension;

    public final SmogConfig config;

    final WorldSnapshotProvider provider;

    final SmogGraph graph;

    final LinkedHashMap<CubeKey, CubeRecord> cubes = new LinkedHashMap<>();

    final LinkedHashMap<BlockPos, Source> sources = new LinkedHashMap<>();

    final ArrayDeque<Frame> frames = new ArrayDeque<>();

    final LinkedHashMap<BlockPos, Double> openAccepted = new LinkedHashMap<>();

    double openRejected, injected, rejected, removed;
    long ticks, steps, emissionWindows, generation;
    int windowTick, scheduleParts;

    boolean scheduleCycling;

    private final ArrayDeque<Cell> builds = new ArrayDeque<>(), fronts = new ArrayDeque<>();

    private final LinkedHashSet<Cell> eventCells = new LinkedHashSet<>();

    // Only one partition task runs at once. Prepared candidates retain results,
    // not a separate 40 KiB BFS workspace each; this bounds build allocation/GC.
    private Partitioner partitionWorkspace;

    final byte[] haloWorkspace = new byte[1728];

    private CellBuild job;

    private final ArrayDeque<CellBuild> prepared = new ArrayDeque<>();

    private Cell[] owners = new Cell[16];

    private boolean[] frontWatch = new boolean[16];

    private int watchedNodes;

    private Frame candidate;

    private int[] candidateNodes;

    private Source[] sourceRoster = new Source[0];

    private BlockPos[] rosterPositions = new BlockPos[0];

    private boolean rosterDirty = true;

    private final IntList changed = new IntList();

    public long lastGeometryNanos, lastLawNanos, lastSourceNanos, lastParkNanos, lastSnapshotNanos;
    public long buildNanos, rebuildNanos, contactNanos, publishNanos, parkNanos, snapshotNanos;
    public long maxPublishNanos, maxBuildUnitNanos, maxContactUnitNanos, maxSnapshotUnitNanos;
    public volatile long ioNanos, ioCpuNanos;
    public long staleJobs, publications, unloadCancellations, budgetOverruns;

    private Checkpoint.Capture capture;

    private long cellSequence;

    /** Both callbacks run on the world thread; attachments run on the I/O thread only. */
    public interface SaveParticipant {
        SaveAttachment capture();
    }

    public interface SaveAttachment {
        default void beforeWrite() {}

        void commit(Path core) throws Exception;
    }

    private SaveParticipant saveParticipant;

    private SaveAttachment saveAttachment;

    public long captureAttempts, captureAborts, captureWallNanos, captureCpuNanos;
    public long maxCaptureTickNanos, maxCaptureMutationNanos;

    private long captureStarted;

    private static final java.lang.management.ThreadMXBean CPU =
            java.lang.management.ManagementFactory.getThreadMXBean();

    private static long cpuTime() {
        return CPU.isCurrentThreadCpuTimeSupported() && CPU.isThreadCpuTimeEnabled()
                ? CPU.getCurrentThreadCpuTime()
                : -1;
    }

    private Path savePath, autoSavePath;

    private int autoSaveTicks = 6000;

    private CompletableFuture<Path> saveFuture;

    private volatile boolean writing;

    private boolean closed;

    private volatile long diskGeneration;

    private volatile Path diskPath;

    private final ExecutorService writer =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "smog-checkpoint");
                        t.setDaemon(true);
                        return t;
                    });

    static final class CubeRecord {

        final CubeKey key;

        final Cell[] cells = new Cell[8];

        final ArrayList<Source> sources = new ArrayList<>();

        long epoch = -1, revision = -1;

        boolean ready;

        CubeRecord(CubeKey key) {
            this.key = key;
        }
    }

    static final class Cell {

        final CellKey key;

        final CubeRecord cube;

        Geometry geometry;

        MassRecord parked;

        Source sourceHead;

        final TreeMap<Integer, Double> trapped = new TreeMap<>(), waiting = new TreeMap<>();

        final TreeMap<Integer, Event> events = new TreeMap<>();

        Status status = Status.ABSENT;

        long ticket;

        boolean queued, front, building;

        long created;

        int frontierFaces;

        Cell(CellKey key, CubeRecord cube) {
            this.key = key;
            this.cube = cube;
        }
    }

    static final class Geometry {

        int[] labels, ids, volumes;

        byte[] distance;

        CubeKey[] dependencies;

        long[] epochs, revisions;
    }

    /** Lossless local partition membership; no global node IDs or edges. */
    static final class MassRecord {

        final int[] labels, volumes;

        final double[] masses;

        MassRecord(int[] labels, int[] volumes, double[] masses) {
            this.labels = labels;
            this.volumes = volumes;
            this.masses = masses;
        }

        double total() {
            double sum = 0;
            for (double m : masses) {
                sum += m;
            }
            return sum;
        }
    }

    static final class Event {

        double add, remove;
    }

    static final class Source {

        final BlockPos position;

        final int local;

        Cell cell;

        Source nextInCell;

        int node = -1;

        double rate, stamp;

        int phase;

        boolean ready, air;

        Source(BlockPos p, double r, int phase) {
            position = p;
            local = p.local8();
            rate = r;
            this.phase = phase;
        }
    }

    static final class Frame {

        final BlockPos[] positions;

        final double[] amounts;

        final double rejected;

        final Source[] handles;

        Frame(Map<BlockPos, Double> values, double rejected) {
            positions = values.keySet().toArray(new BlockPos[0]);
            amounts = new double[positions.length];
            for (int i = 0; i < positions.length; i++) {
                amounts[i] = values.get(positions[i]);
            }
            this.rejected = rejected;
            handles = null;
        }

        Frame(BlockPos[] p, double[] a, double r) {
            this(p, a, r, null);
        }

        Frame(BlockPos[] p, double[] a, double r, Source[] h) {
            positions = p;
            amounts = a;
            rejected = r;
            handles = h;
        }

        double total() {
            double n = rejected;
            for (double a : amounts) {
                n += a;
            }
            return n;
        }
    }

    public DimensionState(int dimension, SmogConfig config, WorldSnapshotProvider provider) {
        if (config.containerSize != 8
                || config.phase != 0
                || config.law != SmogConfig.QUADRATIC_FAST) {
            throw new IllegalArgumentException(
                    "Sparse lifecycle requires Cell8, phase=0, QUADRATIC_FAST");
        }
        this.dimension = dimension;
        this.config = config;
        this.provider = Objects.requireNonNull(provider, "provider");
        graph = new SmogGraph(config);
    }

    private void check(int d) {
        if (d != dimension) {
            throw new IllegalArgumentException("Wrong dimension");
        }
        if (closed) {
            throw new IllegalStateException("Closed");
        }
    }

    Cell cell(CellKey key, boolean create) {
        CubeKey ck = key.cube();
        CubeRecord cube = cubes.get(ck);
        if (cube == null) {
            if (!create) {
                return null;
            }
            cube = new CubeRecord(ck);
            cubes.put(ck, cube);
        }
        int slot = key.slot();
        Cell c = cube.cells[slot];
        if (c == null && create) {
            cube.cells[slot] = c = new Cell(key, cube);
            c.created = ++cellSequence;
        }
        return c;
    }

    ImmutableCubeSnapshot snapshot(CubeKey key) {
        if (!provider.isReady(key)) {
            return null;
        }
        ImmutableCubeSnapshot s = provider.snapshotIfReady(key);
        if (s != null && !s.key.equals(key)) {
            throw new IllegalStateException("Provider returned wrong cube");
        }
        return s;
    }

    public Status status(CellKey key) {
        check(key.dimension);
        Cell c = cell(key, false);
        return c == null ? Status.ABSENT : c.status;
    }

    public void requestBuild(CellKey key) {
        check(key.dimension);
        Cell c = cell(key, true);
        if (c.geometry == null) {
            enqueue(c);
        }
    }

    void enqueue(Cell c) {
        if (c.building) {
            return;
        }
        if (!provider.isReady(c.cube.key)) {
            c.status = c.parked == null ? Status.ABSENT : Status.RESTORE_PENDING;
            return;
        }
        if (!c.queued) {
            c.queued = true;
            builds.addLast(c);
        }
        c.status = c.geometry == null ? Status.QUEUED : Status.DIRTY;
    }

    private void dirty(Cell c) {
        c.ticket++;
        enqueue(c);
    }

    void front(Cell c) {
        if (c.geometry != null && c.frontierFaces != 0 && !c.front) {
            c.front = true;
            fronts.addLast(c);
        }
    }

    public void onWorldCubeReady(CubeKey key, long epoch, long revision) {
        check(key.dimension);
        CubeRecord r = cubes.get(key);
        if (r != null) {
            if (epoch < r.epoch) {
                return;
            }
            boolean changed = !r.ready || r.epoch != epoch || r.revision != revision;
            refreshSources(r, true);
            r.epoch = epoch;
            r.revision = revision;
            r.ready = true;
            if (changed) {
                for (Cell c : r.cells) {
                    if (c != null) {
                        if (c.geometry != null) {
                            dirty(c);
                        } else {
                            enqueue(c);
                        }
                    }
                }
            }
        }
        notifyHalo(key, epoch, revision, true);
    }

    public void onWorldCubeReady(CubeKey key, long revision) {
        ImmutableCubeSnapshot s = snapshot(key);
        if (s != null) {
            onWorldCubeReady(key, s.epoch, revision);
        }
    }

    private void notifyHalo(CubeKey key, long epoch, long revision, boolean ready) {
        for (int z = key.z * 2 - 1; z <= key.z * 2 + 2; z++) {
            for (int y = key.y * 2 - 1; y <= key.y * 2 + 2; y++) {
                for (int x = key.x * 2 - 1; x <= key.x * 2 + 2; x++) {
                    Cell c = cell(new CellKey(dimension, x, y, z), false);
                    if (c == null || c.geometry == null) {
                        continue;
                    }
                    Geometry g = c.geometry;
                    for (int i = 0; i < g.dependencies.length; i++) {
                        if (g.dependencies[i].equals(key)) {
                            if (g.epochs[i] != (ready ? epoch : -1)
                                    || g.revisions[i] != (ready ? revision : -1)) {
                                dirty(c);
                            }
                            break;
                        }
                    }
                    refreshFrontMask(c);
                    if (ready) {
                        front(c);
                    }
                }
            }
        }
    }

    /**
     * Notify after making world data unavailable. Only unload cancels a solver candidate; its
     * immutable emission interval is retained and replayed once.
     */
    public void onWorldCubeUnloading(CubeKey key, long epoch) {
        check(key.dimension);
        CubeRecord r = cubes.get(key);
        if (r != null && epoch < r.epoch) {
            return;
        }
        long start = System.nanoTime();
        if (r != null) {
            abortCapture();
            refreshSources(r, false);
            boolean live = false;
            for (Cell c : r.cells) {
                if (c != null && c.geometry != null) {
                    live = true;
                }
            }
            if (live && graph.isStepInProgress()) {
                graph.cancelStep();
                candidate = null;
                unloadCancellations++;
            }
            r.ready = false;
            r.epoch = epoch;
            r.revision = -1;
            for (Cell c : r.cells) {
                if (c != null) {
                    c.ticket++;
                    c.status = Status.UNLOADING;
                    if (c.geometry != null) {
                        c.parked = massRecord(c);
                        for (int id : c.geometry.ids) {
                            removeOwner(id);
                            graph.removeNode(id);
                        }
                        c.geometry = null;
                        rebindSources(c);
                        generation++;
                    }
                    c.status = c.parked == null ? Status.ABSENT : Status.PARKED;
                }
            }
        }
        notifyHalo(key, epoch, -1, false);
        lastParkNanos = System.nanoTime() - start;
        parkNanos += lastParkNanos;
    }

    public void invalidateRegion(BlockPos min, BlockPos max) {
        check(min.dimension);
        check(max.dimension);
        if (min.x > max.x || min.y > max.y || min.z > max.z) {
            throw new IllegalArgumentException("Reversed region");
        }
        for (CubeRecord r : cubes.values()) {
            if ((long) r.key.x * 16 + 15 >= min.x
                    && (long) r.key.x * 16 <= max.x
                    && (long) r.key.y * 16 + 15 >= min.y
                    && (long) r.key.y * 16 <= max.y
                    && (long) r.key.z * 16 + 15 >= min.z
                    && (long) r.key.z * 16 <= max.z) {
                refreshSources(r, true);
            }
        }
        // Work is bounded by existing records even for huge WorldEdit selections.
        for (CubeRecord r : cubes.values()) {
            for (Cell c : r.cells) {
                if (c != null && c.geometry != null) {
                    BlockPos b = c.key.block(0);
                    if ((long) b.x + 9 >= min.x
                            && (long) b.x - 2 <= max.x
                            && (long) b.y + 9 >= min.y
                            && (long) b.y - 2 <= max.y
                            && (long) b.z + 9 >= min.z
                            && (long) b.z - 2 <= max.z) {
                        dirty(c);
                    }
                }
            }
        }
    }

    public void invalidateBlocks(Collection<BlockPos> changed) {
        HashSet<CubeKey> sourceCubes = new HashSet<>();
        for (BlockPos p : changed) {
            check(p.dimension);
            sourceCubes.add(p.cube());
        }
        for (CubeKey k : sourceCubes) {
            CubeRecord r = cubes.get(k);
            if (r != null) {
                refreshSources(r, true);
            }
        }
        HashSet<CellKey> dirty = new HashSet<>();
        for (BlockPos p : changed) {
            check(p.dimension);
            for (int z = -2; z <= 2; z++) {
                for (int y = -2; y <= 2; y++) {
                    for (int x = -2; x <= 2; x++) {
                        if (Math.abs(x) + Math.abs(y) + Math.abs(z) <= 2) {
                            dirty.add(p.offset(x, y, z).cell());
                        }
                    }
                }
            }
        }
        for (CellKey key : dirty) {
            Cell c = cell(key, false);
            if (c != null && c.geometry != null) {
                dirty(c);
            }
        }
    }

    void columnEvent(int x, int z, boolean unloading) {
        // Include existing adjacent records so a newly READY clean column resumes a front.
        Set<CubeKey> keys = new HashSet<>();
        for (CubeKey k : cubes.keySet()) {
            if (Math.abs((long) k.x - x) <= 1 && Math.abs((long) k.z - z) <= 1) {
                keys.add(new CubeKey(dimension, x, k.y, z));
            }
        }
        for (CubeKey k : keys) {
            CubeRecord r = cubes.get(k);
            if (unloading) {
                if (r != null) {
                    onWorldCubeUnloading(k, r.epoch);
                }
            } else {
                ImmutableCubeSnapshot s = snapshot(k);
                if (s != null) {
                    onWorldCubeReady(k, s.epoch, s.revision);
                }
            }
        }
    }

    public void setSourceRate(BlockPos p, double rate) {
        Source old = sources.get(p);
        int phase =
                old == null
                        ? (int) ((sources.size() * 2654435761L) % config.cyclePeriod)
                        : old.phase;
        setSourceRate(p, rate, phase);
    }

    public void setSourceRate(BlockPos p, double rate, int phase) {
        check(p.dimension);
        nonnegative(rate);
        if (phase < 0 || phase >= config.cyclePeriod) {
            throw new IllegalArgumentException("phase");
        }
        Source s = sources.get(p);
        if (s == null) {
            if (rate == 0) {
                return;
            }
            s = new Source(p, rate, phase);
            s.stamp = gameTime();
            sources.put(p, s);
            Cell c = cell(p.cell(), true);
            attachSource(s, c);
            rosterDirty = true;
            bindSource(s, snapshot(c.cube.key));
        } else {
            settleSource(s);
            s.rate = rate;
            s.phase = phase;
        }
        if (rate > 0) {
            Cell c = cell(p.cell(), true);
            if (c.geometry == null) {
                enqueue(c);
            }
        }
    }

    public boolean queueAddMass(BlockPos p, double amount) {
        check(p.dimension);
        nonnegative(amount);
        ImmutableCubeSnapshot s = snapshot(p.cube());
        if (s == null || !s.airAt(p.x, p.y, p.z)) {
            return false;
        }
        Cell c = cell(p.cell(), true);
        preserveForCapture(c);
        Event e = c.events.get(p.local8());
        if (e == null) {
            c.events.put(p.local8(), e = new Event());
        }
        nonnegative(e.add + amount);
        e.add += amount;
        eventCells.add(c);
        if (c.geometry == null) {
            enqueue(c);
        }
        return true;
    }

    public boolean queueRemoveMass(BlockPos p, double amount) {
        check(p.dimension);
        nonnegative(amount);
        Cell c = cell(p.cell(), false);
        if (c == null || c.geometry == null || c.geometry.labels[p.local8()] < 0) {
            return false;
        }
        preserveForCapture(c);
        Event e = c.events.get(p.local8());
        if (e == null) {
            c.events.put(p.local8(), e = new Event());
        }
        nonnegative(e.remove + amount);
        e.remove += amount;
        eventCells.add(c);
        return true;
    }

    static void nonnegative(double d) {
        if (d < 0 || !Double.isFinite(d)) {
            throw new IllegalArgumentException("Finite nonnegative mass/rate required");
        }
    }

    static void add(Map<Integer, Double> map, int p, double m) {
        if (m != 0) {
            map.put(p, map.getOrDefault(p, 0.0) + m);
        }
    }

    void attachSource(Source source, Cell c) {
        source.cell = c;
        source.nextInCell = c.sourceHead;
        c.sourceHead = source;
        c.cube.sources.add(source);
        bindNode(source);
    }

    private void bindNode(Source source) {
        Geometry g = source.cell.geometry;
        int label = g == null ? -1 : g.labels[source.local];
        source.node = label < 0 ? -1 : g.ids[label];
    }

    private void rebindSources(Cell c) {
        for (Source source = c.sourceHead; source != null; source = source.nextInCell) {
            bindNode(source);
        }
    }

    private void bindSource(Source source, ImmutableCubeSnapshot world) {
        source.ready = world != null;
        source.air =
                world != null
                        && world.airAt(source.position.x, source.position.y, source.position.z);
    }

    private void refreshSources(CubeRecord cube, boolean ready) {
        if (cube.sources.isEmpty()) {
            return;
        }
        ImmutableCubeSnapshot world = ready ? snapshot(cube.key) : null;
        for (Source source : cube.sources) {
            settleSource(source);
            bindSource(source, world);
        }
    }

    private double unaccounted(Source source) {
        if (!source.ready
                || (scheduleCycling
                        && ((emissionWindows / config.cycleSteps + source.phase)
                                        % config.cyclePeriod)
                                >= config.cycleOn)) {
            return 0;
        }
        return source.rate
                * Math.max(
                        0, gameTime() - Math.max(emissionWindows * config.timeStep, source.stamp));
    }

    private void settleSource(Source source) {
        double amount = unaccounted(source);
        if (source.air) {
            if (amount != 0) {
                openAccepted.put(
                        source.position, openAccepted.getOrDefault(source.position, 0.0) + amount);
            }
        } else {
            openRejected += amount;
        }
        source.stamp = gameTime();
    }

    void settleSources() {
        for (Source source : sources.values()) {
            settleSource(source);
        }
    }

    private void emissions(int parts, boolean cycling) {
        long start = System.nanoTime();
        ticks++;
        windowTick++;
        if (windowTick == parts) {
            // Rates/readiness are settled only at changes and interval boundaries.
            // No per-source lookup, allocation or world scan in the other 19 ticks.
            if (rosterDirty) {
                sourceRoster = sources.values().toArray(new Source[0]);
                rosterPositions = new BlockPos[sourceRoster.length];
                for (int i = 0; i < sourceRoster.length; i++) {
                    rosterPositions[i] = sourceRoster[i].position;
                }
                rosterDirty = false;
            }
            BlockPos[] positions = rosterPositions;
            Source[] handles = sourceRoster;
            double[] amounts = new double[positions.length];
            double end = gameTime(), base = emissionWindows * config.timeStep;
            boolean hasPartial = !openAccepted.isEmpty();
            for (int i = 0; i < handles.length; i++) {
                Source source = handles[i];
                double amount;
                if (source.stamp <= base) {
                    boolean active =
                            source.ready
                                    && (!cycling
                                            || ((emissionWindows / config.cycleSteps + source.phase)
                                                            % config.cyclePeriod)
                                                    < config.cycleOn);
                    amount = active ? source.rate * config.timeStep : 0;
                } else {
                    amount = unaccounted(source);
                }
                nonnegative(amount);
                amounts[i] =
                        (hasPartial ? openAccepted.getOrDefault(source.position, 0.0) : 0)
                                + (source.air ? amount : 0);
                if (!source.air) {
                    openRejected += amount;
                }
                source.stamp = end;
            }
            frames.addLast(new Frame(positions, amounts, openRejected, handles));
            openAccepted.clear();
            openRejected = 0;
            windowTick = 0;
            emissionWindows++;
        }
        lastSourceNanos = System.nanoTime() - start;
    }

    public boolean tick(long geometryCpuBudgetNanos) {
        return tick(geometryCpuBudgetNanos, 20, false);
    }

    public boolean tick(long budget, int parts, boolean cycling) {
        check(dimension);
        if (budget < 0 || parts < 1) {
            throw new IllegalArgumentException("Budget/parts");
        }
        if (scheduleParts != 0 && (scheduleParts != parts || scheduleCycling != cycling)) {
            throw new IllegalStateException(
                    "Fixed schedule required; use a new state to change parts");
        }
        scheduleParts = parts;
        scheduleCycling = cycling;
        boolean hadFrame = !frames.isEmpty();
        emissions(parts, cycling);
        geometry(budget);
        long start = System.nanoTime();
        boolean commit = false;
        if (capture == null && (hadFrame || parts == 1)) {
            commit = finishPendingPart(parts);
        }
        lastLawNanos = System.nanoTime() - start;
        if (autoSavePath != null
                && ticks % autoSaveTicks == 0
                && (saveFuture == null || saveFuture.isDone())) {
            requestSave(autoSavePath);
        }
        return commit;
    }

    /** Work without advancing game time, useful for deterministic shutdown/draining. */
    public boolean finishPendingPart(int parts) {
        if (capture != null) {
            return false;
        }
        if (graph.isStepInProgress() && scheduleParts != parts) {
            throw new IllegalStateException("parts changed");
        }
        if (!graph.isStepInProgress()) {
            applyEvents();
        }
        if (frames.isEmpty()) {
            return false;
        }
        if (candidate == null) {
            candidate = frames.peekFirst();
            if (candidateNodes == null || candidateNodes.length != candidate.positions.length) {
                candidateNodes = new int[candidate.positions.length];
            }
            for (int i = 0; i < candidateNodes.length; i++) {
                Source source = candidate.handles == null ? null : candidate.handles[i];
                if (source == null) {
                    candidateNodes[i] = node(candidate.positions[i]);
                } else {
                    candidateNodes[i] = source.node;
                }
            }
            graph.sparseSources(candidateNodes, candidate.amounts);
        }
        if (!graph.sparsePart(parts)) {
            return false;
        }
        for (int i = 0; i < candidateNodes.length; i++) {
            if (candidateNodes[i] >= 0) {
                injected += candidate.amounts[i];
            } else if (candidate.amounts[i] != 0) {
                BlockPos p = candidate.positions[i];
                Cell c = cell(p.cell(), true);
                add(c.waiting, p.local8(), candidate.amounts[i]);
                eventCells.add(c);
                if (c.geometry == null) {
                    enqueue(c);
                }
            }
        }
        rejected += candidate.rejected;
        frames.removeFirst();
        candidate = null;
        steps++;
        if (watchedNodes != 0) {
            changed.clear();
            graph.sparseChanges(changed, frontWatch);
            for (int i = 0; i < changed.size; i++) {
                Cell c = owners[changed.data[i]];
                if (c != null) {
                    front(c);
                }
            }
        }
        applyEvents();
        return true;
    }

    private int node(BlockPos p) {
        Cell c = cell(p.cell(), false);
        if (c == null || c.geometry == null) {
            return -1;
        }
        int local = c.geometry.labels[p.local8()];
        return local < 0 ? -1 : c.geometry.ids[local];
    }

    private void deliver(Cell c, int p, double amount) {
        int local = c.geometry.labels[p];
        if (local < 0) {
            add(c.trapped, p, amount);
        } else {
            graph.addNodeMass(c.geometry.ids[local], amount);
        }
        injected += amount;
    }

    void restoreEvents(Cell c) {
        if (!c.events.isEmpty() || !c.waiting.isEmpty()) {
            eventCells.add(c);
        }
    }

    private void applyEvents() {
        if (graph.isStepInProgress() || capture != null) {
            return;
        }
        Iterator<Cell> it = eventCells.iterator();
        while (it.hasNext()) {
            Cell c = it.next();
            if (c.geometry == null) {
                continue;
            }
            for (Map.Entry<Integer, Double> e : c.waiting.entrySet()) {
                deliver(c, e.getKey(), e.getValue());
            }
            c.waiting.clear();
            for (Map.Entry<Integer, Event> e : c.events.entrySet()) {
                int p = e.getKey();
                deliver(c, p, e.getValue().add);
                int local = c.geometry.labels[p];
                if (local >= 0) {
                    int id = c.geometry.ids[local];
                    double take = Math.min(graph.nodeMass(id), e.getValue().remove);
                    graph.addNodeMass(id, -take);
                    removed += take;
                }
            }
            c.events.clear();
            it.remove();
            front(c);
        }
    }

    public void geometry(long budget) {
        if (budget < 0) {
            throw new IllegalArgumentException("budget");
        }
        long start = System.nanoTime();
        lastSnapshotNanos = 0;
        long snapshotCpuStart = -1;
        while (System.nanoTime() - start < budget) {
            if (savePath != null && !writing && !graph.isStepInProgress()) {
                long s = System.nanoTime();
                if (snapshotCpuStart < 0) {
                    snapshotCpuStart = cpuTime();
                }
                if (capture == null) {
                    captureStarted = System.nanoTime();
                    captureAttempts++;
                    try {
                        capture = new Checkpoint.Capture(this);
                        // No game event can run between these two copies on the owner thread.
                        saveAttachment = saveParticipant == null ? null : saveParticipant.capture();
                    } catch (RuntimeException failure) {
                        capture = null;
                        savePath = null;
                        saveParticipant = null;
                        saveAttachment = null;
                        saveFuture.completeExceptionally(failure);
                        continue;
                    }
                }
                boolean done = capture.advance();
                long duration = System.nanoTime() - s;
                lastSnapshotNanos += duration;
                snapshotNanos += duration;
                maxSnapshotUnitNanos = Math.max(maxSnapshotUnitNanos, duration);
                if (done) {
                    if (snapshotCpuStart >= 0) {
                        captureCpuNanos += cpuTime() - snapshotCpuStart;
                        snapshotCpuStart = -1;
                    }
                    writeCapture();
                }
                continue;
            }
            if (!prepared.isEmpty()) {
                CellBuild b = prepared.peekFirst();
                if (b.cell.ticket != b.ticket || !provider.isReady(b.cell.cube.key)) {
                    prepared.removeFirst();
                    b.cell.building = false;
                    staleJobs++;
                    enqueue(b.cell);
                    continue;
                }
                if (!b.ready) {
                    advanceBuild(b);
                    continue;
                }
                if (!graph.isStepInProgress() && capture == null) {
                    if (!b.validWorld()) {
                        prepared.removeFirst();
                        b.cell.building = false;
                        staleJobs++;
                        enqueue(b.cell);
                        continue;
                    }
                    if (!b.validContacts()) {
                        b.refreshContacts();
                        continue;
                    }
                    long s = System.nanoTime();
                    publish(b);
                    long duration = System.nanoTime() - s;
                    publishNanos += duration;
                    maxPublishNanos = Math.max(maxPublishNanos, duration);
                    prepared.removeFirst();
                    b.cell.building = false;
                    applyEvents();
                    continue;
                }
            }
            if (job != null) {
                if (job.cell.ticket != job.ticket || !provider.isReady(job.cell.cube.key)) {
                    Cell c = job.cell;
                    c.building = false;
                    staleJobs++;
                    job = null;
                    enqueue(c);
                    continue;
                }
                if (job.ready) {
                    prepared.addLast(job);
                    job = null;
                } else {
                    advanceBuild(job);
                }
                continue;
            }
            if (!builds.isEmpty() && prepared.size() < 64) {
                Cell c = builds.removeFirst();
                c.queued = false;
                if (!provider.isReady(c.cube.key)) {
                    continue;
                }
                c.building = true;
                job = new CellBuild(this, c);
                c.status = c.geometry == null ? Status.BUILDING : Status.DIRTY;
                continue;
            }
            if (!fronts.isEmpty()) {
                Cell c = fronts.removeFirst();
                c.front = false;
                scanFront(c);
                continue;
            }
            break;
        }
        if (snapshotCpuStart >= 0) {
            captureCpuNanos += cpuTime() - snapshotCpuStart;
        }
        maxCaptureTickNanos = Math.max(maxCaptureTickNanos, lastSnapshotNanos);
        lastGeometryNanos = System.nanoTime() - start;
        if (budget > 0 && lastGeometryNanos > budget) {
            budgetOverruns++;
        }
    }

    Partitioner partitionWorkspace(PartitionTarget target) {
        if (partitionWorkspace == null) {
            partitionWorkspace = new Partitioner(target);
        } else {
            partitionWorkspace.retarget(target);
        }
        return partitionWorkspace;
    }

    private void advanceBuild(CellBuild b) {
        long start = System.nanoTime();
        boolean contacts = b.inContacts();
        b.advance();
        long duration = System.nanoTime() - start;
        if (contacts) {
            contactNanos += duration;
            maxContactUnitNanos = Math.max(maxContactUnitNanos, duration);
        } else {
            if (b.cell.geometry != null) {
                rebuildNanos += duration;
            } else {
                buildNanos += duration;
            }
            maxBuildUnitNanos = Math.max(maxBuildUnitNanos, duration);
        }
    }

    private void removeOwner(int id) {
        owners[id] = null;
        if (frontWatch[id]) {
            frontWatch[id] = false;
            watchedNodes--;
        }
    }

    private void refreshFrontMask(Cell c) {
        int mask = 0;
        for (int d = 0; d < 6; d++) {
            CellKey key = CellBuild.neighbor(c.key, d);
            Cell other = cell(key, false);
            if ((other == null || other.geometry == null) && provider.isReady(key.cube())) {
                mask |= 1 << d;
            }
        }
        c.frontierFaces = mask;
        if (c.geometry != null) {
            for (int id : c.geometry.ids) {
                boolean watch = mask != 0;
                if (frontWatch[id] != watch) {
                    frontWatch[id] = watch;
                    watchedNodes += watch ? 1 : -1;
                }
            }
        }
    }

    private void scanFront(Cell c) {
        if (c.geometry == null) {
            return;
        }
        for (int d = 0; d < 6; d++) {
            if ((c.frontierFaces & (1 << d)) == 0) {
                continue;
            }
            CellKey neighbor = CellBuild.neighbor(c.key, d);
            Cell other = cell(neighbor, false);
            if (other != null && other.geometry != null) {
                continue;
            }
            ImmutableCubeSnapshot world = snapshot(neighbor.cube());
            if (world == null) {
                continue;
            }
            for (int f = 0; f < 64; f++) {
                int p = CellBuild.face(d, f), local = c.geometry.labels[p];
                if (local < 0 || graph.nodeMass(c.geometry.ids[local]) <= 0) {
                    continue;
                }
                BlockPos q =
                        c.key.block(p).offset(CellBuild.DX[d], CellBuild.DY[d], CellBuild.DZ[d]);
                if (world.airAt(q.x, q.y, q.z)) {
                    requestBuild(neighbor);
                    break;
                }
            }
        }
    }

    MassRecord massRecord(Cell c) {
        if (c.geometry == null) {
            return c.parked;
        }
        Geometry g = c.geometry;
        double[] masses = new double[g.ids.length];
        for (int i = 0; i < masses.length; i++) {
            masses[i] = graph.nodeMass(g.ids[i]);
        }
        return new MassRecord(g.labels.clone(), g.volumes.clone(), masses);
    }

    private void publish(CellBuild b) {
        Cell c = b.cell;
        MassRecord old = massRecord(c);
        Geometry g = b.geometry;
        if (c.geometry != null) {
            for (int id : c.geometry.ids) {
                removeOwner(id);
                graph.removeNode(id);
            }
        }
        g.ids = new int[g.volumes.length];
        for (int i = 0; i < g.ids.length; i++) {
            int id = graph.newNode(b.local.seeds[i]);
            g.ids[i] = id;
            graph.setVolume(id, g.volumes[i]);
            if (id >= owners.length) {
                int size = Math.max(id + 1, owners.length * 2);
                owners = Arrays.copyOf(owners, size);
                frontWatch = Arrays.copyOf(frontWatch, size);
            }
            owners[id] = c;
            graph.sparseBoundary(id, b.boundary[i]);
        }
        if (old != null) {
            int[] survivors = new int[old.masses.length],
                    representative = new int[old.masses.length];
            for (int p = 0; p < 512; p++) {
                int a = old.labels[p];
                if (a >= 0) {
                    representative[a] = p;
                    if (g.labels[p] >= 0) {
                        survivors[a]++;
                    }
                }
            }
            // Same rule and voxel iteration order as legacy remapMass: divide the
            // ENTIRE old node mass over its surviving air, trap only if none survive.
            for (int p = 0; p < 512; p++) {
                int a = old.labels[p], v = g.labels[p];
                if (v >= 0 && a >= 0 && survivors[a] > 0) {
                    graph.addNodeMass(g.ids[v], old.masses[a] / survivors[a]);
                }
            }
            for (int i = 0; i < survivors.length; i++) {
                if (survivors[i] == 0) {
                    add(c.trapped, representative[i], old.masses[i]);
                }
            }
        }
        for (Iterator<Map.Entry<Integer, Double>> it = c.trapped.entrySet().iterator();
                it.hasNext(); ) {
            Map.Entry<Integer, Double> e = it.next();
            int local = g.labels[e.getKey()];
            if (local >= 0) {
                graph.addNodeMass(g.ids[local], e.getValue());
                it.remove();
            }
        }
        c.geometry = g;
        rebindSources(c);
        c.parked = null;
        c.status = Status.PUBLISHED;
        c.cube.ready = true;
        ImmutableCubeSnapshot self = b.self();
        c.cube.epoch = self.epoch;
        c.cube.revision = self.revision;
        b.edges.appendTo(
                config,
                (a, z, k) ->
                        graph.addEdge(
                                a < 512 ? g.ids[a] : a - 512, z < 512 ? g.ids[z] : z - 512, k));
        generation++;
        publications++;
        refreshFrontMask(c);
        front(c);
        for (int d = 0; d < 6; d++) {
            Cell other = cell(CellBuild.neighbor(c.key, d), false);
            if (other != null && other.geometry != null) {
                refreshFrontMask(other);
                front(other);
            }
        }
    }

    public double sample(BlockPos p) {
        int id = node(p);
        return id < 0 ? 0 : graph.nodeMass(id) / graph.volume[id];
    }

    public double liveMass() {
        return graph.mass();
    }

    public double parkedMass() {
        double n = 0;
        for (CubeRecord r : cubes.values()) {
            for (Cell c : r.cells) {
                if (c != null && c.parked != null) {
                    n += c.parked.total();
                }
            }
        }
        return n;
    }

    public double trappedMass() {
        double n = 0;
        for (CubeRecord r : cubes.values()) {
            for (Cell c : r.cells) {
                if (c != null) {
                    for (double a : c.trapped.values()) {
                        n += a;
                    }
                }
            }
        }
        return n;
    }

    public double pendingMass() {
        double n = openRejected;
        for (Source source : sources.values()) {
            n += unaccounted(source);
        }
        for (double a : openAccepted.values()) {
            n += a;
        }
        for (Frame f : frames) {
            n += f.total();
        }
        for (CubeRecord r : cubes.values()) {
            for (Cell c : r.cells) {
                if (c != null) {
                    for (double a : c.waiting.values()) {
                        n += a;
                    }
                    for (Event e : c.events.values()) {
                        n += e.add;
                    }
                }
            }
        }
        return n;
    }

    public double injectedMass() {
        return injected;
    }

    public double rejectedMass() {
        return rejected;
    }

    public double escapedMass() {
        return graph.escapedMass();
    }

    public double removedMass() {
        return removed;
    }

    public double generatedMass() {
        return injected + rejected + pendingMass();
    }

    public double balanceError() {
        return liveMass() + parkedMass() + trappedMass() + escapedMass() + removed - injected;
    }

    public long gameTicks() {
        return ticks;
    }

    public long iteration() {
        return steps;
    }

    public double gameTime() {
        return config.timeStep
                * (emissionWindows
                        + (scheduleParts == 0 ? 0 : (double) windowTick / scheduleParts));
    }

    public double modelTime() {
        return steps * config.timeStep;
    }

    public int cubeCount() {
        return cubes.size();
    }

    public int cellCount() {
        int n = 0;
        for (CubeRecord r : cubes.values()) {
            for (Cell c : r.cells) {
                if (c != null && c.geometry != null) {
                    n++;
                }
            }
        }
        return n;
    }

    public int nodeCount() {
        return graph.nodeCount();
    }

    public int edgeCount() {
        return graph.edgeCount();
    }

    public boolean geometryPending() {
        return job != null || !prepared.isEmpty() || !builds.isEmpty() || !fronts.isEmpty();
    }

    public int pendingIntervals() {
        return frames.size();
    }

    /** Diagnostics only. Canonical vertex identity is its complete sorted voxel membership. */
    public SortedMap<String, Double> topology() {
        HashMap<Integer, String> identity = new HashMap<>();
        for (CubeRecord r : cubes.values()) {
            for (Cell c : r.cells) {
                if (c != null && c.geometry != null) {
                    Geometry g = c.geometry;
                    StringBuilder[] b = new StringBuilder[g.ids.length];
                    for (int i = 0; i < b.length; i++) {
                        b[i] = new StringBuilder(c.key.toString()).append('/');
                    }
                    for (int p = 0; p < 512; p++) {
                        if (g.labels[p] >= 0) {
                            b[g.labels[p]].append(p).append(',');
                        }
                    }
                    for (int i = 0; i < b.length; i++) {
                        identity.put(g.ids[i], b[i].toString());
                    }
                }
            }
        }
        TreeMap<String, Double> out = new TreeMap<>();
        for (String s : identity.values()) {
            out.put("N:" + s, 0.0);
        }
        double[] e = graph.copyEdges();
        for (int i = 0; i < e.length; i += 3) {
            if (e[i] >= 0) {
                String a = identity.get((int) e[i]), b = identity.get((int) e[i + 1]);
                String k = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
                if (out.put("E:" + k, e[i + 2]) != null) {
                    throw new AssertionError("Duplicate edge");
                }
            }
        }
        return out;
    }

    /**
     * Startup adapter hook for cubes already READY before loading a checkpoint. Visits meaningful
     * saved records only, and enqueues work without building.
     */
    public void resumeReadyCubes() {
        for (CubeRecord r : new ArrayList<>(cubes.values())) {
            ImmutableCubeSnapshot world = snapshot(r.key);
            if (world != null) {
                onWorldCubeReady(r.key, world.epoch, world.revision);
            }
        }
    }

    public void autoSave(Path path, int everyTicks) {
        if (everyTicks < 1) {
            throw new IllegalArgumentException("interval");
        }
        autoSavePath = path;
        autoSaveTicks = everyTicks;
    }

    public CompletableFuture<Path> requestSave(Path path) {
        return requestSave(path, null);
    }

    public CompletableFuture<Path> requestSave(Path path, SaveParticipant participant) {
        check(dimension);
        if (saveFuture != null && !saveFuture.isDone()) {
            throw new IllegalStateException("Save already pending");
        }
        savePath = path.toAbsolutePath().normalize();
        saveFuture = new CompletableFuture<>();
        saveParticipant = participant;
        captureCpuNanos = 0;
        return saveFuture;
    }

    public String checkpointPhase() {
        if (writing) {
            return "WRITING";
        }
        if (capture != null) {
            return "CAPTURING";
        }
        return savePath == null ? "IDLE" : "REQUESTED";
    }

    public long checkpointGeneration() {
        return diskGeneration;
    }

    long cellSequence() {
        return cellSequence;
    }

    // Copy only a cell that is about to change. Ordinary emissions cannot starve a large capture.
    void preserveForCapture(Cell cell) {
        if (capture != null) {
            long start = System.nanoTime();
            capture.preserve(cell);
            maxCaptureMutationNanos = Math.max(maxCaptureMutationNanos, System.nanoTime() - start);
        }
    }

    private void abortCapture() {
        if (capture != null) {
            captureAborts++;
            capture = null;
            saveAttachment = null;
        }
    }

    private void writeCapture() {
        final Checkpoint.Data data = capture.data;
        final Path path = savePath;
        final CompletableFuture<Path> future = saveFuture;
        final SaveAttachment attachment = saveAttachment;
        final long expected = diskPath != null && diskPath.equals(path) ? diskGeneration : 0;
        capture = null;
        saveAttachment = null;
        saveParticipant = null;
        captureWallNanos = System.nanoTime() - captureStarted;
        writing = true;
        savePath = null;
        writer.execute(
                () -> {
                    long start = System.nanoTime();
                    java.lang.management.ThreadMXBean cpu =
                            java.lang.management.ManagementFactory.getThreadMXBean();
                    long cpuStart =
                            cpu.isCurrentThreadCpuTimeSupported()
                                    ? cpu.getCurrentThreadCpuTime()
                                    : -1;
                    Throwable failure = null;
                    try {
                        if (attachment != null) {
                            attachment.beforeWrite();
                        }
                        long gen = Checkpoint.write(path, data, expected);
                        if (attachment != null) {
                            attachment.commit(path);
                        }
                        synchronized (this) {
                            diskPath = path;
                            diskGeneration = gen;
                        }
                    } catch (Throwable e) {
                        failure = e;
                    } finally {
                        ioNanos += System.nanoTime() - start;
                        if (cpuStart >= 0 && ioCpuNanos >= 0) {
                            ioCpuNanos += cpu.getCurrentThreadCpuTime() - cpuStart;
                        } else {
                            ioCpuNanos = -1;
                        }
                        writing = false;
                    }
                    if (failure == null) {
                        future.complete(path);
                    } else {
                        future.completeExceptionally(failure);
                    }
                });
    }

    public static DimensionState load(Path path, WorldSnapshotProvider provider)
            throws IOException {
        Checkpoint.Loaded loaded = Checkpoint.read(path);
        DimensionState s = Checkpoint.restore(loaded.data, provider);
        s.diskPath = path.toAbsolutePath().normalize();
        s.diskGeneration = loaded.generation;
        return s;
    }

    /** Does not silently save or drain. Complete a requested save before close. */
    @Override
    public void close() {
        closed = true;
        writer.shutdown();
    }
}

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

    public enum GeometryState {
        UNVERIFIED,
        VALIDATED,
        DIRTY,
        INVALID
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
    private final ArrayDeque<Cell> criticalBuilds = new ArrayDeque<>();

    private final LinkedHashSet<Cell> eventCells = new LinkedHashSet<>();

    // Only one partition task runs at once. Prepared candidates retain results,
    // not a separate 40 KiB BFS workspace each; this bounds build allocation/GC.
    private Partitioner partitionWorkspace;

    final byte[] haloWorkspace = new byte[1728];

    private CellBuild job;

    private CellMassRemapper massRemapper;

    // Independent A/B switch; epsilon policy is identical with either setting.
    private static final boolean COLD_EXACT_MASS = Boolean
        .parseBoolean(System.getProperty("smog.coldExactMass", "true"));
    private static final int PUBLICATION_EPSILON = 1, PUBLICATION_COLD = 2;

    private final ArrayDeque<CellBuild> prepared = new ArrayDeque<>();

    // Restart-only diagnostic knob. Default 64 preserves the PerformanceFix scheduler.
    private static final int PREPARED_LIMIT = Math.max(8, Math.min(1024, Integer.getInteger("smog.preparedLimit", 64)));

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

    /** Runtime-only phase timings; reset does not affect the graph, scheduling or saves. */
    public final GeometryMetrics geometryMetrics = new GeometryMetrics();

    public final RestartMetrics restartMetrics = new RestartMetrics();
    final RestartOptions restartOptions = new RestartOptions();
    final StartupPipeline startup = new StartupPipeline(this);
    final GeometryValidator validator = new GeometryValidator(this);
    GeometryCache geometryCache;
    private RestorePlan restorePlan;
    private final ArrayDeque<RestorePlan> waitingRestores = new ArrayDeque<>();
    private final LinkedHashSet<Cell> pendingPublicationMass = new LinkedHashSet<>();
    private Throwable appendFailure;
    private int geometryUnits;

    private Checkpoint.Capture capture;

    private long cellSequence;

    /** Both callbacks run on the world thread; attachments run on the I/O thread only. */
    public interface SaveParticipant {

        SaveAttachment capture();
    }

    public interface SaveAttachment {

        default void beforeWrite() {}

        default void afterWrite() throws Exception {}

        void commit(Path core) throws Exception;
    }

    private SaveParticipant saveParticipant;

    private SaveAttachment saveAttachment;

    public long captureAttempts, captureAborts, captureWallNanos, captureCpuNanos;
    public long maxCaptureTickNanos, maxCaptureMutationNanos;

    private long captureStarted;

    private static final java.lang.management.ThreadMXBean CPU = java.lang.management.ManagementFactory
        .getThreadMXBean();

    private static long cpuTime() {
        return CPU.isCurrentThreadCpuTimeSupported() && CPU.isThreadCpuTimeEnabled() ? CPU.getCurrentThreadCpuTime()
            : -1;
    }

    private Path savePath, autoSavePath;

    private int autoSaveTicks = 6000;

    private CompletableFuture<Path> saveFuture;

    private volatile boolean writing;

    private boolean closed;

    private volatile long diskGeneration;

    private volatile Path diskPath;

    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
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

        int savedMask, readySavedMask;
        boolean restartChecked;

        CubeRecord(CubeKey key) {
            this.key = key;
        }
    }

    static final class Cell {

        final CellKey key;

        final CubeRecord cube;

        Geometry geometry;
        GeometryValidator.Task validation;
        GeometryState cacheState;

        MassRecord parked;
        MassRecord checkpointTracking;

        // Only the first publication of a checkpoint record may use coldExactMass.
        // Runtime unload/reload never sets this marker; it is not serialized.
        boolean checkpointRestorePending, reloadCacheEligible, cacheAttempted;

        Source sourceHead;

        final Map<Integer, Double> trapped = new LazyIntMap<>(), waiting = new LazyIntMap<>();

        final Map<Integer, Event> events = new LazyIntMap<>();

        Status status = Status.ABSENT;

        long ticket;

        boolean queued, front, building, criticalQueued;

        long created;

        // Transient queue timestamps in simulation ticks, never serialized.
        long diagnosticQueuedTick = -1, diagnosticPreparedTick = -1;

        int frontierFaces;
        int pendingMassPolicy;
        long publicationMassAfter;

        Cell(CellKey key, CubeRecord cube) {
            this.key = key;
            this.cube = cube;
        }
    }

    static final class Geometry {

        GeometryState verification = GeometryState.VALIDATED;

        VoxelLabels labels;
        int[] ids, volumes;

        VoxelDistance distance;

        CubeKey[] dependencies;

        long[] epochs, revisions;
        long activationEpoch;
        double[] checkpointMass;
    }

    /** Lossless local partition membership; no global node IDs or edges. */
    static final class MassRecord {

        private final short[] membership;
        private final short uniform;
        final int[] volumes;

        final double[] masses;

        MassRecord(int[] labels, int[] volumes, double[] masses) {
            if (labels.length != 512) throw new IllegalArgumentException("Cell8 membership size");
            boolean same = true;
            for (int p = 1; p < 512 && same; p++) same = labels[p] == labels[0];
            uniform = (short) labels[0];
            membership = same ? null : new short[512];
            if (!same) for (int i = 0; i < 512; i++) membership[i] = (short) labels[i];
            this.volumes = volumes;
            this.masses = masses;
        }

        MassRecord(VoxelLabels labels, int[] volumes, double[] masses) {
            int first = labels.get(0);
            boolean same = true;
            for (int p = 1; p < 512 && same; p++) same = labels.get(p) == first;
            uniform = (short) first;
            membership = same ? null : new short[512];
            if (!same) for (int p = 0; p < 512; p++) membership[p] = (short) labels.get(p);
            this.volumes = volumes;
            this.masses = masses;
        }

        private MassRecord(short[] labels, short uniform, int[] volumes, double[] masses) {
            this.membership = labels;
            this.uniform = uniform;
            this.volumes = volumes;
            this.masses = masses;
        }

        int labelAt(int p) {
            return membership == null ? uniform : membership[p];
        }

        int[] labels() {
            int[] result = new int[512];
            for (int i = 0; i < 512; i++) result[i] = labelAt(i);
            return result;
        }

        boolean sameLabels(int[] labels) {
            for (int p = 0; p < 512; p++) if (labelAt(p) != labels[p]) return false;
            return true;
        }

        boolean sameLabels(VoxelLabels labels) {
            for (int p = 0; p < 512; p++) if (labelAt(p) != labels.get(p)) return false;
            return true;
        }

        MassRecord withMasses(double[] values) {
            return new MassRecord(membership, uniform, volumes, values);
        }

        long bytes() {
            return 80L + (membership == null ? 0 : 1040L) + 12L * volumes.length;
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
        private Set<CellKey> contributionCells;

        Frame(Map<BlockPos, Double> values, double rejected) {
            positions = values.keySet()
                .toArray(new BlockPos[0]);
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

        /** Built once per immutable frame, never from mutable Source.node bindings. */
        boolean contributesTo(CellKey key) {
            if (positions.length == 0) return false;
            if (contributionCells == null) {
                contributionCells = new HashSet<>();
                for (int i = 0; i < positions.length; i++)
                    if (amounts[i] != 0) contributionCells.add(positions[i].cell());
            }
            return contributionCells.contains(key);
        }
    }

    public DimensionState(int dimension, SmogConfig config, WorldSnapshotProvider provider) {
        if (config.containerSize != 8 || config.phase != 0 || config.law != SmogConfig.QUADRATIC_FAST) {
            throw new IllegalArgumentException("Sparse lifecycle requires Cell8, phase=0, QUADRATIC_FAST");
        }
        this.dimension = dimension;
        this.config = config;
        this.provider = Objects.requireNonNull(provider, "provider");
        graph = new SmogGraph(config);
    }

    private void check(int d) {
        if (appendFailure != null) throw new IllegalStateException(
            "SMOG dimension " + dimension + " stopped after failed epoch append; refusing simulation/checkpoint",
            appendFailure);
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
            startup.register(cube);
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
        if (c.checkpointRestorePending && c.cube.readySavedMask != c.cube.savedMask) startup.ready(c.cube, true);
        if (epochRestoreEnabled() && !c.checkpointRestorePending
            && !c.reloadCacheEligible
            && !c.criticalQueued
            && (c.geometry != null || c.sourceHead != null || !c.events.isEmpty() || !c.waiting.isEmpty())) {
            c.criticalQueued = true;
            criticalBuilds.addLast(c);
        }
        if (!c.queued) {
            if (c.diagnosticQueuedTick < 0) {
                c.diagnosticQueuedTick = ticks;
            }
            c.queued = true;
            if (c.checkpointRestorePending) builds.addFirst(c);
            else builds.addLast(c);
        }
        c.status = c.geometry == null ? Status.QUEUED : Status.DIRTY;
    }

    private void dirty(Cell c) {
        validator.cancel(c);
        if (c.geometry != null) c.geometry.verification = GeometryState.DIRTY;
        c.cacheState = GeometryState.DIRTY;
        // Session edits must not restart the same stale saved candidate.
        c.cacheAttempted = true;
        c.ticket++;
        enqueue(c);
    }

    void validationDirty(Cell c) {
        dirty(c);
    }

    public GeometryState geometryState(CellKey key) {
        check(key.dimension);
        Cell c = cell(key, false);
        return c == null ? null : c.geometry == null ? c.cacheState : c.geometry.verification;
    }

    /** Readiness registration without building a Minecraft snapshot on the restore path. */
    void onWorldCubeAvailable(CubeKey key) {
        CubeRecord r = cubes.get(key);
        if (!provider.isReady(key)) return;
        if (r != null) {
            r.ready = true;
            refreshSources(r, true);
            startup.ready(r, true);
            for (Cell c : r.cells) if (c != null && c.geometry == null) enqueue(c);
        }
        // A clean READY section has no CubeRecord, but still supplies a halo/front.
        notifyHalo(key, -1, -1, true);
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
        boolean sessionEdit = false;
        if (r != null) {
            if (epoch < r.epoch) {
                return;
            }
            sessionEdit = r.ready && r.epoch == epoch && r.revision >= 0 && revision > r.revision;
            boolean changed = !r.ready || r.epoch != epoch || r.revision != revision;
            refreshSources(r, true);
            r.epoch = epoch;
            r.revision = revision;
            r.ready = true;
            startup.ready(r, true);
            {
                for (Cell c : r.cells) {
                    if (c != null) {
                        if (sessionEdit && (c.geometry != null || c.building)) continue;
                        if (c.geometry != null && c.geometry.verification == GeometryState.UNVERIFIED) {
                            validator.wake(c);
                        } else if (c.geometry != null && changed) {
                            dirty(c);
                        } else if (c.geometry == null) {
                            enqueue(c);
                        }
                    }
                }
            }
        }
        if (sessionEdit) {
            notifyHalo(key, epoch, revision, true, true);
            return;
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
        notifyHalo(key, epoch, revision, ready, false);
    }

    private void notifyHalo(CubeKey key, long epoch, long revision, boolean ready, boolean edited) {
        for (int z = key.z * 2 - 1; z <= key.z * 2 + 2; z++) {
            for (int y = key.y * 2 - 1; y <= key.y * 2 + 2; y++) {
                for (int x = key.x * 2 - 1; x <= key.x * 2 + 2; x++) {
                    Cell c = cell(new CellKey(dimension, x, y, z), false);
                    if (c == null) continue;
                    if (edited && (c.geometry != null || c.building || c.checkpointRestorePending)) dirty(c);
                    if (c.geometry == null) {
                        continue;
                    }
                    Geometry g = c.geometry;
                    for (int i = 0; !edited && i < g.dependencies.length; i++) {
                        if (g.dependencies[i].equals(key)) {
                            if (g.verification == GeometryState.UNVERIFIED) {
                                validator.wake(c);
                            } else if (g.epochs == null) {
                                dirty(c);
                            } else if (ready && epoch < 0) {
                                // Index discovery has no current revision. A dependency that
                                // was already checked ready must not be dirtied by discovery.
                                if (g.epochs[i] < 0) dirty(c);
                            } else
                                if (g.epochs[i] != (ready ? epoch : -1) || g.revisions[i] != (ready ? revision : -1)) {
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
            startup.ready(r, false);
            r.epoch = epoch;
            r.revision = -1;
            for (Cell c : r.cells) {
                if (c != null) {
                    c.ticket++;
                    validator.cancel(c);
                    c.status = Status.UNLOADING;
                    if (c.geometry != null) {
                        preserveCheckpointProgress(c);
                        c.reloadCacheEligible = c.geometry.verification != GeometryState.DIRTY
                            && c.geometry.verification != GeometryState.INVALID;
                        c.cacheAttempted = !c.reloadCacheEligible;
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
                    if (c.trapped.isEmpty()) c.trapped.clear();
                    if (c.waiting.isEmpty()) c.waiting.clear();
                    if (c.events.isEmpty()) c.events.clear();
                    c.diagnosticQueuedTick = c.diagnosticPreparedTick = -1;
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
            if ((long) r.key.x * 16 + 15 >= min.x && (long) r.key.x * 16 <= max.x
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
                if (c != null && (c.geometry != null || c.building || c.checkpointRestorePending)) {
                    BlockPos b = c.key.block(0);
                    if ((long) b.x + 9 >= min.x && (long) b.x - 2 <= max.x
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
                            dirty.add(
                                p.offset(x, y, z)
                                    .cell());
                        }
                    }
                }
            }
        }
        for (CellKey key : dirty) {
            Cell c = cell(key, false);
            if (c != null && (c.geometry != null || c.building || c.checkpointRestorePending)) {
                dirty(c);
            }
        }
    }

    void columnEvent(int x, int z, boolean unloading) {
        startup.columnEvent(x, z, unloading);
    }

    public void setSourceRate(BlockPos p, double rate) {
        Source old = sources.get(p);
        int phase = old == null ? (int) ((sources.size() * 2654435761L) % config.cyclePeriod) : old.phase;
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
        if (c == null || c.geometry == null || c.geometry.labels.get(p.local8()) < 0) {
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
        int label = g == null ? -1 : g.labels.get(source.local);
        source.node = label < 0 ? -1 : g.ids[label];
    }

    private void rebindSources(Cell c) {
        for (Source source = c.sourceHead; source != null; source = source.nextInCell) {
            bindNode(source);
        }
    }

    private void bindSource(Source source, ImmutableCubeSnapshot world) {
        source.ready = world != null;
        source.air = world != null && world.airAt(source.position.x, source.position.y, source.position.z);
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
        if (!source.ready || (scheduleCycling
            && ((emissionWindows / config.cycleSteps + source.phase) % config.cyclePeriod) >= config.cycleOn)) {
            return 0;
        }
        return source.rate * Math.max(0, gameTime() - Math.max(emissionWindows * config.timeStep, source.stamp));
    }

    private void settleSource(Source source) {
        double amount = unaccounted(source);
        if (source.air) {
            if (amount != 0) {
                openAccepted.put(source.position, openAccepted.getOrDefault(source.position, 0.0) + amount);
            }
        } else {
            openRejected += amount;
        }
        source.stamp = gameTime();
    }

    void settleSources() {
        check(dimension);
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
                sourceRoster = sources.values()
                    .toArray(new Source[0]);
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
                    boolean active = source.ready
                        && (!cycling || ((emissionWindows / config.cycleSteps + source.phase) % config.cyclePeriod)
                            < config.cycleOn);
                    amount = active ? source.rate * config.timeStep : 0;
                } else {
                    amount = unaccounted(source);
                }
                nonnegative(amount);
                amounts[i] = (hasPartial ? openAccepted.getOrDefault(source.position, 0.0) : 0)
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
        return tick(budget, parts, cycling, Long.MAX_VALUE);
    }

    /** Adapter grant includes work-based restore boosts and background validation. */
    public boolean tick(long budget, int parts, boolean cycling, long geometryCap) {
        check(dimension);
        if (budget < 0 || geometryCap < 0 || parts < 1) {
            throw new IllegalArgumentException("Budget/parts");
        }
        if (scheduleParts != 0 && (scheduleParts != parts || scheduleCycling != cycling)) {
            throw new IllegalStateException("Fixed schedule required; use a new state to change parts");
        }
        scheduleParts = parts;
        scheduleCycling = cycling;
        boolean hadFrame = !frames.isEmpty();
        emissions(parts, cycling);
        long normalBudget = restoreWorkReady() ? Math.max(budget, restartOptions.restoreBudget) : budget;
        if (frames.size() > 1) normalBudget = budget;
        long effectiveBudget = Math.min(geometryCap, normalBudget);
        geometry(effectiveBudget);
        if (geometryCache != null) geometryCache.syncMetrics(restartMetrics);
        long start = System.nanoTime();
        long postGeometry = 0;
        boolean commit = false;
        if (capture == null && (hadFrame || parts == 1)) {
            geometryMetrics.partCalls++;
            commit = finishPendingPart(parts);
            if (commit) {
                geometryMetrics.partCommits++;
                // Use the safe boundary that just opened, before another solver step
                // can start on the next tick. Charge the same total geometry budget;
                // never cancel, skip or postpone a physical frame for restoration.
                long firstGeometry = lastGeometryNanos;
                long remaining = effectiveBudget - firstGeometry - (System.nanoTime() - start);
                if (remaining > 0 && geometryPending()) {
                    geometry(remaining);
                    postGeometry = lastGeometryNanos;
                    lastGeometryNanos += firstGeometry;
                }
            }
        } else if (capture != null && hadFrame) {
            geometryMetrics.partsSkippedForCapture++;
        }
        lastLawNanos = System.nanoTime() - start - postGeometry;
        restartMetrics.set("solver_pending_frames", frames.size());
        restartMetrics
            .set("solver_oldest_frame_ticks", frames.isEmpty() ? 0 : Math.max(0, ticks - (steps + 1) * parts));
        if (graph.isStepInProgress() || capture != null) {
            geometryMetrics.safeWindow(false);
        }
        if (autoSavePath != null && ticks % autoSaveTicks == 0 && (saveFuture == null || saveFuture.isDone())) {
            requestSave(autoSavePath);
        }
        return commit;
    }

    /** Work without advancing game time, useful for deterministic shutdown/draining. */
    public boolean finishPendingPart(int parts) {
        check(dimension);
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
        boolean complete = graph.sparsePart(parts);
        if (graph.isStepInProgress()) {
            geometryMetrics.safeWindow(false);
        }
        if (!complete) {
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
        int local = c.geometry.labels.get(p.local8());
        return local < 0 ? -1 : c.geometry.ids[local];
    }

    private void deliver(Cell c, int p, double amount) {
        int local = c.geometry.labels.get(p);
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

    void applyEvents() {
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
                int local = c.geometry.labels.get(p);
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
        for (Iterator<Cell> pending = pendingPublicationMass.iterator(); pending.hasNext();) {
            Cell c = pending.next();
            if (c.geometry != null && steps >= c.publicationMassAfter) {
                int policy = c.pendingMassPolicy;
                c.pendingMassPolicy = 0;
                pending.remove();
                finishPublicationMass(c, policy);
            }
        }
    }

    public void geometry(long budget) {
        check(dimension);
        if (budget < 0) {
            throw new IllegalArgumentException("budget");
        }
        long start = System.nanoTime();
        validator.advance(Math.min(restartOptions.validationBudget, budget / 4));
        if (geometryCache != null) {
            geometryCache.pump(restartMetrics);
            startup.prefetch(geometryCache);
        }
        // Validation may consume its entire slice. Discovery needs a separate
        // bounded slice, or ready saved sections can remain unchecked indefinitely.
        startup.discover(System.nanoTime() + Math.min(250000L, budget / 8));
        lastSnapshotNanos = 0;
        long snapshotCpuStart = -1;
        boolean noEligibleWork = false;
        boolean eligibleCall = !graph.isStepInProgress() && capture == null && (savePath == null || writing);
        int preparedAtStart = prepared.size();
        int restoreScans = 0, restoreScanLimit = waitingRestores.size();
        int frontUnits = 0;
        boolean restorePriority = epochRestoreEnabled() && restartOptions.epochFrontLimit && startup.available >= 256;
        geometryMetrics.beginSafeCall(eligibleCall);
        if (eligibleCall) {
            geometryMetrics.safeWindow(true);
            geometryMetrics.eligibleGeometryCalls++;
        } else {
            geometryMetrics.safeWindow(false);
        }
        while (System.nanoTime() - start < budget) {
            geometryUnits++;
            // A bounded critical unit every 64 preparation units, distinct from the
            // expansion quota. No scan of the saved territory or distance to players.
            if (epochRestoreEnabled() && (geometryUnits & 63) == 32 && advanceCriticalUnit()) continue;
            // A continuous stream of saved sections must leave progress for fronts.
            if (geometryUnits % 64 == 0 && !fronts.isEmpty()
                && savePath == null
                && (!restorePriority || frontUnits < restartOptions.frontQuota)) {
                frontUnits++;
                Cell c = fronts.removeFirst();
                c.front = false;
                long frontStart = System.nanoTime();
                scanFront(c);
                geometryMetrics.front(System.nanoTime() - frontStart);
                continue;
            }
            if (savePath != null && !writing && !graph.isStepInProgress() && publicationAccountingReady()) {
                geometryMetrics.safeWindow(false);
                long s = System.nanoTime();
                if (snapshotCpuStart < 0) {
                    snapshotCpuStart = cpuTime();
                }
                if (capture == null) {
                    applyEvents(); // Includes deferred publication epsilon before copying ledgers.
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
                geometryMetrics.capture(duration);
                if (done) {
                    if (snapshotCpuStart >= 0) {
                        captureCpuNanos += cpuTime() - snapshotCpuStart;
                        snapshotCpuStart = -1;
                    }
                    writeCapture();
                }
                continue;
            }
            if (restorePlan == null && restoreScans < restoreScanLimit && !waitingRestores.isEmpty()) {
                restoreScans++;
                restorePlan = waitingRestores.removeFirst();
            }
            if (startup.pending() && (builds.isEmpty() || !builds.peekFirst().checkpointRestorePending)
                && restorePlan == null
                && waitingRestores.size() < restartOptions.preparedCubes) {
                startup.advance();
                continue;
            }
            if (restorePlan != null) {
                int progress = restorePlan.advance();
                if (progress == 0) {
                    restorePlan = null;
                    continue;
                }
                if (progress > 0) continue;
                // A private plan holds no graph mutations. Its cells remain building,
                // preventing duplicate work. Visit each deferred plan once per call;
                // IO/solver waits cannot turn into an unbounded polling loop.
                waitingRestores.addLast(restorePlan);
                restorePlan = null;
                continue;
            }
            if (!prepared.isEmpty()) {
                CellBuild b = prepared.peekFirst();
                if (b.cell.ticket != b.ticket || !provider.isReady(b.cell.cube.key)) {
                    prepared.removeFirst();
                    b.cell.diagnosticPreparedTick = -1;
                    b.cell.building = false;
                    staleJobs++;
                    geometryMetrics.staleJobs++;
                    enqueue(b.cell);
                    continue;
                }
                if (!b.ready) {
                    advanceBuild(b);
                    continue;
                }
                if (!graph.isStepInProgress() && capture == null) {
                    long validationStart = System.nanoTime();
                    boolean validWorld = b.validWorld();
                    geometryMetrics.worldValidation(System.nanoTime() - validationStart, validWorld);
                    if (!validWorld) {
                        prepared.removeFirst();
                        b.cell.diagnosticPreparedTick = -1;
                        b.cell.building = false;
                        staleJobs++;
                        geometryMetrics.staleJobs++;
                        enqueue(b.cell);
                        continue;
                    }
                    validationStart = System.nanoTime();
                    boolean validContacts = b.validContacts();
                    geometryMetrics.contactValidation(System.nanoTime() - validationStart, validContacts);
                    if (!validContacts) {
                        long refreshStart = System.nanoTime();
                        b.refreshContacts();
                        geometryMetrics.refreshed(System.nanoTime() - refreshStart);
                        b.diagnosticRefreshingContacts = true;
                        continue;
                    }
                    geometryMetrics.safeWindow(true);
                    long s = System.nanoTime();
                    int massPolicy = publish(b);
                    long duration = System.nanoTime() - s;
                    geometryMetrics.publishedWait(
                        b.cell.diagnosticQueuedTick < 0 ? -1 : Math.max(0, ticks - b.cell.diagnosticQueuedTick),
                        b.cell.diagnosticPreparedTick < 0 ? -1 : Math.max(0, ticks - b.cell.diagnosticPreparedTick));
                    prepared.removeFirst();
                    b.cell.diagnosticQueuedTick = b.cell.diagnosticPreparedTick = -1;
                    b.cell.building = false;
                    long eventsStart = System.nanoTime();
                    applyEvents();
                    geometryMetrics.publicationEvents(System.nanoTime() - eventsStart);
                    long epsilonStart = System.nanoTime();
                    finishPublicationMass(b.cell, massPolicy);
                    duration += System.nanoTime() - epsilonStart;
                    publishNanos += duration;
                    maxPublishNanos = Math.max(maxPublishNanos, duration);
                    geometryMetrics.publish(duration);
                    startup.published(b.cell, false);
                    continue;
                }
            }
            if (job != null) {
                if (job.cell.ticket != job.ticket || !provider.isReady(job.cell.cube.key)) {
                    Cell c = job.cell;
                    c.building = false;
                    staleJobs++;
                    geometryMetrics.staleJobs++;
                    job = null;
                    enqueue(c);
                    continue;
                }
                if (job.ready) {
                    prepared.addLast(job);
                    job.cell.diagnosticPreparedTick = ticks;
                    geometryMetrics.preparedAccepted++;
                    job = null;
                } else {
                    advanceBuild(job);
                }
                continue;
            }
            if (!builds.isEmpty() && prepared.size() < PREPARED_LIMIT
                && waitingRestores.size() < restartOptions.preparedCubes) {
                Cell c = builds.removeFirst();
                if (!c.queued) continue;
                if (!provider.isReady(c.cube.key)) {
                    c.queued = false;
                    continue;
                }
                if ((c.checkpointRestorePending || c.reloadCacheEligible) && !c.cacheAttempted
                    && geometryCache != null
                    && provider.isReady(c.cube.key)) {
                    restorePlan = epochRestoreEnabled() ? new EpochRestorePlan(this, c) : new FastRestorePlan(this, c);
                    continue;
                }
                c.queued = false;
                if (!provider.isReady(c.cube.key)) {
                    continue;
                }
                c.building = true;
                if (c.geometry == null) {
                    geometryMetrics.newJobs++;
                } else {
                    geometryMetrics.rebuildJobs++;
                }
                job = new CellBuild(this, c);
                geometryMetrics.jobsStarted++;
                c.status = c.geometry == null ? Status.BUILDING : Status.DIRTY;
                continue;
            }
            if (!fronts.isEmpty() && (!restorePriority || frontUnits < restartOptions.frontQuota)) {
                frontUnits++;
                Cell c = fronts.removeFirst();
                c.front = false;
                long frontStart = System.nanoTime();
                scanFront(c);
                geometryMetrics.front(System.nanoTime() - frontStart);
                continue;
            }
            noEligibleWork = true;
            break;
        }
        if (noEligibleWork) {
            geometryMetrics.idleEndCalls++;
            if (geometryPending() || savePath != null) {
                geometryMetrics.idleWithBacklogCalls++;
                if (!prepared.isEmpty() && prepared.peekFirst().ready) {
                    if (graph.isStepInProgress()) {
                        geometryMetrics.waitSolverCalls++;
                    }
                    if (capture != null) {
                        geometryMetrics.waitCaptureCalls++;
                    }
                }
                if (prepared.size() >= PREPARED_LIMIT && !builds.isEmpty()) {
                    geometryMetrics.preparedFullCalls++;
                }
            }
        } else if (budget > 0) {
            geometryMetrics.budgetEndCalls++;
        }
        if (snapshotCpuStart >= 0) {
            captureCpuNanos += cpuTime() - snapshotCpuStart;
        }
        maxCaptureTickNanos = Math.max(maxCaptureTickNanos, lastSnapshotNanos);
        if (graph.isStepInProgress() || capture != null) {
            geometryMetrics.safeWindow(false);
        }
        lastGeometryNanos = System.nanoTime() - start;
        restartMetrics.set("epoch_pending_solver_activation", graph.pendingSolverNodes());
        geometryMetrics
            .endSafeCall(lastGeometryNanos, budget, !noEligibleWork && budget > 0, preparedAtStart, prepared.size());
        geometryMetrics.geometry(lastGeometryNanos, geometryPending() || savePath != null);
        if (budget > 0 && lastGeometryNanos > budget) {
            budgetOverruns++;
            geometryMetrics.budgetOverruns++;
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

    private boolean advanceCriticalUnit() {
        if (job != null) {
            if (criticalBuilds.isEmpty() && job.cell.geometry == null
                && job.cell.sourceHead == null
                && job.cell.events.isEmpty()
                && job.cell.waiting.isEmpty()) return false;
            if (job.cell.ticket != job.ticket || !provider.isReady(job.cell.cube.key)) return false;
            if (!job.ready) advanceBuild(job);
            else {
                if (job.cell.geometry != null || job.cell.sourceHead != null || !job.cell.events.isEmpty())
                    prepared.addFirst(job);
                else prepared.addLast(job);
                job.cell.diagnosticPreparedTick = ticks;
                geometryMetrics.preparedAccepted++;
                job = null;
            }
            return true;
        }
        if (prepared.size() >= PREPARED_LIMIT) return false;
        for (int scanned = 0; scanned < 8 && !criticalBuilds.isEmpty(); scanned++) {
            Cell c = criticalBuilds.removeFirst();
            c.criticalQueued = false;
            if (!c.queued || c.building || !provider.isReady(c.cube.key)) continue;
            c.queued = false;
            c.building = true;
            if (c.geometry == null) geometryMetrics.newJobs++;
            else geometryMetrics.rebuildJobs++;
            job = new CellBuild(this, c);
            geometryMetrics.jobsStarted++;
            c.status = c.geometry == null ? Status.BUILDING : Status.DIRTY;
            return true;
        }
        return false;
    }

    private void advanceBuild(CellBuild b) {
        long start = System.nanoTime();
        int phase = b.diagnosticStage();
        boolean contacts = b.inContacts();
        b.advance();
        long duration = System.nanoTime() - start;
        geometryMetrics.phase(phase, duration);
        if (phase == GeometryMetrics.PARTITION && b.diagnosticStage() != phase) {
            geometryMetrics.partitionsCompleted++;
            geometryMetrics.partitionNodes += b.local.count;
            geometryMetrics.maxPartitionNodes = Math.max(geometryMetrics.maxPartitionNodes, b.local.count);
        }
        if (phase == GeometryMetrics.EXTERNAL_CONTACTS && b.ready) {
            geometryMetrics.cellsPrepared++;
        }
        if (contacts) {
            if (b.diagnosticRefreshingContacts) {
                geometryMetrics.refreshedAdvance(duration);
            }
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
            // Saved neighbors belong to the restore index, not expansion. Watching
            // them as fronts repeatedly scans world blocks while awaiting a safe
            // commit, and bypasses the cache's region-coherent restore order.
            if ((other == null || other.geometry == null) && !startup.restores(other) && provider.isReady(key.cube())) {
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
            if (other != null && other.geometry != null || startup.restores(other)) {
                continue;
            }
            ImmutableCubeSnapshot world = snapshot(neighbor.cube());
            if (world == null) {
                continue;
            }
            for (int f = 0; f < 64; f++) {
                int p = CellBuild.face(d, f), local = c.geometry.labels.get(p);
                if (local < 0 || graph.nodeMass(c.geometry.ids[local]) <= 0) {
                    continue;
                }
                BlockPos q = c.key.block(p)
                    .offset(CellBuild.DX[d], CellBuild.DY[d], CellBuild.DZ[d]);
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
        return new MassRecord(g.labels, g.volumes.clone(), masses);
    }

    private int publish(CellBuild b) {
        GeometryPack pack = geometryCache == null || b.fromCache ? null : b.pack();
        int policy = publishGeometry(b.cell, b.geometry, b.local.seeds, b.boundary, b.self(), b.edges, true);
        if (pack != null) geometryCache.store(pack);
        return policy;
    }

    static final class PreparedMass {

        double[] masses;
        double[] checkpointMass;
        final Map<Integer, Double> trapped = new LinkedHashMap<>();
        int policy;
        boolean cold, exact;
        long readyAfter;
    }

    /** All remapping is private; parked/trapped stay authoritative until the append. */
    PreparedMass prepareEpochMass(Cell c, Geometry g) {
        PreparedMass result = new PreparedMass();
        result.readyAfter = publicationMassBoundary(c);
        result.cold = c.checkpointRestorePending;
        result.policy = (result.cold || c.parked != null ? PUBLICATION_EPSILON : 0)
            | (result.cold ? PUBLICATION_COLD : 0);
        MassRecord old = c.parked;
        result.exact = result.cold && old != null
            && COLD_EXACT_MASS
            && old.masses.length == g.volumes.length
            && old.sameLabels(g.labels)
            && Arrays.equals(old.volumes, g.volumes);
        result.masses = new double[g.volumes.length];
        result.trapped.putAll(c.trapped);
        long start = System.nanoTime();
        if (result.exact) System.arraycopy(old.masses, 0, result.masses, 0, old.masses.length);
        else if (old != null) {
            if (massRemapper == null) massRemapper = new CellMassRemapper();
            System.arraycopy(
                massRemapper.remap(old, g.labels, g.volumes.length),
                0,
                result.masses,
                0,
                result.masses.length);
            for (int i = 0; i < old.masses.length; i++)
                if (massRemapper.trapped(i)) add(result.trapped, massRemapper.representative(i), old.masses[i]);
        }
        if (result.cold) result.checkpointMass = result.masses.clone();
        else if (c.checkpointTracking != null) {
            if (massRemapper == null) massRemapper = new CellMassRemapper();
            result.checkpointMass = Arrays
                .copyOf(massRemapper.remap(c.checkpointTracking, g.labels, g.volumes.length), g.volumes.length);
        }
        for (Iterator<Map.Entry<Integer, Double>> it = result.trapped.entrySet()
            .iterator(); it.hasNext();) {
            Map.Entry<Integer, Double> e = it.next();
            int local = g.labels.get(e.getKey());
            if (local >= 0) {
                result.masses[local] += e.getValue();
                it.remove();
            }
        }
        if (result.cold) {
            double ms = (System.nanoTime() - start) / 1e6;
            if (result.exact) geometryMetrics.coldExactMassMs += ms;
            else geometryMetrics.coldFallbackMassMs += ms;
        }
        return result;
    }

    void publishEpochGeometry(Cell c, Geometry g, GeometryPack pack, PreparedMass mass) {
        for (int i = 0; i < g.ids.length; i++) {
            int id = graph.newNode(pack.seeds[i]);
            g.ids[i] = id;
            graph.setVolume(id, g.volumes[i]);
            graph.addNodeMass(id, mass.masses[i]);
            graph.sparseBoundary(id, pack.boundary[i]);
            owners[id] = c;
        }
        c.trapped.clear();
        c.trapped.putAll(mass.trapped);
        c.geometry = g;
        g.activationEpoch = graph.solverEpoch() + 1;
        g.checkpointMass = mass.checkpointMass;
        c.checkpointTracking = null;
        c.publicationMassAfter = mass.readyAfter;
        c.cacheState = g.verification;
        c.checkpointRestorePending = c.reloadCacheEligible = false;
        c.parked = null;
        c.status = Status.PUBLISHED;
        c.cube.ready = true;
        rebindSources(c); // candidateNodes belongs to the old immutable Frame, not these bindings.
        generation++;
        publications++;
        if (mass.cold) {
            geometryMetrics.coldPublishCount++;
            if (mass.exact) geometryMetrics.coldExactCount++;
            else geometryMetrics.coldFallbackCount++;
        }
    }

    int publishGeometry(Cell c, Geometry g, int[] seeds, double[] boundary, ImmutableCubeSnapshot self,
        EdgeAccumulator edges, boolean refresh) {
        long massBoundary = epochRestoreEnabled() ? publicationMassBoundary(c) : c.publicationMassAfter;
        boolean replacement = c.geometry != null;
        boolean cold = !replacement && c.checkpointRestorePending;
        MassRecord old = massRecord(c);
        if (cold) {
            geometryMetrics.coldPublishCount++;
        } else if (replacement) {
            geometryMetrics.replacementPublishCount++;
        }
        if (replacement) {
            preserveCheckpointProgress(c);
            for (int id : c.geometry.ids) {
                removeOwner(id);
                graph.removeNode(id);
            }
        }
        if (g.ids == null) g.ids = new int[g.volumes.length];
        for (int i = 0; i < g.ids.length; i++) {
            int id = graph.newNode(seeds[i]);
            g.ids[i] = id;
            graph.setVolume(id, g.volumes[i]);
            if (id >= owners.length) {
                int size = Math.max(id + 1, owners.length * 2);
                owners = Arrays.copyOf(owners, size);
                frontWatch = Arrays.copyOf(frontWatch, size);
            }
            if (id >= frontWatch.length) frontWatch = Arrays.copyOf(frontWatch, owners.length);
            owners[id] = c;
            graph.sparseBoundary(id, boundary[i]);
        }
        long massStart = cold ? System.nanoTime() : 0;
        boolean exact = cold && old != null
            && COLD_EXACT_MASS
            && old.masses.length == g.volumes.length
            && old.sameLabels(g.labels)
            && Arrays.equals(old.volumes, g.volumes);
        if (exact) {
            g.checkpointMass = old.masses.clone();
            for (int i = 0; i < old.masses.length; i++) {
                graph.addNodeMass(g.ids[i], old.masses[i]);
            }
        } else if (old != null) {
            if (massRemapper == null) {
                massRemapper = new CellMassRemapper();
            }
            double[] masses = massRemapper.remap(old, g.labels, g.ids.length);
            if (cold) g.checkpointMass = masses.clone();
            for (int i = 0; i < g.ids.length; i++) {
                graph.addNodeMass(g.ids[i], masses[i]);
            }
            for (int i = 0; i < old.masses.length; i++) {
                if (massRemapper.trapped(i)) {
                    add(c.trapped, massRemapper.representative(i), old.masses[i]);
                }
            }
        }
        for (Iterator<Map.Entry<Integer, Double>> it = c.trapped.entrySet()
            .iterator(); it.hasNext();) {
            Map.Entry<Integer, Double> e = it.next();
            int local = g.labels.get(e.getKey());
            if (local >= 0) {
                graph.addNodeMass(g.ids[local], e.getValue());
                it.remove();
            }
        }
        if (cold) {
            double elapsedMs = (System.nanoTime() - massStart) / 1e6;
            if (exact) {
                geometryMetrics.coldExactCount++;
                geometryMetrics.coldExactMassMs += elapsedMs;
            } else {
                geometryMetrics.coldFallbackCount++;
                geometryMetrics.coldFallbackMassMs += elapsedMs;
            }
        }
        c.geometry = g;
        g.activationEpoch = graph.solverEpoch() + 1;
        c.publicationMassAfter = massBoundary;
        if (c.checkpointTracking != null) {
            if (massRemapper == null) massRemapper = new CellMassRemapper();
            g.checkpointMass = Arrays
                .copyOf(massRemapper.remap(c.checkpointTracking, g.labels, g.volumes.length), g.volumes.length);
            c.checkpointTracking = null;
        }
        if (c.trapped.isEmpty()) c.trapped.clear();
        c.cacheState = g.verification;
        c.checkpointRestorePending = false;
        c.reloadCacheEligible = false;
        rebindSources(c);
        c.parked = null;
        c.status = Status.PUBLISHED;
        c.cube.ready = true;
        if (self != null) {
            c.cube.epoch = self.epoch;
            c.cube.revision = self.revision;
        }
        if (edges != null) edges.appendTo(
            config,
            (a, z, k) -> graph.addEdge(a < 512 ? g.ids[a] : a - 512, z < 512 ? g.ids[z] : z - 512, k));
        generation++;
        publications++;
        if (refresh) {
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
        return (cold || old != null ? PUBLICATION_EPSILON : 0) | (cold ? PUBLICATION_COLD : 0);
    }

    /** Called once, after the existing global applyEvents, on the same idle boundary. */
    void finishPublicationMass(Cell c, int policy) {
        if (graph.isStepInProgress() || steps < c.publicationMassAfter) {
            c.pendingMassPolicy |= policy;
            if (c.pendingMassPolicy != 0) pendingPublicationMass.add(c);
            return;
        }
        policy |= c.pendingMassPolicy;
        c.pendingMassPolicy = 0;
        pendingPublicationMass.remove(c);
        if ((policy & PUBLICATION_EPSILON) == 0) {
            return;
        }
        double restoredCheckpoint = 0;
        for (int i = 0; i < c.geometry.ids.length; i++) {
            int id = c.geometry.ids[i];
            double mass = graph.nodeMass(id);
            // All remap/trapped/waiting/deferred-event contributions have arrived.
            // Exactly 1.0 survives. Never apply epsilon to a per-voxel share.
            if (mass > 0 && mass < 1.0) {
                graph.addNodeMass(id, -mass);
                geometryMetrics.epsilonDroppedMass += mass;
                // The existing persisted removal ledger explains the loss after save/load.
                removed += mass;
                mass = 0;
            }
            if ((policy & PUBLICATION_COLD) != 0) {
                geometryMetrics.coldRestoredMass += mass;
            }
            if (mass != 0 && c.geometry.checkpointMass != null) restoredCheckpoint += c.geometry.checkpointMass[i];
        }
        if (c.geometry.checkpointMass != null) {
            startup.massRestored(restoredCheckpoint);
            c.geometry.checkpointMass = null;
        }
    }

    private boolean publicationAccountingReady() {
        for (Cell c : pendingPublicationMass) if (c.geometry != null && steps < c.publicationMassAfter) return false;
        return true;
    }

    private long publicationMassBoundary(Cell c) {
        long boundary = steps, ordinal = steps;
        for (Frame frame : frames) {
            ordinal++;
            if (frame.contributesTo(c.key)) boundary = ordinal;
        }
        return boundary;
    }

    private void preserveCheckpointProgress(Cell c) {
        Geometry g = c.geometry;
        if (g.checkpointMass != null)
            c.checkpointTracking = new MassRecord(g.labels, g.volumes.clone(), g.checkpointMass.clone());
    }

    /** Optional derivative cache is independent of the authoritative checkpoint bundle. */
    public void geometryCache(Path directory) {
        check(dimension);
        if (restartOptions.cache && geometryCache == null)
            geometryCache = new GeometryCache(directory, dimension, config, restartOptions.cacheBytes);
    }

    public void dimensionOpenedAt(long monotonicNanos) {
        startup.openedAt(monotonicNanos);
    }

    boolean canFastCommit() {
        return !graph.isStepInProgress() && capture == null && (savePath == null || writing);
    }

    boolean epochRestoreEnabled() {
        return restartOptions.epochAppend && restartOptions.fast && graph.law() == SmogConfig.QUADRATIC_FAST;
    }

    boolean canEpochCommit() {
        return appendFailure == null && epochRestoreEnabled() && capture == null && (savePath == null || writing);
    }

    void failAppend(Throwable cause) {
        appendFailure = cause;
    }

    void reserveFast(int nodes, int edges) {
        int slots = graph.reserveSparse(nodes, edges);
        if (owners.length < slots) {
            int capacity = IntList.capacity(owners.length, slots);
            owners = Arrays.copyOf(owners, capacity);
            frontWatch = Arrays.copyOf(frontWatch, capacity);
        }
        if (frontWatch.length < slots) frontWatch = Arrays.copyOf(frontWatch, owners.length);
        if (massRemapper == null) massRemapper = new CellMassRemapper();
    }

    void reserveEpoch(int nodes, int edges) {
        int slots = graph.beginAppendBatch(nodes, edges);
        try {
            if (owners.length < slots) {
                int capacity = IntList.capacity(owners.length, slots);
                owners = Arrays.copyOf(owners, capacity);
                frontWatch = Arrays.copyOf(frontWatch, capacity);
            }
            if (frontWatch.length < slots) frontWatch = Arrays.copyOf(frontWatch, owners.length);
            if (massRemapper == null) massRemapper = new CellMassRemapper();
        } catch (RuntimeException | Error failure) {
            graph.endAppendBatch();
            throw failure;
        }
    }

    boolean prepareEpochCapacity(int nodes, int edges) {
        long start = System.nanoTime();
        boolean ready = graph.prepareAppendCapacity(nodes, edges);
        int slots = Math.addExact(graph.allocatedNodeSlots(), nodes);
        if (ready && owners.length < slots) {
            owners = Arrays.copyOf(owners, IntList.capacity(owners.length, slots));
            ready = false;
        } else if (ready && frontWatch.length < slots) {
            frontWatch = Arrays.copyOf(frontWatch, IntList.capacity(frontWatch.length, slots));
            ready = false;
        }
        double ms = (System.nanoTime() - start) / 1e6;
        restartMetrics.add("epoch_append_reserve_ms", ms);
        restartMetrics.max("epoch_append_reserve_max_ms", ms);
        return ready;
    }

    void refreshFastFronts(Set<Cell> cells) {
        for (Cell c : cells) {
            refreshFrontMask(c);
            front(c);
        }
    }

    void stageCachedBuild(FastRestorePlan.Entry entry) {
        CellBuild b = new CellBuild(this, entry);
        prepared.addLast(b);
        b.cell.diagnosticPreparedTick = ticks;
        geometryMetrics.preparedAccepted++;
    }

    void recordFastPublication(long nanos, int count) {
        publishNanos += nanos;
        maxPublishNanos = Math.max(maxPublishNanos, nanos);
        geometryMetrics.publishBatch(nanos, count);
    }

    public void updateRestartMemoryMetrics() {
        long mass = 0, published = 0;
        for (CubeRecord cube : cubes.values()) for (Cell c : cube.cells) if (c != null) {
            if (c.parked != null) mass += c.parked.bytes();
            Geometry g = c.geometry;
            if (g != null) published += 256L + g.labels.bytes()
                + g.distance.bytes()
                + 8L * g.volumes.length
                + 24L * g.dependencies.length
                + (g.epochs == null ? 0 : 32L + 16L * g.dependencies.length);
        }
        restartMetrics.set("memory_mass_records_bytes", mass);
        restartMetrics.set("memory_published_bytes", published);
        restartMetrics.set("memory_graph_bytes", graph.sparseBytes());
        restartMetrics.set("graph_live_nodes", graph.nodeCount());
        restartMetrics.set("graph_node_slots", graph.allocatedNodeSlots());
        restartMetrics.set("graph_live_edges", graph.edgeCount());
        restartMetrics.set("graph_edge_slots", graph.allocatedEdgeSlots());
    }

    public boolean coldExactMassEnabled() {
        return COLD_EXACT_MASS;
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
        return config.timeStep * (emissionWindows + (scheduleParts == 0 ? 0 : (double) windowTick / scheduleParts));
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
        return validator.runnable() || startup.pending()
            || startup.discovering()
            || restorePlan != null
            || !waitingRestores.isEmpty()
            || job != null
            || !prepared.isEmpty()
            || !builds.isEmpty()
            || !fronts.isEmpty();
    }

    private boolean fastWorkReady() {
        if (restorePlan != null && restorePlan.runnable()) return true;
        for (RestorePlan plan : waitingRestores) if (plan.runnable()) return true;
        return false;
    }

    private boolean restoreWorkReady() {
        return fastWorkReady() || startup.available > 0 && (startup.pending() || !builds.isEmpty()
            || job != null
            || !prepared.isEmpty() && (!prepared.peekFirst().ready || canFastCommit()));
    }

    /** O(queue cap), no scan of unloaded saved territory and no world snapshots. */
    public int geometryWorkWeight() {
        boolean runnable = validator.runnable() || startup.pending()
            || startup.discovering()
            || job != null
            || !fronts.isEmpty()
            || fastWorkReady()
            || !builds.isEmpty() && prepared.size() < PREPARED_LIMIT
                && waitingRestores.size() < restartOptions.preparedCubes
            || !prepared.isEmpty() && (!prepared.peekFirst().ready || canFastCommit())
            || savePath != null && !writing && !graph.isStepInProgress();
        return !runnable ? 0 : frames.size() <= 1 && restoreWorkReady() ? 4 : 1;
    }

    public int pendingIntervals() {
        return frames.size();
    }

    /** Current limit is a restart-only diagnostic property, not a physics parameter. */
    public int preparedLimit() {
        return PREPARED_LIMIT;
    }

    /** O(1) head-of-queue ages, NOT a scan for the oldest cell in the world. */
    public long[] geometryQueueHeadAgeTicks() {
        Cell first = builds.peekFirst();
        CellBuild firstPrepared = prepared.peekFirst();
        return new long[] {
            first == null || first.diagnosticQueuedTick < 0 ? -1 : Math.max(0, ticks - first.diagnosticQueuedTick),
            firstPrepared == null || firstPrepared.cell.diagnosticPreparedTick < 0 ? -1
                : Math.max(0, ticks - firstPrepared.cell.diagnosticPreparedTick) };
    }

    /** Read-only availability, independent of the legacy numeric sample() contract. */
    public boolean hasSampleGeometry(BlockPos point) {
        check(point.dimension);
        Cell c = cell(point.cell(), false);
        return c != null && c.geometry != null;
    }

    /** Time waiting for this Cell8 since its first eligible enqueue, or -1. */
    public long sampleQueueAgeTicks(BlockPos point) {
        check(point.dimension);
        Cell c = cell(point.cell(), false);
        return c == null || c.diagnosticQueuedTick < 0 ? -1 : Math.max(0, ticks - c.diagnosticQueuedTick);
    }

    /** O(1) queue overview; pending work is not necessarily runnable right now. */
    public int[] geometryQueues() {
        return new int[] { builds.size(), job == null ? 0 : 1, prepared.size(), fronts.size() };
    }

    /** Expensive only when explicitly requested; never run this in the tick hot path. */
    public GeometryFootprint geometryFootprint() {
        GeometryFootprint result = new GeometryFootprint();
        result.registeredCubes = cubes.size();
        for (CubeRecord cube : cubes.values()) {
            if (provider.isReady(cube.key)) {
                result.readyCubes++;
            }
            boolean active = false;
            boolean parked = false;
            for (Cell c : cube.cells) {
                if (c == null) {
                    continue;
                }
                result.allocatedCells++;
                if (c.geometry != null) {
                    result.activeCells++;
                    active = true;
                }
                if (c.parked != null) {
                    result.parkedCells++;
                    parked = true;
                }
            }
            if (active) {
                result.activeCubes++;
            } else if (parked) {
                result.parkedOnlyCubes++;
            }
        }
        return result;
    }

    public static final class GeometryFootprint {

        public int registeredCubes, readyCubes, activeCubes, parkedOnlyCubes;
        public int allocatedCells, activeCells, parkedCells;
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
                        if (g.labels.get(p) >= 0) {
                            b[g.labels.get(p)].append(p)
                                .append(',');
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
        startup.resume();
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
        savePath = path.toAbsolutePath()
            .normalize();
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
        writer.execute(() -> {
            long start = System.nanoTime();
            java.lang.management.ThreadMXBean cpu = java.lang.management.ManagementFactory.getThreadMXBean();
            long cpuStart = cpu.isCurrentThreadCpuTimeSupported() ? cpu.getCurrentThreadCpuTime() : -1;
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
                if (attachment != null) {
                    try {
                        attachment.afterWrite();
                    } catch (Exception cleanupFailure) {
                        System.err.println("SMOG save attachment close deferred: " + cleanupFailure);
                    }
                }
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

    public static DimensionState load(Path path, WorldSnapshotProvider provider) throws IOException {
        long opened = System.nanoTime();
        Checkpoint.Loaded loaded = Checkpoint.read(path);
        DimensionState s = Checkpoint.restore(loaded.data, provider);
        s.dimensionOpenedAt(opened);
        s.diskPath = path.toAbsolutePath()
            .normalize();
        s.diskGeneration = loaded.generation;
        s.restartMetrics.set("checkpoint_read_ms", loaded.data.readNanos / 1e6);
        s.restartMetrics.set("checkpoint_decode_ms", loaded.data.decodeNanos / 1e6);
        s.restartMetrics.set("checkpoint_bytes", loaded.data.bytes);
        s.restartMetrics.set("checkpoint_cells", loaded.data.cells.size());
        s.restartMetrics.set("checkpoint_format_version", loaded.data.formatVersion);
        return s;
    }

    /** Complete the authoritative save first. Only derived-cache IO gets a bounded flush here. */
    @Override
    public void close() {
        closed = true;
        writer.shutdown();
        if (geometryCache != null) {
            geometryCache.close();
            geometryCache.syncMetrics(restartMetrics);
        }
    }
}

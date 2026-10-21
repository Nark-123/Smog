package smog;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/** Bounded, transient, single-writer telemetry. Never holds a world, graph or task reference. */
public final class ProfilerCounters {

    public static final int OFF = 0, BASIC = 1, SAMPLED = 2, DIAGNOSTIC = 3;
    // Append IDs only. These numbers are the profile v1 protocol.
    public static final int RUNTIME = 0, DELIVERY = 1, SAVE_POLL = 2, DIMENSION_TICK = 3, EMISSIONS = 4, GEOMETRY = 5,
        LAW4 = 6, BUILD_SNAPSHOT = 7, BUILD_HALO = 8, BUILD_PARTITION = 9, BUILD_INNER = 10, BUILD_OUTER = 11,
        CONVERSION = 12, PACK = 13, RESERVE = 14, APPEND = 15, VALIDATOR = 16, WORLD_CHECK = 17, CONTACT_CHECK = 18,
        CONTACT_REFRESH = 19, FRONT = 20, CACHE_PUMP = 21, READ_QUEUE = 22, CACHE_READ = 23, CACHE_DECOMPRESS = 24,
        CACHE_DECODE = 25, CACHE_WRITE = 26, REWRITE_READ = 27, CACHE_ENCODE = 28, ATOMIC_REPLACE = 29, CAPTURE = 30,
        CHECKPOINT_READ = 31, CHECKPOINT_DECODE = 32, CHECKPOINT_WRITE = 33, CHECKPOINT_COMMIT = 34, STARTUP = 35,
        INVALIDATION = 36, CHUNK_LOAD = 37, CHUNK_UNLOAD = 38, PULSE_ACCEPT = 39, SNAPSHOT = 40, MASS_PREPARE = 41,
        MASS_FINISH = 42, EVENTS = 43, FRAME_INDEX = 44, EPOCH_PLAN = 45, EPOCH_CONTACTS = 46, BUILD_QUEUE = 47,
        APPEND_QUEUE = 48, REPLACEMENT_QUEUE = 49, NETWORK = 50, DIAGNOSTICS = 51, CHECKPOINT_LOAD = 52,
        EXPANSION_NEW = 53, RESTORE_FAST = 54, RESTORE_FALLBACK = 55, SERVER_TICK = 56, REPLACEMENT = 57,
        STAGE_COUNT = 58;
    public static final String[] NAMES = { "runtime", "delivery", "save_poll", "dimension_tick", "emissions",
        "geometry", "law4", "build_snapshot", "build_halo", "build_partition", "build_inner", "build_outer",
        "conversion", "pack", "reserve", "append", "validator", "world_check", "contact_check", "contact_refresh",
        "front", "cache_pump", "read_queue", "cache_read", "cache_decompress", "cache_decode", "cache_write",
        "rewrite_read", "cache_encode", "atomic_replace", "capture", "checkpoint_read", "checkpoint_decode",
        "checkpoint_write", "checkpoint_commit", "startup", "invalidation", "chunk_load", "chunk_unload",
        "pulse_accept", "snapshot", "mass_prepare", "mass_finish", "events", "frame_index", "epoch_plan",
        "epoch_contacts", "build_queue", "append_queue", "replacement_queue", "network", "diagnostics",
        "checkpoint_load", "expansion_new", "restore_fast", "restore_fallback", "server_tick", "replacement" };
    public static final int NO_WORK = 0, BUDGET_END = 1, PREPARED_FULL = 2, WORLD_NOT_READY = 3, CACHE_PENDING = 4,
        IO_PENDING = 5, SOLVER_STEP = 6, CAPTURE_BLOCK = 7, WAIT_SAFE_CUT = 8, CAPACITY_RESERVE = 9, STALE_TICKET = 10,
        INVALID_WORLD = 11, INVALID_CONTACT = 12, BLOCKED_REPLACEMENT = 13, VALIDATOR_QUOTA = 14, FRONT_QUOTA = 15,
        WRITE_BACKPRESSURE = 16, SHUTDOWN = 17, IO_ERROR = 18, CORRUPT = 19, CONFIG = 20, CACHE_MISS = 21,
        CACHE_HIT = 22, REASON_COUNT = 23;
    public static final String[] REASONS = { "NO_WORK", "BUDGET_END", "PREPARED_FULL", "WORLD_NOT_READY",
        "CACHE_PENDING", "IO_PENDING", "SOLVER_STEP", "CAPTURE", "WAIT_SAFE_CUT", "CAPACITY_RESERVE", "STALE_TICKET",
        "INVALID_WORLD", "INVALID_CONTACT", "BLOCKED_REPLACEMENT", "VALIDATOR_QUOTA", "FRONT_QUOTA",
        "WRITE_BACKPRESSURE", "SHUTDOWN", "IO_ERROR", "CORRUPT", "CONFIG", "CACHE_MISS", "CACHE_HIT" };

    public enum CheckpointStatus {
        MISSING,
        EMPTY,
        LOADED,
        FALLBACK_PREVIOUS,
        FAILED
    }

    static final int STRIDE = 36, OPS = 0, TIMED = 1, UNITS = 2, TIMED_UNITS = 3, NANOS = 4, MAX = 5, MIN = 6,
        RETRIED = 7, CANCELLED = 8, HIST = 9, UNIT_HIST = 18, GT2 = 26, GT5 = 27, GT10 = 28, GT50 = 29, DETAIL_A = 30,
        DETAIL_B = 31, QUEUE_PEAK = 32, AGE_MAX = 33, COMPLETE = 34, FAILED = 35;
    static final int RING_ROWS = 256, RING_WIDTH = 16;
    private static final long[] TIME_LIMITS = { 10000, 50000, 200000, 1000000, 5000000, 20000000, 100000000,
        500000000 };
    private static final long EVENT_PERIOD = 30000000000L;

    private static final class Settings {

        volatile int level, mask;
        final String session = UUID.randomUUID()
            .toString();
        long opened = System.nanoTime();
        volatile CheckpointStatus checkpoint = CheckpointStatus.MISSING;
        volatile boolean previous;
        volatile long checkpointCells, checkpointBytes, checkpointVersion;
        volatile long diagnosticEnd;
        volatile ProfileFileLog sink;

        Settings(int level, int rate) {
            this.level = level;
            mask = rate - 1;
        }
    }

    private final Settings settings;
    private final int dimension, lane;
    private final long[] data = new long[STAGE_COUNT * STRIDE], sequence = new long[STAGE_COUNT];
    private final long[] reasons = new long[REASON_COUNT * 2];
    private final long[] ring;
    private final long[] lastEvent = new long[STAGE_COUNT], suppressed = new long[STAGE_COUNT];
    private final long[] milestones = new long[7];
    private final long[] publications = new long[3];
    private long pendingEvents, nextFlush, windowTick, windowWall, ticks, ringCursor, drops;
    private long overheadNanos, samplePairs, costCalls, estimatedCounterNanos;
    private long sampledSmogCpuNanos, cpuSamples, windowCpuTicks, nextOwnerFlush;
    private final int periodTicks = Math.max(20, Math.min(72000, Integer.getInteger("smog.perfLogTicks", 1200)));
    private long lastTickId;
    private boolean serverTickKnown;

    public ProfilerCounters(int dimension) {
        this(
            dimension,
            0,
            new Settings(
                parseLevel(System.getProperty("smog.profileLevel", "BASIC")),
                sampleRate(Integer.getInteger("smog.profileSampleRate", 256))));
        if (level() == DIAGNOSTIC) settings.diagnosticEnd = diagnosticTicks();
    }

    public ProfilerCounters(int dimension, int level, int rate) {
        this(dimension, 0, new Settings(level, sampleRate(rate)));
        if (level < OFF || level > DIAGNOSTIC) throw new IllegalArgumentException("Profile level");
        if (level == DIAGNOSTIC) settings.diagnosticEnd = diagnosticTicks();
    }

    private ProfilerCounters(int dimension, int lane, Settings settings) {
        this.dimension = dimension;
        this.lane = lane;
        this.settings = settings;
        ring = lane == 0 ? new long[RING_ROWS * RING_WIDTH] : new long[0];
        Arrays.fill(milestones, -1);
        windowWall = settings.opened;
        nextFlush = windowWall;
    }

    private static int diagnosticTicks() {
        return Math.max(1, Math.min(400, Integer.getInteger("smog.profileDiagnosticTicks", 300)));
    }

    private static int sampleRate(int rate) {
        int result = 1;
        while (result < Math.max(1, Math.min(1024, rate))) result <<= 1;
        return result;
    }

    public static int parseLevel(String value) {
        if (value == null) return BASIC;
        switch (value.toUpperCase(Locale.ROOT)) {
            case "OFF":
                return OFF;
            case "SAMPLED":
                return SAMPLED;
            case "DIAGNOSTIC":
                return DIAGNOSTIC;
            default:
                return BASIC;
        }
    }

    public int level() {
        return settings.level;
    }

    public int sampleRate() {
        return settings.mask + 1;
    }

    public String session() {
        return settings.session;
    }

    /** Must be called on the owner thread. Worker lanes only observe the settings. */
    public void level(int level) {
        if (level < OFF || level > DIAGNOSTIC) throw new IllegalArgumentException("Profile level");
        settings.level = level;
        if (level == DIAGNOSTIC) settings.diagnosticEnd = ticks + diagnosticTicks();
    }

    public void attach(ProfileFileLog sink) {
        settings.sink = sink;
    }

    public ProfilerCounters worker(int workerLane) {
        if (workerLane < 1 || workerLane > 2) throw new IllegalArgumentException("Worker lane");
        return new ProfilerCounters(dimension, workerLane, settings);
    }

    public void checkpoint(CheckpointStatus status, boolean fromPrevious) {
        settings.checkpoint = status;
        settings.previous = fromPrevious;
    }

    public void checkpointInfo(long cells, long bytes, long version) {
        settings.checkpointCells = cells;
        settings.checkpointBytes = bytes;
        settings.checkpointVersion = version;
    }

    public void openedAt(long nanos) {
        settings.opened = nanos;
        windowWall = nanos;
        nextFlush = nanos;
    }

    /** New timers are selected once per bounded call, never per voxel. Zero means untimed. */
    public long begin(int stage) {
        int level = settings.level;
        if (level == OFF) return 0;
        data[stage * STRIDE + OPS]++;
        long ordinal = sequence[stage]++;
        return level >= SAMPLED && ((ordinal + stage * 17L) & settings.mask) == 0 ? System.nanoTime() : 0;
    }

    public void end(int stage, long start, long units) {
        if (settings.level == OFF) return;
        int base = stage * STRIDE;
        data[base + UNITS] += Math.max(0, units);
        if (start != 0) {
            samplePairs++;
            timing(base, Math.max(0, System.nanoTime() - start), units, true);
        }
    }

    /** Reuse an existing timer: no additional clock calls and no change to its meaning. */
    public void record(int stage, long nanos, long units) {
        int level = settings.level;
        if (level == OFF) return;
        int base = stage * STRIDE;
        // Existing fine-grained timers already paid for the measurements. BASIC needs
        // only three additions per slice, with no histogram/min/max/extra clock reads.
        if (level == BASIC
            && (stage >= BUILD_SNAPSHOT && stage <= BUILD_OUTER || stage == EPOCH_PLAN || stage == EPOCH_CONTACTS)) {
            data[base + OPS]++;
            data[base + UNITS] += Math.max(0, units);
            data[base + NANOS] += Math.max(0, nanos);
            return;
        }
        long selfStart = (++costCalls & 1023) == 0 ? System.nanoTime() : 0;
        data[base + OPS]++;
        data[base + UNITS] += Math.max(0, units);
        boolean histogram = level >= SAMPLED && ((sequence[stage]++ + stage * 17L) & settings.mask) == 0;
        timing(base, Math.max(0, nanos), units, histogram);
        if (selfStart != 0) estimatedCounterNanos += Math.max(0, System.nanoTime() - selfStart) * 1024;
    }

    public void count(int stage, long units) {
        if (settings.level == OFF) return;
        int base = stage * STRIDE;
        data[base + OPS]++;
        data[base + UNITS] += Math.max(0, units);
    }

    private void timing(int base, long nanos, long units, boolean histogram) {
        data[base + TIMED]++;
        data[base + TIMED_UNITS] += Math.max(0, units);
        data[base + NANOS] += nanos;
        data[base + MAX] = Math.max(data[base + MAX], nanos);
        if (data[base + TIMED] == 1 || nanos < data[base + MIN]) data[base + MIN] = nanos;
        if (nanos > 10000000L && lane == 0) pendingEvents |= 1L << (base / STRIDE);
        if (!histogram || settings.level < SAMPLED) return;
        int bucket = 0;
        while (bucket < TIME_LIMITS.length && nanos >= TIME_LIMITS[bucket]) bucket++;
        data[base + HIST + bucket]++;
        if (nanos > 2000000L) data[base + GT2]++;
        if (nanos > 5000000L) data[base + GT5]++;
        if (nanos > 10000000L) data[base + GT10]++;
        if (nanos > 50000000L) data[base + GT50]++;
    }

    public void batch(int stage, long size) {
        if (settings.level < SAMPLED) return;
        int b = size <= 1 ? 0
            : size <= 4 ? 1 : size <= 8 ? 2 : size <= 16 ? 3 : size <= 32 ? 4 : size <= 64 ? 5 : size <= 128 ? 6 : 7;
        data[stage * STRIDE + UNIT_HIST + b]++;
    }

    public void outcome(int stage, long productive, long retried, long cancelled) {
        if (settings.level == OFF) return;
        int b = stage * STRIDE;
        data[b + COMPLETE] += productive;
        data[b + RETRIED] += retried;
        data[b + CANCELLED] += cancelled;
    }

    public void failure(int stage) {
        if (settings.level != OFF) data[stage * STRIDE + FAILED]++;
    }

    public void detail(int stage, long a, long b) {
        if (settings.level == OFF) return;
        data[stage * STRIDE + DETAIL_A] = a;
        data[stage * STRIDE + DETAIL_B] = b;
    }

    /** One primary reason per attempt; nanos is observed time, not inferred queue latency. */
    public void reason(int reason, long observedNanos) {
        if (settings.level == OFF) return;
        reasons[reason * 2]++;
        reasons[reason * 2 + 1] += Math.max(0, observedNanos);
    }

    public void queue(int stage, long size, long oldestNanos) {
        if (settings.level == OFF) return;
        count(stage, size);
        batch(stage, size);
        int b = stage * STRIDE;
        data[b + QUEUE_PEAK] = Math.max(data[b + QUEUE_PEAK], size);
        data[b + AGE_MAX] = Math.max(data[b + AGE_MAX], Math.max(0, oldestNanos));
    }

    public void publication(boolean saved, boolean fast, long now) {
        publication(saved, fast, false, now);
    }

    public void publication(boolean saved, boolean fast, boolean replacement, long now) {
        if (settings.level == OFF) return;
        if (replacement) {
            count(REPLACEMENT, 1);
            return;
        }
        int kind = saved ? (fast ? 1 : 2) : 0;
        publications[kind]++;
        count(saved ? (fast ? RESTORE_FAST : RESTORE_FALLBACK) : EXPANSION_NEW, 1);
        if (saved) {
            long n = publications[1] + publications[2];
            long elapsed = Math.max(0, now - settings.opened);
            if (milestones[0] < 0) milestones[0] = elapsed;
            if (n >= 10000 && milestones[1] < 0) milestones[1] = elapsed;
            if (n >= 30000 && milestones[2] < 0) milestones[2] = elapsed;
            if (n >= 50000 && milestones[3] < 0) milestones[3] = elapsed;
            if (n >= 80000 && milestones[4] < 0) milestones[4] = elapsed;
        }
    }

    public void availableRestored(long now) {
        if (settings.level != OFF && publications[1] + publications[2] > 0 && milestones[5] < 0)
            milestones[5] = Math.max(0, now - settings.opened);
    }

    public void firstReady(long now) {
        if (settings.level != OFF && milestones[6] < 0) milestones[6] = Math.max(0, now - settings.opened);
    }

    /** Optional CPU samples cover only the SMOG call, once per 64 ticks. */
    public long beginTickCpu() {
        return settings.level != OFF && (ticks & 63) == 0 ? ProfileJvmStats.cpu() : -1;
    }

    public void endTickCpu(long start) {
        if (start < 0) return;
        long end = ProfileJvmStats.cpu();
        if (end >= start) {
            sampledSmogCpuNanos += end - start;
            cpuSamples++;
        }
    }

    /** O(1), no allocation. Full server-tick timing is deliberately not fabricated here. */
    public void tick(long id, long now, long runtime, long delivery, long dimensionNanos, long geometry, long law,
        long poll, long grant, long published, long ready, long frames, int reason, long mask) {
        if (settings.level == OFF || lane != 0) return;
        int b = (int) (ringCursor++ & (RING_ROWS - 1)) * RING_WIDTH;
        ring[b] = id;
        ring[b + 1] = now;
        ring[b + 2] = runtime;
        ring[b + 3] = delivery;
        ring[b + 4] = dimensionNanos;
        ring[b + 5] = geometry;
        ring[b + 6] = law;
        ring[b + 7] = poll;
        ring[b + 8] = grant;
        ring[b + 9] = published;
        ring[b + 10] = ready;
        ring[b + 11] = frames;
        ring[b + 12] = reason;
        ring[b + 13] = mask;
        ring[b + 14] = -1;
        ring[b + 15] = settings.level;
        ticks++;
        lastTickId = id;
        if (geometry > grant + 2000000L) pendingEvents |= 1L << GEOMETRY;
        if (settings.sink != null && pendingEvents != 0) events(id, now);
        if (settings.level == DIAGNOSTIC && ticks >= settings.diagnosticEnd) {
            flush(id, now, true);
            settings.level = BASIC;
        }
        flush(id, now, false);
    }

    public void serverTick(long nanos, long now) {
        if (settings.level == OFF || lane != 0) return;
        serverTickKnown = true;
        record(SERVER_TICK, nanos, 1);
        if (ringCursor != 0) ring[(int) ((ringCursor - 1) & (RING_ROWS - 1)) * RING_WIDTH + 14] = nanos;
        // Whole-server incidents use 200 ms, rather than the SMOG-operation 10 ms threshold.
        pendingEvents &= ~(1L << SERVER_TICK);
        if (nanos > 200000000L) pendingEvents |= 1L << SERVER_TICK;
        if (settings.sink != null && pendingEvents != 0) events(lastTickId, now);
    }

    private void events(long tick, long now) {
        long pending = pendingEvents;
        pendingEvents = 0;
        // One event per class per 30 s, one ring copy per actual admitted event.
        while (pending != 0) {
            int stage = Long.numberOfTrailingZeros(pending);
            pending &= pending - 1;
            if (lastEvent[stage] != 0 && now - lastEvent[stage] < EVENT_PERIOD) {
                suppressed[stage]++;
                continue;
            }
            lastEvent[stage] = now;
            Snapshot event = snapshot(tick, now, false, true, stage, suppressed[stage]);
            suppressed[stage] = 0;
            if (!settings.sink.offer(event)) drops++;
        }
    }

    /** Rare compact copy only; formatting, GC queries and disk writes run on the log worker. */
    public void flush(long tick, long now, boolean force) {
        ProfileFileLog sink = settings.sink;
        if (settings.level == OFF || sink == null || !force && (lane == 0 ? ticks < nextOwnerFlush : now < nextFlush))
            return;
        nextOwnerFlush = ticks + periodTicks;
        nextFlush = now + periodTicks * 50000000L;
        long start = System.nanoTime();
        try {
            long windowTicks = ticks - windowCpuTicks;
            long estimatedCpu = cpuSamples == 0 ? -1 : sampledSmogCpuNanos * windowTicks / cpuSamples;
            long selfCost = estimatedCounterNanos + overheadNanos;
            Snapshot snapshot = snapshot(tick, now, true, false, -1, 0);
            if (!sink.offer(snapshot)) drops++;
            overheadNanos += Math.max(0, System.nanoTime() - start);
            if (lane == 0) observeOverhead(selfCost + overheadNanos, estimatedCpu, windowTicks);
        } catch (RuntimeException diagnosticFailure) {
            drops++;
        }
    }

    /** Use only a sufficiently stable measured window; never changes simulation budgets. */
    public void observeOverhead(long diagnosticNanos, long smogCpuNanos, long samples) {
        if (samples < 256 || smogCpuNanos <= 0) return;
        long allowed = settings.level <= BASIC ? Math.min(smogCpuNanos / 100, samples * 20000L)
            : smogCpuNanos * 3 / 100;
        if (diagnosticNanos <= allowed || settings.level <= BASIC) return;
        if (settings.mask < 1023) settings.mask = (settings.mask << 1) | 1;
        else settings.level = BASIC;
    }

    Snapshot snapshot(long tick, long now, boolean reset, boolean withRing, int event, long suppressedCount) {
        long[] copied = data.clone();
        if (settings.level == BASIC) for (int stage = 0; stage < STAGE_COUNT; stage++) {
            if (stage >= BUILD_SNAPSHOT && stage <= BUILD_OUTER || stage == EPOCH_PLAN || stage == EPOCH_CONTACTS) {
                int b = stage * STRIDE;
                copied[b + TIMED] = copied[b + OPS];
                copied[b + TIMED_UNITS] = copied[b + UNITS];
            }
        }
        Snapshot result = new Snapshot(
            settings.session,
            dimension,
            lane,
            settings.level,
            settings.mask + 1,
            windowTick,
            tick,
            windowWall,
            now,
            settings.opened,
            settings.checkpoint,
            settings.previous,
            copied,
            reasons.clone(),
            withRing ? ring.clone() : new long[0],
            ringCursor,
            milestones.clone(),
            publications.clone(),
            drops,
            overheadNanos,
            samplePairs,
            event,
            suppressedCount,
            serverTickKnown,
            settings.checkpointCells,
            settings.checkpointBytes,
            settings.checkpointVersion,
            sampledSmogCpuNanos,
            cpuSamples,
            estimatedCounterNanos);
        if (reset) {
            Arrays.fill(data, 0);
            Arrays.fill(reasons, 0);
            windowTick = tick;
            windowWall = now;
            overheadNanos = samplePairs = 0;
            windowCpuTicks = ticks;
            sampledSmogCpuNanos = cpuSamples = estimatedCounterNanos = 0;
        }
        return result;
    }

    long value(int stage, int field) {
        return data[stage * STRIDE + field];
    }

    long reasonCount(int reason) {
        return reasons[reason * 2];
    }

    long ringRows() {
        return Math.min(ringCursor, RING_ROWS);
    }

    public long copiedNanos() {
        return overheadNanos;
    }

    static final class Snapshot {

        final String session;
        final int dimension, lane, level, rate, event;
        final long startTick, endTick, startWall, endWall, opened, cursor, dropped, overhead, pairs, suppressed;
        final long cpuNanos, allocatedBytes, checkpointCells, checkpointBytes, checkpointVersion, wallUtcMillis;
        final long sampledSmogCpuNanos, cpuSamples, estimatedCounterNanos;
        final CheckpointStatus checkpoint;
        final boolean previous, serverTickKnown;
        final long[] data, reasons, ring, milestones, publications;

        Snapshot(String session, int dimension, int lane, int level, int rate, long startTick, long endTick,
            long startWall, long endWall, long opened, CheckpointStatus checkpoint, boolean previous, long[] data,
            long[] reasons, long[] ring, long cursor, long[] milestones, long[] publications, long dropped,
            long overhead, long pairs, int event, long suppressed, boolean serverTickKnown, long checkpointCells,
            long checkpointBytes, long checkpointVersion, long sampledSmogCpuNanos, long cpuSamples,
            long estimatedCounterNanos) {
            this.session = session;
            this.dimension = dimension;
            this.lane = lane;
            this.level = level;
            this.rate = rate;
            this.startTick = startTick;
            this.endTick = endTick;
            this.startWall = startWall;
            this.endWall = endWall;
            this.opened = opened;
            this.checkpoint = checkpoint;
            this.previous = previous;
            this.data = data;
            this.reasons = reasons;
            this.ring = ring;
            this.cursor = cursor;
            this.milestones = milestones;
            this.publications = publications;
            this.dropped = dropped;
            this.overhead = overhead;
            this.pairs = pairs;
            this.event = event;
            this.suppressed = suppressed;
            this.serverTickKnown = serverTickKnown;
            wallUtcMillis = System.currentTimeMillis();
            cpuNanos = ProfileJvmStats.cpu();
            allocatedBytes = ProfileJvmStats.allocated();
            this.checkpointCells = checkpointCells;
            this.checkpointBytes = checkpointBytes;
            this.checkpointVersion = checkpointVersion;
            this.sampledSmogCpuNanos = sampledSmogCpuNanos;
            this.cpuSamples = cpuSamples;
            this.estimatedCounterNanos = estimatedCounterNanos;
        }
    }
}

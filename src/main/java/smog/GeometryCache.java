package smog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Derived cache. Groups 4x4x4 Cube16 into one atomic, selectively indexed file. */
final class GeometryCache implements AutoCloseable {

    private static final int MAGIC = 0x53475031, MAX_FILE = 64 * 1048576;

    enum Status {
        OK,
        MISS,
        CONFIG,
        CORRUPT
    }

    static final class Lookup {

        Status status;
        Map<CellKey, GeometryPack> cells = Collections.emptyMap();
        long bytes, readNanos, decompressNanos, decodeNanos;
        boolean accounted;

        Lookup(Status status) {
            this.status = status;
        }
    }

    private static final class Index {

        CubeKey key;
        long offset;
        int length;
        int version;
    }

    interface RegionReader { RandomAccessFile open(Path path) throws IOException; }
    private final RegionReader reader;
    private ProfilerCounters profile;
    void profile(ProfilerCounters profile) { this.profile = profile; }
    private final Path directory;
    private final int dimension;
    private final byte[] config;
    private final long limit;
    private final int formatVersion = Integer.getInteger("smog.geometryPackVersion", 2) == 1 ? 1 : 2;
    private final boolean selective = Boolean.parseBoolean(System.getProperty("smog.selectiveGeometryRead", "true"));
    private final int prefetchLimit = Math.max(0, Math.min(4, Integer.getInteger("smog.geometryPrefetchCubes", 2)));
    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "smog-geometry-cache");
        t.setDaemon(true);
        return t;
    });
    private final LinkedHashMap<CubeKey, Lookup> decoded = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashMap<CubeKey, CompletableFuture<Lookup>> reads = new LinkedHashMap<>();
    private long memory;
    private static final class PendingRegion {
        final Map<CellKey, GeometryPack> updates = new LinkedHashMap<>();
        long generation, firstQueued = System.nanoTime(), retryAfter;
        int failures;
    }
    private static final class Barrier {
        final Map<CubeKey, Long> targets = new HashMap<>();
        final CompletableFuture<Void> future = new CompletableFuture<>();
    }
    private static final class Memo {
        Map<CellKey, GeometryPack> cells;
        java.nio.file.attribute.BasicFileAttributes identity;
        long bytes;
    }
    private final Map<CubeKey, PendingRegion> pending = new LinkedHashMap<>();
    private final List<Barrier> barriers = new ArrayList<>();
    private PendingRegion activeWrite;
    private CubeKey activeKey;
    private long nextGeneration;
    private boolean draining;
    private IOException lostWrite;
    private final LinkedHashMap<CubeKey, Memo> written = new LinkedHashMap<>(8, .75f, true); // IO only
    private long writtenBytes;
    private final Map<CubeKey, Long> lastWrites = new HashMap<>(); // IO only
    private long pendingBytes;
    private boolean scheduled, closed;
    private java.util.concurrent.ScheduledFuture<?> delayedDrain;
    private volatile long writeFailures, dropped, entries;
    private volatile boolean shutdownTimedOut;
    private volatile long readFailures;
    private volatile long readQueueNanos, maxReadQueueNanos, readRequests, coalesced, retries;
    private volatile long rewriteReadNanos, encodeWriteNanos, atomicReplaceNanos, memoReuses;
    private volatile boolean flushFailed;
    private volatile long writeRegions, writeCells, writeUpdates, writeBytes, rewriteReadBytes, writeNanos, maxWriteNanos;
    private final Map<CubeKey, Integer> knownEntries = new HashMap<>(); // I/O thread only

    private void noteEntries(CubeKey key, int count) {
        Integer old = knownEntries.put(key, count);
        entries += count - (old == null ? 0 : old);
    }

    GeometryCache(Path directory, int dimension, SmogConfig settings, long limit) {
        this(directory, dimension, settings, limit, path -> new RandomAccessFile(path.toFile(), "r"));
    }

    GeometryCache(Path directory, int dimension, SmogConfig settings, long limit, RegionReader reader) {
        this.reader = java.util.Objects.requireNonNull(reader, "reader");
        this.directory = directory;
        this.dimension = dimension;
        config = GeometryPack.configFingerprint(settings);
        this.limit = limit;
    }

    private CubeKey region(CubeKey key) {
        return new CubeKey(dimension, Math.floorDiv(key.x, 4), Math.floorDiv(key.y, 4), Math.floorDiv(key.z, 4));
    }

    private Path file(CubeKey region) {
        return directory.resolve("pack-" + region.x + "-" + region.y + "-" + region.z + ".gpk");
    }

    CompletableFuture<Lookup> request(CubeKey cube) {
        CubeKey group = selective ? cube : region(cube);
        Lookup hit = decoded.get(group);
        if (hit != null) {
            Lookup result = new Lookup(hit.status);
            result.cells = hit.cells;
            return CompletableFuture.completedFuture(result);
        }
        CompletableFuture<Lookup> pending = reads.get(group);
        if (pending == null) {
            long enqueued = System.nanoTime();
            pending = CompletableFuture.supplyAsync(() -> {
                long wait = System.nanoTime() - enqueued;
                readQueueNanos += wait; maxReadQueueNanos = Math.max(maxReadQueueNanos, wait); readRequests++;
                if (profile != null) profile.record(ProfilerCounters.READ_QUEUE, wait, 1);
                return read(cube);
            }, io);
            reads.put(group, pending);
        }
        return pending;
    }

    boolean prefetch(CubeKey cube) {
        CubeKey group = selective ? cube : region(cube);
        // One IO worker, a bounded number of speculative cubes, one demanded transaction.
        if (reads.size() < prefetchLimit && !decoded.containsKey(group) && !reads.containsKey(group)) {
            request(cube);
            return true;
        }
        return false;
    }

    void pump(RestartMetrics metrics) {
        Iterator<Map.Entry<CubeKey, CompletableFuture<Lookup>>> it = reads.entrySet()
            .iterator();
        while (it.hasNext()) {
            Map.Entry<CubeKey, CompletableFuture<Lookup>> e = it.next();
            if (!e.getValue()
                .isDone()) continue;
            try {
                acceptRegion(
                    e.getKey(),
                    e.getValue()
                        .join(),
                    metrics);
            } catch (RuntimeException ignored) {/* demanded lookup falls back */}
            it.remove();
        }
        syncMetrics(metrics);
    }

    void accept(CubeKey cube, Lookup lookup, RestartMetrics metrics) {
        CubeKey group = selective ? cube : region(cube);
        acceptRegion(group, lookup, metrics);
        reads.remove(group);
    }

    private void acceptRegion(CubeKey group, Lookup lookup, RestartMetrics metrics) {
        if (!lookup.accounted) {
            metrics.add("cache_read_ms", lookup.readNanos / 1e6);
            metrics.add("cache_decompress_ms", lookup.decompressNanos / 1e6);
            metrics.add("cache_decode_ms", lookup.decodeNanos / 1e6);
            metrics.add("cache_bytes_read", lookup.bytes);
            lookup.accounted = true;
        }
        if (!decoded.containsKey(group)) {
            long bytes = bytes(lookup);
            if (bytes <= limit) {
                decoded.put(group, lookup);
                memory += bytes;
            }
            while (memory > limit && !decoded.isEmpty()) {
                Iterator<Lookup> it = decoded.values()
                    .iterator();
                memory -= bytes(it.next());
                it.remove();
            }
        }
        metrics.set("cache_memory_bytes", memory);
        syncMetrics(metrics);
    }

    private static long bytes(Lookup lookup) {
        long n = 128;
        for (GeometryPack p : lookup.cells.values()) n += p.bytes();
        return n;
    }

    void syncMetrics(RestartMetrics metrics) {
        metrics.set("cache_entries", entries);
        metrics.set("cache_write_failures", writeFailures);
        metrics.set("cache_write_dropped", dropped);
        metrics.set("cache_shutdown_timeout", shutdownTimedOut ? 1 : 0);
        metrics.set("cache_write_regions", writeRegions);
        metrics.set("cache_write_cells", writeCells);
        metrics.set("cache_write_updates", writeUpdates);
        metrics.set("cache_write_bytes", writeBytes);
        metrics.set("cache_rewrite_read_bytes", rewriteReadBytes);
        metrics.set("cache_write_ms", writeNanos / 1e6);
        metrics.set("cache_write_max_ms", maxWriteNanos / 1e6);
        metrics.set("cache_read_wait_ms", readQueueNanos / 1e6);
        metrics.set("cache_read_wait_max_ms", maxReadQueueNanos / 1e6);
        metrics.set("cache_read_requests", readRequests);
        metrics.set("cache_read_failures", readFailures);
        metrics.set("cache_write_coalesced", coalesced);
        metrics.set("cache_rewrite_read_ms", rewriteReadNanos / 1e6);
        metrics.set("cache_encode_write_ms", encodeWriteNanos / 1e6);
        metrics.set("cache_atomic_replace_ms", atomicReplaceNanos / 1e6);
        metrics.set("cache_region_reuse", memoReuses);
        metrics.set("cache_write_retries", retries);
        metrics.set("cache_flush_failed", flushFailed ? 1 : 0);
        long readBytes = 0;
        for (CompletableFuture<Lookup> future : reads.values())
            if (future.isDone() && !future.isCompletedExceptionally())
                readBytes += bytes(future.getNow(new Lookup(Status.MISS)));
        synchronized (pending) {
            metrics.set("cache_inflight_bytes", pendingBytes + readBytes);
            metrics.set("cache_pending_bytes", pendingBytes);
            metrics.set("cache_pending_regions", pending.size() + (activeWrite == null ? 0 : 1));
            long oldest = System.nanoTime();
            for (PendingRegion region : pending.values()) oldest = Math.min(oldest, region.firstQueued);
            if (activeWrite != null) oldest = Math.min(oldest, activeWrite.firstQueued);
            metrics.set("cache_pending_oldest_ms", (System.nanoTime() - oldest) / 1e6);
        }
    }

    void store(GeometryPack pack) {
        Lookup old = decoded.remove(selective ? pack.key.cube() : region(pack.key.cube()));
        if (old != null) memory -= bytes(old);
        synchronized (pending) {
            CubeKey key = region(pack.key.cube());
            PendingRegion group = pending.get(key);
            GeometryPack replaced = group == null ? null : group.updates.get(pack.key);
            long extra = pack.bytes() - (replaced == null ? 0 : replaced.bytes());
            if (closed || pendingBytes + extra > limit) { dropped++; return; }
            if (group == null) { group = new PendingRegion(); pending.put(key, group); }
            group.updates.put(pack.key, pack); group.generation = ++nextGeneration;
            pendingBytes += extra;
            if (replaced != null) coalesced++;
            scheduleDrain(200000000L, false);
        }
    }

    // Called under pending's monitor. One region per IO turn lets queued demand reads run.
    private void scheduleDrain(long delay, boolean expedite) {
        if (io.isShutdown() || draining || scheduled && !expedite) return;
        if (scheduled && delayedDrain != null) delayedDrain.cancel(false);
        scheduled = true;
        delayedDrain = io.schedule(this::drain, Math.max(0, delay), TimeUnit.NANOSECONDS);
    }

    private void drain() {
        CubeKey key = null;
        PendingRegion update = null;
        synchronized (pending) {
            draining = true; delayedDrain = null;
            long now = System.nanoTime(), next = Long.MAX_VALUE;
            boolean urgent = closed || !barriers.isEmpty();
            for (Map.Entry<CubeKey, PendingRegion> e : pending.entrySet()) {
                long due = Math.max(e.getValue().retryAfter, urgent ? 0 : lastWrites.getOrDefault(e.getKey(), 0L) + 200000000L);
                if (due <= now) { key = e.getKey(); update = e.getValue(); break; }
                next = Math.min(next, due - now);
            }
            if (update == null) {
                draining = scheduled = false;
                if (!pending.isEmpty()) scheduleDrain(next, false);
                return;
            }
            pending.remove(key); activeKey = key; activeWrite = update;
        }
        boolean success = false;
        IOException failure = null;
        long start = System.nanoTime();
        try {
            long previousBytes = Files.exists(file(key)) ? Files.size(file(key)) : 0;
            long readStart = System.nanoTime();
            long reuseBefore = memoReuses;
            Map<CellKey, GeometryPack> all = readRegion(key);
            long profileReadNanos = System.nanoTime() - readStart;
            rewriteReadNanos += profileReadNanos;
            if (profile != null) profile.record(ProfilerCounters.REWRITE_READ, profileReadNanos, all.size());
            all.putAll(update.updates);
            long encodeStart = System.nanoTime(), replaceBefore = atomicReplaceNanos;
            writeRegion(key, all);
            long profileEncodeNanos = System.nanoTime() - encodeStart - (atomicReplaceNanos - replaceBefore);
            encodeWriteNanos += profileEncodeNanos;
            if (profile != null) profile.record(ProfilerCounters.CACHE_ENCODE, profileEncodeNanos, all.size());
            remember(key, all);
            writeRegions++; writeCells += all.size(); writeUpdates += update.updates.size();
            writeBytes += Files.size(file(key)); rewriteReadBytes += memoReuses == reuseBefore ? previousBytes : 0;
            long nanos = System.nanoTime() - start;
            writeNanos += nanos; maxWriteNanos = Math.max(maxWriteNanos, nanos);
            if (profile != null) {
                profile.record(ProfilerCounters.CACHE_WRITE, nanos, all.size());
                profile.detail(ProfilerCounters.CACHE_WRITE, update.updates.size(), all.size());
                profile.batch(ProfilerCounters.CACHE_WRITE, all.size());
                profile.flush(-1, start + nanos, false);
            }
            lastWrites.put(key, System.nanoTime()); success = true;
        } catch (IOException | RuntimeException problem) {
            failure = problem instanceof IOException ? (IOException)problem : new IOException(problem);
            writeFailures++;
            if (profile != null) {
                profile.count(ProfilerCounters.CACHE_WRITE, 0);
                profile.failure(ProfilerCounters.CACHE_WRITE);
                profile.reason(ProfilerCounters.IO_ERROR, 0);
            }
            System.err.println("SMOG GeometryCache operation=rewrite timestamp=" + java.time.Instant.now()
                + " dimension=" + dimension + " path=" + file(key) + " attempt=" + (update.failures + 1));
            failure.printStackTrace(System.err);
        } finally {
            synchronized (pending) {
                for (GeometryPack p : update.updates.values()) pendingBytes -= p.bytes();
                if (!success && ++update.failures < 3 && !io.isShutdown()) {
                    retries++;
                    update.retryAfter = System.nanoTime() + (200000000L << (update.failures - 1));
                    PendingRegion newer = pending.get(key);
                    if (newer == null) { pending.put(key, update); for (GeometryPack p : update.updates.values()) pendingBytes += p.bytes(); }
                    else {
                        newer.failures = Math.max(newer.failures, update.failures); newer.retryAfter = update.retryAfter;
                        for (GeometryPack p : update.updates.values()) if (!newer.updates.containsKey(p.key)) {
                            if (pendingBytes + p.bytes() <= limit) { newer.updates.put(p.key, p); pendingBytes += p.bytes(); }
                            else { dropped++; lostWrite = failure; }
                        }
                    }
                } else if (!success) { lostWrite = failure; flushFailed = true; dropped += update.updates.size(); }
                for (Iterator<Barrier> it = barriers.iterator(); it.hasNext();) {
                    Barrier barrier = it.next();
                    Long target = barrier.targets.get(key);
                    if (success && target != null && target <= update.generation) barrier.targets.remove(key);
                    if (lostWrite != null) { barrier.future.completeExceptionally(lostWrite); it.remove(); }
                    else if (barrier.targets.isEmpty()) { barrier.future.complete(null); it.remove(); }
                }
                activeKey = null; activeWrite = null; draining = scheduled = false;
                if (!pending.isEmpty()) scheduleDrain(0, false);
            }
        }
    }

    private static boolean sameFile(java.nio.file.attribute.BasicFileAttributes a,
        java.nio.file.attribute.BasicFileAttributes b) {
        return a != null && b != null && a.fileKey() != null && b.fileKey() != null
            && java.util.Objects.equals(a.fileKey(), b.fileKey())
            && a.size() == b.size() && a.lastModifiedTime().equals(b.lastModifiedTime())
            && a.creationTime().equals(b.creationTime());
    }
    private void remember(CubeKey key, Map<CellKey, GeometryPack> cells) throws IOException {
        Memo memo = new Memo(); memo.cells = Collections.unmodifiableMap(new LinkedHashMap<>(cells));
        memo.identity = Files.readAttributes(file(key), java.nio.file.attribute.BasicFileAttributes.class);
        for (GeometryPack pack : cells.values()) memo.bytes += pack.bytes();
        Memo old = written.remove(key); if (old != null) writtenBytes -= old.bytes;
        if (memo.bytes <= limit / 4) { written.put(key, memo); writtenBytes += memo.bytes; }
        while (writtenBytes > limit / 4 && !written.isEmpty()) {
            Iterator<Memo> it = written.values().iterator(); writtenBytes -= it.next().bytes; it.remove();
        }
    }

    private Map<CellKey, GeometryPack> readRegion(CubeKey key) throws IOException {
        Map<CellKey, GeometryPack> result = new LinkedHashMap<>();
        Path path = file(key);
        java.nio.file.attribute.BasicFileAttributes identity;
        try { identity = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class); }
        catch (java.nio.file.NoSuchFileException missing) { return result; }
        Memo memo = written.get(key);
        if (memo != null && sameFile(memo.identity, identity)) {
            memoReuses++; return new LinkedHashMap<>(memo.cells);
        }
        if (memo != null) { written.remove(key); writtenBytes -= memo.bytes; }
        try (RandomAccessFile in = reader.open(path)) {
            for (Index entry : index(in, key)) result.putAll(readCube(in, entry, null));
        } catch (InvalidPack | RecordCodec.InvalidRecord | java.io.EOFException | ConfigMismatch invalid) {
            result.clear();
        } catch (RuntimeException invalid) { result.clear(); }
        // Other IOExceptions are device/access failures: propagate, retain the old atomic file.
        return result;
    }

    private Lookup read(CubeKey cube) {
        Lookup result = new Lookup(Status.MISS);
        long start = System.nanoTime();
        long failuresBefore = readFailures;
        Path path = file(region(cube));
        try (RandomAccessFile in = reader.open(path)) {
            List<Index> indexes = index(in, region(cube));
            result.bytes += in.getFilePointer();
            Map<CellKey, GeometryPack> group = new LinkedHashMap<>();
            long memory = 0;
            for (Index entry : indexes) {
                if (selective && !entry.key.equals(cube)) continue;
                Map<CellKey, GeometryPack> cells = readCube(in, entry, result);
                for (GeometryPack pack : cells.values()) memory += pack.bytes();
                if (memory > 32L * 1048576) throw new IOException("GeometryPack decoded region limit");
                group.putAll(cells);
                noteEntries(entry.key, cells.size());
            }
            result.cells = group;
            result.status = Status.OK;
        } catch (ConfigMismatch mismatch) {
            result.status = Status.CONFIG;
        } catch (InvalidPack | RecordCodec.InvalidRecord | java.io.EOFException invalid) {
            result.status = Status.CORRUPT;
        } catch (IOException unavailable) {
            result.status = Status.MISS;
            if (!Files.notExists(path)) {
                readFailures++;
                System.err.println("SMOG GeometryCache operation=read timestamp=" + java.time.Instant.now()
                    + " dimension=" + dimension + " path=" + path);
                unavailable.printStackTrace(System.err);
            }
        } catch (RuntimeException invalid) { result.status = Status.CORRUPT; }
        long profileNow = System.nanoTime();
        result.readNanos = profileNow - start - result.decompressNanos - result.decodeNanos;
        if (profile != null) {
            profile.record(ProfilerCounters.CACHE_READ, result.readNanos, result.bytes);
            if (result.decompressNanos > 0) profile.record(ProfilerCounters.CACHE_DECOMPRESS, result.decompressNanos, result.bytes);
            if (result.decodeNanos > 0) profile.record(ProfilerCounters.CACHE_DECODE, result.decodeNanos, result.cells.size());
            profile.reason(readFailures != failuresBefore ? ProfilerCounters.IO_ERROR
                : result.status == Status.OK ? ProfilerCounters.CACHE_HIT
                : result.status == Status.CORRUPT ? ProfilerCounters.CORRUPT
                : result.status == Status.CONFIG ? ProfilerCounters.CONFIG : ProfilerCounters.CACHE_MISS, 0);
            profile.flush(-1, profileNow, false);
        }
        return result;
    }

    private static final class InvalidPack extends IOException {
        private static final long serialVersionUID = 1L;
        InvalidPack(String message) { super(message); }
        InvalidPack(Throwable cause) { super("Invalid decoded GeometryPack", cause); }
    }

    private static final class ConfigMismatch extends IOException {

        private static final long serialVersionUID = 1L;
    }

    private List<Index> index(RandomAccessFile in, CubeKey region) throws IOException {
        if (in.length() < 80 || in.length() > MAX_FILE) throw new InvalidPack("GeometryPack file bounds");
        byte[] prefix = new byte[48];
        in.readFully(prefix);
        DataInputStream first = new DataInputStream(new ByteArrayInputStream(prefix));
        int magic = first.readInt(), version = first.readInt();
        if (magic != MAGIC || version < 1 || version > GeometryPack.VERSION || first.readInt() != dimension)
            throw new ConfigMismatch();
        byte[] stored = new byte[32];
        first.readFully(stored);
        if (!MessageDigest.isEqual(stored, config)) throw new ConfigMismatch();
        int count = first.readInt();
        if (count < 0 || count > 64) throw new InvalidPack("GeometryPack index count");
        byte[] table = new byte[count * 24];
        in.readFully(table);
        DataInputStream entries = new DataInputStream(new ByteArrayInputStream(table));
        List<Index> result = new ArrayList<>();
        Set<CubeKey> unique = new HashSet<>();
        long end = 80L + 24L * count;
        for (int i = 0; i < count; i++) {
            Index e = new Index();
            e.version = version;
            e.key = new CubeKey(dimension, entries.readInt(), entries.readInt(), entries.readInt());
            e.offset = entries.readLong();
            e.length = entries.readInt();
            if (!region(e.key).equals(region) || !unique.add(e.key)
                || e.offset != end
                || e.length < 12
                || e.length > RecordCodec.MAX + 1036L
                || e.offset + e.length > in.length()) throw new InvalidPack("GeometryPack index bounds");
            end += e.length;
            result.add(e);
        }
        long checksumAt = in.getFilePointer();
        byte[] expected = new byte[32];
        in.readFully(expected);
        byte[] header = new byte[(int) checksumAt];
        System.arraycopy(prefix, 0, header, 0, 48);
        System.arraycopy(table, 0, header, 48, table.length);
        if (!MessageDigest.isEqual(
            RecordCodec.digest()
                .digest(header),
            expected) || end != in.length()) throw new InvalidPack("GeometryPack index checksum");
        in.seek(checksumAt + 32);
        return result;
    }

    private Map<CellKey, GeometryPack> readCube(RandomAccessFile in, Index entry, Lookup stats) throws IOException {
        in.seek(entry.offset);
        long start = System.nanoTime();
        byte[] raw = RecordCodec.read(in);
        if (in.getFilePointer() != entry.offset + entry.length) throw new InvalidPack("GeometryPack record extent");
        if (stats != null) {
            stats.bytes += entry.length;
            stats.decompressNanos += System.nanoTime() - start;
        }
        long decodeStart = System.nanoTime();
        Map<CellKey, GeometryPack> result = new LinkedHashMap<>();
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(raw))) {
            int count = data.readUnsignedByte();
            if (count > 8) throw new IOException("GeometryPack cube cells");
            for (int i = 0; i < count; i++) {
                GeometryPack p = GeometryPack.read(data, dimension, entry.version);
                if (!p.key.cube()
                    .equals(entry.key) || result.put(p.key, p) != null)
                    throw new IOException("GeometryPack duplicate/coordinate mismatch");
            }
            if (data.read() != -1) throw new IOException("GeometryPack trailing bytes");
        } catch (IOException | RuntimeException invalid) { throw new InvalidPack(invalid); }
        if (stats != null) stats.decodeNanos += System.nanoTime() - decodeStart;
        return result;
    }

    private void writeRegion(CubeKey region, Map<CellKey, GeometryPack> cells) throws IOException {
        TreeMap<CubeKey, List<GeometryPack>> cubes = new TreeMap<>((a, b) -> {
            int c = Integer.compare(a.x, b.x);
            if (c == 0) c = Integer.compare(a.y, b.y);
            if (c == 0) c = Integer.compare(a.z, b.z);
            return c;
        });
        for (GeometryPack p : cells.values()) cubes.computeIfAbsent(p.key.cube(), k -> new ArrayList<>())
            .add(p);
        if (cubes.size() > 64) throw new IOException("GeometryPack region overflow");
        Files.createDirectories(directory);
        Path target = file(region), tmp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (RandomAccessFile out = new RandomAccessFile(tmp.toFile(), "rw")) {
                out.seek(80L + 24L * cubes.size());
                List<Index> indexes = new ArrayList<>();
                for (Map.Entry<CubeKey, List<GeometryPack>> e : cubes.entrySet()) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    DataOutputStream data = new DataOutputStream(bytes);
                    data.writeByte(
                        e.getValue()
                            .size());
                    for (GeometryPack p : e.getValue()) p.write(data, formatVersion);
                    data.flush();
                    Index index = new Index();
                    index.key = e.getKey();
                    index.offset = out.getFilePointer();
                    RecordCodec.write(out, bytes.toByteArray());
                    index.length = (int) (out.getFilePointer() - index.offset);
                    indexes.add(index);
                }
                ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
                DataOutputStream headerOut = new DataOutputStream(headerBytes);
                headerOut.writeInt(MAGIC);
                headerOut.writeInt(formatVersion);
                headerOut.writeInt(dimension);
                headerOut.write(config);
                headerOut.writeInt(indexes.size());
                for (Index e : indexes) {
                    headerOut.writeInt(e.key.x);
                    headerOut.writeInt(e.key.y);
                    headerOut.writeInt(e.key.z);
                    headerOut.writeLong(e.offset);
                    headerOut.writeInt(e.length);
                }
                headerOut.flush();
                byte[] header = headerBytes.toByteArray();
                out.seek(0);
                out.write(header);
                out.write(
                    RecordCodec.digest()
                        .digest(header));
                out.getFD()
                    .sync();
            }
            long replaceStart = System.nanoTime();
            RecordCodec.replace(tmp, target);
            long profileReplaceNanos = System.nanoTime() - replaceStart;
            atomicReplaceNanos += profileReplaceNanos;
            if (profile != null) profile.record(ProfilerCounters.ATOMIC_REPLACE, profileReplaceNanos, 1);
            for (Map.Entry<CubeKey, List<GeometryPack>> e : cubes.entrySet()) noteEntries(
                e.getKey(),
                e.getValue()
                    .size());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    CompletableFuture<Void> flush() {
        synchronized (pending) {
            if (lostWrite != null) { CompletableFuture<Void> failed = new CompletableFuture<>(); failed.completeExceptionally(lostWrite); return failed; }
            Barrier barrier = new Barrier();
            for (Map.Entry<CubeKey, PendingRegion> e : pending.entrySet()) barrier.targets.put(e.getKey(), e.getValue().generation);
            if (activeWrite != null) barrier.targets.merge(activeKey, activeWrite.generation, Math::max);
            if (barrier.targets.isEmpty()) barrier.future.complete(null);
            else { barriers.add(barrier); scheduleDrain(0, true); }
            return barrier.future;
        }
    }

    @Override
    public void close() {
        synchronized (pending) { if (closed) return; closed = true; }
        CompletableFuture<Void> flushed = flush();
        try {
            long timeout = RestartOptions.millis("smog.geometryShutdownMs", 2000, 0, 10000);
            long deadline = System.nanoTime() + timeout;
            flushed.get(timeout, TimeUnit.NANOSECONDS);
            if (profile != null) io.execute(() -> profile.flush(-1, System.nanoTime(), true));
            io.shutdown();
            if (!io.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
                throw new java.util.concurrent.TimeoutException("IO worker did not terminate");
        } catch (java.util.concurrent.TimeoutException timeout) {
            shutdownTimedOut = true; flushFailed = true;
            System.err.println("SMOG GeometryCache operation=close dimension=" + dimension + " path=" + directory + " flush timed out");
        } catch (java.util.concurrent.ExecutionException failure) {
            flushFailed = true;
            System.err.println("SMOG GeometryCache operation=close dimension=" + dimension + " path=" + directory + " flush FAILED: " + failure.getCause());
        } catch (InterruptedException interrupted) {
            shutdownTimedOut = true; flushFailed = true; Thread.currentThread().interrupt();
        } finally { io.shutdownNow(); }
        decoded.clear(); reads.clear(); memory = 0;
    }
}

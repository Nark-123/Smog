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
    private final Map<CubeKey, Map<CellKey, GeometryPack>> pending = new LinkedHashMap<>();
    private long pendingBytes;
    private boolean scheduled, closed;
    private java.util.concurrent.ScheduledFuture<?> delayedDrain;
    private volatile long writeFailures, dropped, entries;
    private volatile boolean shutdownTimedOut;
    private volatile long writeRegions, writeCells, writeUpdates, writeBytes, rewriteReadBytes, writeNanos,
        maxWriteNanos;
    private final Map<CubeKey, Integer> knownEntries = new HashMap<>(); // I/O thread only

    private void noteEntries(CubeKey key, int count) {
        Integer old = knownEntries.put(key, count);
        entries += count - (old == null ? 0 : old);
    }

    GeometryCache(Path directory, int dimension, SmogConfig settings, long limit) {
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
            pending = CompletableFuture.supplyAsync(() -> read(cube), io);
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
        long readBytes = 0;
        for (CompletableFuture<Lookup> future : reads.values())
            if (future.isDone() && !future.isCompletedExceptionally())
                readBytes += bytes(future.getNow(new Lookup(Status.MISS)));
        synchronized (pending) {
            metrics.set("cache_inflight_bytes", pendingBytes + readBytes);
        }
    }

    void store(GeometryPack pack) {
        // Invalidate the owner-thread decoded view immediately; writer only sees immutable packs.
        Lookup old = decoded.remove(selective ? pack.key.cube() : region(pack.key.cube()));
        if (old != null) memory -= bytes(old);
        synchronized (pending) {
            if (closed || pendingBytes + pack.bytes() > limit) {
                dropped++;
                return;
            }
            CubeKey region = region(pack.key.cube());
            Map<CellKey, GeometryPack> group = pending.computeIfAbsent(region, k -> new LinkedHashMap<>());
            GeometryPack replaced = group.put(pack.key, pack);
            pendingBytes += pack.bytes() - (replaced == null ? 0 : replaced.bytes());
            if (!scheduled) {
                scheduled = true;
                delayedDrain = io.schedule(this::drain, 200, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void drain() {
        while (true) {
            CubeKey key;
            Map<CellKey, GeometryPack> updates;
            synchronized (pending) {
                if (pending.isEmpty()) {
                    scheduled = false;
                    return;
                }
                Iterator<Map.Entry<CubeKey, Map<CellKey, GeometryPack>>> it = pending.entrySet()
                    .iterator();
                Map.Entry<CubeKey, Map<CellKey, GeometryPack>> e = it.next();
                key = e.getKey();
                updates = e.getValue();
                it.remove();
                for (GeometryPack p : updates.values()) pendingBytes -= p.bytes();
            }
            try {
                long start = System.nanoTime();
                long previousBytes = Files.exists(file(key)) ? Files.size(file(key)) : 0;
                Map<CellKey, GeometryPack> all = readRegion(key);
                all.putAll(updates);
                writeRegion(key, all);
                writeRegions++;
                writeCells += all.size();
                writeUpdates += updates.size();
                writeBytes += Files.size(file(key));
                rewriteReadBytes += previousBytes;
                long nanos = System.nanoTime() - start;
                writeNanos += nanos;
                maxWriteNanos = Math.max(maxWriteNanos, nanos);
            } catch (IOException | RuntimeException failure) {
                writeFailures++;
            }
        }
    }

    private Map<CellKey, GeometryPack> readRegion(CubeKey key) {
        Map<CellKey, GeometryPack> result = new LinkedHashMap<>();
        Path path = file(key);
        if (!Files.exists(path)) return result;
        try (RandomAccessFile in = new RandomAccessFile(path.toFile(), "r")) {
            for (Index entry : index(in, key)) result.putAll(readCube(in, entry, null));
        } catch (IOException | RuntimeException invalid) {
            result.clear();
        }
        return result;
    }

    private Lookup read(CubeKey cube) {
        Lookup result = new Lookup(Status.MISS);
        long start = System.nanoTime();
        Path path = file(region(cube));
        if (!Files.exists(path)) {
            result.readNanos = System.nanoTime() - start;
            return result;
        }
        try (RandomAccessFile in = new RandomAccessFile(path.toFile(), "r")) {
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
        } catch (IOException | RuntimeException invalid) {
            result.status = Status.CORRUPT;
        }
        result.readNanos = System.nanoTime() - start - result.decompressNanos - result.decodeNanos;
        return result;
    }

    private static final class ConfigMismatch extends IOException {

        private static final long serialVersionUID = 1L;
    }

    private List<Index> index(RandomAccessFile in, CubeKey region) throws IOException {
        if (in.length() < 80 || in.length() > MAX_FILE) throw new IOException("GeometryPack file bounds");
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
        if (count < 0 || count > 64) throw new IOException("GeometryPack index count");
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
                || e.offset + e.length > in.length()) throw new IOException("GeometryPack index bounds");
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
            expected) || end != in.length()) throw new IOException("GeometryPack index checksum");
        in.seek(checksumAt + 32);
        return result;
    }

    private Map<CellKey, GeometryPack> readCube(RandomAccessFile in, Index entry, Lookup stats) throws IOException {
        in.seek(entry.offset);
        long start = System.nanoTime();
        byte[] raw = RecordCodec.read(in);
        if (in.getFilePointer() != entry.offset + entry.length) throw new IOException("GeometryPack record extent");
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
        }
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
            RecordCodec.replace(tmp, target);
            for (Map.Entry<CubeKey, List<GeometryPack>> e : cubes.entrySet()) noteEntries(
                e.getKey(),
                e.getValue()
                    .size());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    CompletableFuture<Void> flush() {
        return CompletableFuture.runAsync(this::drain, io);
    }

    @Override
    public void close() {
        synchronized (pending) {
            if (closed) return;
            closed = true;
            if (delayedDrain != null) delayedDrain.cancel(false);
        }
        // Bypass the 200 ms coalescing delay, then wait only at shutdown. This is
        // derived data: a timeout leaves a recoverable old atomic file, never a
        // checkpoint failure. Filesystem stalls cannot block the owner indefinitely.
        io.execute(this::drain);
        io.shutdown();
        try {
            long timeout = RestartOptions.millis("smog.geometryShutdownMs", 2000, 0, 10000);
            if (!io.awaitTermination(timeout, TimeUnit.NANOSECONDS)) {
                shutdownTimedOut = true;
                io.shutdownNow();
                System.err.println("SMOG derived geometry-cache flush timed out; cache may need rebuilding");
            }
        } catch (InterruptedException interrupted) {
            shutdownTimedOut = true;
            io.shutdownNow();
            Thread.currentThread()
                .interrupt();
        }
        decoded.clear();
        reads.clear();
        memory = 0;
    }
}

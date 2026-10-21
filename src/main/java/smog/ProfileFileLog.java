package smog;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Optional best-effort sidecar. No caller formats rows, waits for I/O or passes mutable state. */
public final class ProfileFileLog implements AutoCloseable {

    public static final String HEADER = "schema_version,session_id,dimension,lane,level,sample_rate,window_start_tick,window_end_tick,wall_start_ms,wall_end_ms,stage_id,total_ops,timed_ops,units,timed_units,nanos,max_nanos,min_nanos,retried_units,cancelled_units,h_lt10us,h_10_50us,h_50_200us,h_200us_1ms,h_1_5ms,h_5_20ms,h_20_100ms,h_100_500ms,h_ge500ms,b_0_1,b_2_4,b_5_8,b_9_16,b_17_32,b_33_64,b_65_128,b_gt128,gt2ms,gt5ms,gt10ms,gt50ms,detail_a,detail_b,queue_peak,oldest_observed_ns,productive_units,failed_ops";
    private final ThreadPoolExecutor writer;
    private final Path directory;
    private final int dimension;
    private final long fileLimit, sessionLimit;
    private final AtomicLong dropped = new AtomicLong(), errors = new AtomicLong();
    private volatile long writtenBytes, encodeNanos, writeNanos, rows;
    private volatile boolean closed;
    // All following fields are exclusively owned by the writer thread.
    private final boolean[] initialized = new boolean[3];
    private final long[] sizes = new long[3];
    private long sessionBytes;

    public ProfileFileLog(Path directory, int dimension) {
        this(directory, dimension, 32, 8L * 1048576, 32L * 1048576);
    }

    ProfileFileLog(Path directory, int dimension, int capacity, long fileLimit, long sessionLimit) {
        this.directory = directory;
        this.dimension = dimension;
        this.fileLimit = Math.max(1024, fileLimit);
        this.sessionLimit = Math.max(1024, sessionLimit);
        writer = new ThreadPoolExecutor(
            0,
            1,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(Math.max(1, capacity)),
            r -> {
                Thread thread = new Thread(r, "SMOG-profile-writer");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    }

    boolean offer(final ProfilerCounters.Snapshot snapshot) {
        if (closed) {
            dropped.incrementAndGet();
            return false;
        }
        try {
            writer.execute(() -> {
                try {
                    write(snapshot);
                } catch (IOException | RuntimeException problem) {
                    errors.incrementAndGet();
                }
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            dropped.incrementAndGet();
            return false;
        }
    }

    public long dropped() {
        return dropped.get();
    }

    public long errors() {
        return errors.get();
    }

    public long writtenBytes() {
        return writtenBytes;
    }

    public long encodeNanos() {
        return encodeNanos;
    }

    public long writeNanos() {
        return writeNanos;
    }

    public long rows() {
        return rows;
    }

    public int queueDepth() {
        return writer.getQueue()
            .size();
    }

    private Path path(int kind) {
        return directory.resolve(
            kind == 0 ? "profile-dimension_" + dimension + "_v1.csv"
                : kind == 1 ? "profile-events-dimension_" + dimension + "_v1.jsonl"
                    : "profile-summary-dimension_" + dimension + "_v1.jsonl");
    }

    static String csv(ProfilerCounters.Snapshot s, int stage) {
        int base = stage * ProfilerCounters.STRIDE;
        StringBuilder row = new StringBuilder(512);
        row.append("1,")
            .append(s.session)
            .append(',')
            .append(s.dimension)
            .append(',')
            .append(s.lane)
            .append(',')
            .append(s.level)
            .append(',')
            .append(s.rate)
            .append(',')
            .append(s.startTick)
            .append(',')
            .append(s.endTick)
            .append(',')
            .append((s.startWall - s.opened) / 1000000L)
            .append(',')
            .append((s.endWall - s.opened) / 1000000L)
            .append(',')
            .append(stage);
        for (int i = 0; i < ProfilerCounters.STRIDE; i++) row.append(',')
            .append(s.data[base + i]);
        return row.toString();
    }

    private void write(ProfilerCounters.Snapshot s) throws IOException {
        long started = System.nanoTime();
        StringBuilder csv = new StringBuilder();
        if (s.event < 0 && s.level >= ProfilerCounters.SAMPLED) {
            for (int stage = 0; stage < ProfilerCounters.STAGE_COUNT; stage++)
                if (s.data[stage * ProfilerCounters.STRIDE + ProfilerCounters.OPS] != 0) csv.append(csv(s, stage))
                    .append('\n');
        }
        String summary = summary(s);
        encodeNanos += System.nanoTime() - started;
        started = System.nanoTime();
        if (csv.length() != 0) append(0, csv.toString());
        append(s.event < 0 ? 2 : 1, summary + "\n");
        writeNanos += System.nanoTime() - started;
    }

    private String summary(ProfilerCounters.Snapshot s) {
        StringBuilder json = new StringBuilder(2048);
        json.append("{\"schema_version\":1,\"session_id\":\"")
            .append(s.session)
            .append("\",\"dimension\":")
            .append(s.dimension)
            .append(",\"lane\":")
            .append(s.lane)
            .append(",\"level\":")
            .append(s.level)
            .append(",\"sample_rate\":")
            .append(s.rate)
            .append(",\"window_start_tick\":")
            .append(s.startTick)
            .append(",\"window_end_tick\":")
            .append(s.endTick)
            .append(",\"elapsed_ns\":")
            .append(s.endWall - s.opened)
            .append(",\"wall_utc_ms\":")
            .append(s.wallUtcMillis)
            .append(",\"checkpoint_status\":\"")
            .append(s.checkpoint)
            .append("\",\"checkpoint_previous\":")
            .append(s.previous)
            .append(",\"checkpoint_cells\":")
            .append(s.checkpointCells)
            .append(",\"checkpoint_bytes\":")
            .append(s.checkpointBytes)
            .append(",\"checkpoint_version\":")
            .append(s.checkpointVersion)
            .append(",\"server_tick_source\":\"")
            .append(s.serverTickKnown ? "forge_server_tick_hooks" : "external_or_unknown")
            .append("\",\"timing\":\"inclusive_wall_not_cpu\"")
            .append(",\"sample_quantiles\":\"histogram_only_low_sample_below_100\"")
            .append(",\"profile_dropped\":")
            .append(s.dropped + dropped.get())
            .append(",\"profile_io_errors\":")
            .append(errors.get())
            .append(",\"snapshot_main_ns\":")
            .append(s.overhead)
            .append(",\"sample_timer_pairs\":")
            .append(s.pairs)
            .append(",\"writer_queue\":")
            .append(queueDepth())
            .append(",\"writer_bytes\":")
            .append(writtenBytes)
            .append(",\"writer_encode_ns\":")
            .append(encodeNanos)
            .append(",\"writer_io_ns\":")
            .append(writeNanos)
            .append(",\"smog_cpu_sample_nanos\":")
            .append(s.sampledSmogCpuNanos)
            .append(",\"smog_cpu_samples\":")
            .append(s.cpuSamples)
            .append(",\"counter_cost_estimate_ns\":")
            .append(s.estimatedCounterNanos)
            .append(",\"thread_cpu_ns\":")
            .append(s.cpuNanos)
            .append(",\"thread_allocated_bytes\":")
            .append(s.allocatedBytes)
            .append(",\"optional_jvm_unsupported_value\":-1");
        long gcCount = 0, gcMillis = 0;
        try {
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                long count = gc.getCollectionCount(), millis = gc.getCollectionTime();
                if (count >= 0) gcCount += count;
                if (millis >= 0) gcMillis += millis;
            }
            json.append(",\"gc_count_cumulative\":")
                .append(gcCount)
                .append(",\"gc_ms_cumulative\":")
                .append(gcMillis)
                .append(",\"heap_used_worker_sample\":")
                .append(
                    ManagementFactory.getMemoryMXBean()
                        .getHeapMemoryUsage()
                        .getUsed());
        } catch (RuntimeException unavailable) {
            json.append(",\"gc_status\":\"UNSUPPORTED\"");
        }
        json.append(",\"publications_new_fast_fallback\":");
        array(json, s.publications);
        json.append(",\"milestone_ns_first_10k_30k_50k_80k_available_firstready\":");
        array(json, s.milestones);
        json.append(",\"reason_count_observed_ns\":");
        array(json, s.reasons);
        json.append(",\"stages\":[");
        boolean comma = false;
        for (int stage = 0; stage < ProfilerCounters.STAGE_COUNT; stage++) {
            int b = stage * ProfilerCounters.STRIDE;
            if (s.data[b + ProfilerCounters.OPS] == 0) continue;
            if (comma) json.append(',');
            comma = true;
            json.append('[')
                .append(stage);
            for (int i = 0; i < ProfilerCounters.STRIDE; i++) json.append(',')
                .append(s.data[b + i]);
            json.append(']');
        }
        json.append(']');
        if (s.event >= 0) {
            json.append(",\"event_stage\":")
                .append(s.event)
                .append(",\"suppressed_count\":")
                .append(s.suppressed)
                .append(",\"ring_width\":16,\"ring_oldest_to_newest\":[");
            int count = (int) Math.min(s.cursor, ProfilerCounters.RING_ROWS);
            for (int i = 0; i < count; i++) {
                if (i != 0) json.append(',');
                int row = (int) ((s.cursor - count + i) & (ProfilerCounters.RING_ROWS - 1));
                json.append('[');
                for (int j = 0; j < ProfilerCounters.RING_WIDTH; j++) {
                    if (j != 0) json.append(',');
                    json.append(s.ring[row * ProfilerCounters.RING_WIDTH + j]);
                }
                json.append(']');
            }
            json.append(']');
        }
        return json.append('}')
            .toString();
    }

    private static void array(StringBuilder s, long[] values) {
        s.append('[');
        for (int i = 0; i < values.length; i++) {
            if (i != 0) s.append(',');
            s.append(values[i]);
        }
        s.append(']');
    }

    private void append(int kind, String content) throws IOException {
        Path path = path(kind);
        Files.createDirectories(directory);
        if (!initialized[kind]) {
            sizes[kind] = Files.exists(path) ? Files.size(path) : 0;
            if (kind == 0 && sizes[kind] != 0) {
                try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    if (!HEADER.equals(reader.readLine())) throw new IOException("Profile v1 header mismatch");
                }
            }
            initialized[kind] = true;
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        byte[] header = kind == 0 ? (HEADER + "\n").getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (sessionBytes + bytes.length + header.length > sessionLimit || bytes.length + header.length > fileLimit) {
            dropped.incrementAndGet();
            return;
        }
        if (sizes[kind] + bytes.length > fileLimit) {
            Files.move(path, path.resolveSibling(path.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            sizes[kind] = 0;
        }
        if (sizes[kind] == 0 && header.length != 0) {
            Files.write(path, header, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            sizes[kind] += header.length;
            sessionBytes += header.length;
            writtenBytes += header.length;
        }
        Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        sizes[kind] += bytes.length;
        sessionBytes += bytes.length;
        writtenBytes += bytes.length;
        rows++;
    }

    /** Normal world close never waits for a diagnostic disk. Pending immutable records may drain. */
    @Override
    public void close() {
        closed = true;
        writer.shutdown();
    }

    boolean await(long millis) throws InterruptedException {
        return writer.awaitTermination(millis, TimeUnit.MILLISECONDS);
    }
}

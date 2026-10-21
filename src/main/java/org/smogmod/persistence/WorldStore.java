package org.smogmod.persistence;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.LinkOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.smogmod.compat.gt5u.PendingPulse;

import smog.BlockPos;
import smog.ProfilerCounters;
import smog.DimensionState;
import smog.SmogConfig;
import smog.WorldSnapshotProvider;

/**
 * One bounded save coordinator per dimension. Requests/poll/capture are owner-thread only. The
 * checkpoint writer owns immutable queue copies and all disk operations until commit.
 */
public final class WorldStore {

    private final Path directory;

    private volatile String lastValid;
    public long pollNanos;
    public ProfilerCounters.CheckpointStatus loadStatus = ProfilerCounters.CheckpointStatus.MISSING;

    private CompletableFuture<Path> active;

    private DimensionState state;

    private List<PendingPulse> pulses;

    private boolean requested;

    private long version, cutVersion = -1, committedVersion = -1;

    private long requestedAt;

    private volatile String phase = "IDLE", error = "";
    public long requests, coalesced, completed, failed, maxServerNanos;
    public volatile long cleanupFailures;
    public volatile long safeCutWaitNanos, queueCopyNanos, writeNanos, commitNanos, lastSuccessMillis;

    private volatile long writeStarted;
    private volatile String operation = "idle", operationPath = "", failedOperation = "", failedPath = "";

    /** A failed acquisition closes its channel even when called without an outer callback.
     * Never delete a marker whose lease was not acquired by this writer. */
    static final class BundleLease implements AutoCloseable {
        private final Path marker;
        private java.nio.channels.FileChannel channel;
        private java.nio.channels.FileLock lock;
        private BundleLease(Path marker) { this.marker = marker; }
        static BundleLease acquire(Path marker, long timeoutMillis) throws IOException {
            return acquire(marker, timeoutMillis, java.nio.channels.FileChannel.open(marker,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE));
        }
        static BundleLease acquire(Path marker, long timeoutMillis, java.nio.channels.FileChannel channel) throws IOException {
            BundleLease lease = new BundleLease(marker);
            lease.channel = channel;
            try {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
                while (lease.lock == null) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Interrupted checkpoint lease");
                    try { lease.lock = lease.channel.tryLock(); }
                    catch (java.nio.channels.OverlappingFileLockException busy) { throw new IOException("Checkpoint lease already held: " + marker, busy); }
                    if (lease.lock != null) return lease;
                    if (System.nanoTime() >= deadline) throw new IOException("Checkpoint lease timeout: " + marker);
                    try { Thread.sleep(10); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException("Interrupted checkpoint lease");
                    }
                }
                return lease;
            } catch (IOException | RuntimeException failure) {
                if (lease.channel != null) try { lease.channel.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
        }
        @Override public void close() throws IOException {
            IOException failure = null;
            boolean channelClosed = false;
            try { if (lock != null && lock.isValid()) lock.release(); }
            catch (IOException problem) { failure = problem; }
            finally {
                if (channel != null) try { channel.close(); channelClosed = true; }
                catch (IOException problem) { if (failure == null) failure = problem; else failure.addSuppressed(problem); }
            }
            if (lock != null && channelClosed && !lock.isValid()) {
                try { Files.deleteIfExists(marker); }
                catch (IOException problem) { if (failure == null) failure = problem; else failure.addSuppressed(problem); }
            }
            if (failure != null) throw failure;
        }
    }


    public WorldStore(Path directory) {
        this.directory = directory;
    }

    public DimensionState load(int dimension, WorldSnapshotProvider provider, List<PendingPulse> pulses)
        throws IOException {
        loadStatus = ProfilerCounters.CheckpointStatus.FAILED;
        Files.createDirectories(directory);
        IOException failure = null;
        for (String pointer : new String[] { "CURRENT", "PREVIOUS" }) {
            Path index = directory.resolve(pointer);
            if (!Files.exists(index)) {
                continue;
            }
            try {
                String name = new String(Files.readAllBytes(index), StandardCharsets.US_ASCII).trim();
                if (!name.matches("bundle-[0-9a-f-]{36}")) {
                    throw new IOException("Invalid SMOG pointer");
                }
                Path bundle = directory.resolve(name);
                List<PendingPulse> restored = readPulses(bundle.resolve("pending.bin"), dimension);
                DimensionState state = DimensionState.load(bundle.resolve("core"), provider);
                if (state.dimension != dimension) {
                    state.close();
                    throw new IOException("Wrong dimension");
                }
                pulses.addAll(restored);
                this.state = state;
                loadStatus = "PREVIOUS".equals(pointer) ? ProfilerCounters.CheckpointStatus.FALLBACK_PREVIOUS
                    : state.restartMetrics.get("checkpoint_cells") == 0 ? ProfilerCounters.CheckpointStatus.EMPTY
                    : ProfilerCounters.CheckpointStatus.LOADED;
                state.profile.checkpoint(loadStatus, "PREVIOUS".equals(pointer));
                this.pulses = pulses;
                lastValid = name;
                committedVersion = version;
                if (failure != null) {
                    System.err.println("SMOG recovered PREVIOUS after: " + failure);
                }
                return state;
            } catch (IOException exception) {
                failure = exception;
            }
        }
        if (failure != null) {
            loadStatus = ProfilerCounters.CheckpointStatus.FAILED;
            throw failure;
        }
        loadStatus = ProfilerCounters.CheckpointStatus.MISSING;
        state = new DimensionState(dimension, SmogConfig.throat8(), provider);
        this.pulses = pulses;
        return state;
    }

    /** Call for runtime mutations, including time/phase changes, without scanning the graph. */
    public void markDirty() {
        version++;
    }

    /** Request/coalesce only. No disk access and no waiting in a gameplay event. */
    public void save(DimensionState state, List<PendingPulse> pulses) {
        long start = System.nanoTime();
        try {
            if (this.state != null && this.state != state) {
                throw new IllegalStateException("A WorldStore belongs to one dimension state");
            }
            this.state = state;
            this.pulses = pulses;
            requests++;
            pollSaveCompletion();
            if (active != null) {
                coalesced++;
                // Before SAFE CUT the active request already covers the latest version.
                if (cutVersion >= 0 && cutVersion != version) {
                    requested = true;
                }
            } else {
                requested = true;
                startRequested();
            }
        } finally {
            maxServerNanos = Math.max(maxServerNanos, System.nanoTime() - start);
        }
    }

    /** Future completion is observed without get/join/wait in ordinary ticks. */
    public void pollSaveCompletion() {
        long start = System.nanoTime();
        try {
            if (active != null && active.isDone()) {
                try {
                    active.getNow(null);
                    committedVersion = cutVersion;
                    if (committedVersion == version) {
                        requested = false;
                    }
                    completed++;
                    phase = "IDLE";
                    System.out.println("SMOG checkpoint committed: " + lastValid);
                } catch (CompletionException exception) {
                    failed++;
                    state.profile.failure(ProfilerCounters.SAVE_POLL);
                    state.profile.reason(ProfilerCounters.IO_ERROR, 0);
                    String failedPhase = phase;
                    phase = "FAILED";
                    error = exception.getCause()
                        .toString();
                    System.err.println("SMOG WorldStore operation=" + (failedOperation.isEmpty() ? operation : failedOperation)
                        + " path=" + (failedPath.isEmpty() ? operationPath : failedPath)
                        + " timestamp=" + java.time.Instant.now() + " dimension=" + state.dimension
                        + " writerPhase=" + failedPhase + " saveStatus=FAILED lastCommitted=" + lastValid);
                    exception.getCause().printStackTrace(System.err);
                    requested = false;
                }
                active = null;
            }
            if (active == null && requested) {
                startRequested();
            }
        } finally {
            long duration = System.nanoTime() - start;
            pollNanos += duration;
            if (state != null) state.profile.record(ProfilerCounters.SAVE_POLL, duration, 1);
            maxServerNanos = Math.max(maxServerNanos, duration);
        }
    }

    private void startRequested() {
        requested = false;
        cutVersion = -1;
        requestedAt = System.nanoTime();
        phase = "REQUESTED";
        error = ""; failedOperation = failedPath = "";
        Path bundle = directory.resolve("bundle-" + UUID.randomUUID());
        active = state.requestSave(bundle.resolve("core"), () -> {
            long start = System.nanoTime();
            safeCutWaitNanos = start - requestedAt;
            cutVersion = version;
            // PendingPulse and BlockPos contain final fields. The list itself is
            // detached.
            final List<PendingPulse> snapshot = new ArrayList<>(pulses);
            queueCopyNanos = System.nanoTime() - start;
            phase = "CAPTURING";
            return new DimensionState.SaveAttachment() {
                private BundleLease lease;
                private boolean committed;

                @Override
                public void beforeWrite() {
                    try {
                        Files.createDirectories(bundle);
                        operation = "lease-acquire"; operationPath = directory.relativize(bundle.resolve(".in-progress")).toString();
                        long timeout = Math.max(0, Math.min(10000, Integer.getInteger("smog.checkpointLockTimeoutMs", 2000)));
                        lease = BundleLease.acquire(bundle.resolve(".in-progress"), timeout);
                    } catch (IOException failure) {
                        failedOperation = operation; failedPath = operationPath;
                        throw new java.io.UncheckedIOException(failure);
                    }
                    phase = "WRITING";
                    operation = "core-write"; operationPath = directory.relativize(bundle.resolve("core")).toString();
                    writeStarted = System.nanoTime();
                }

                @Override
                public void afterWrite() throws IOException {
                    if (!committed && failedOperation.isEmpty()) { failedOperation = operation; failedPath = operationPath; }
                    operation = "lease-release"; operationPath = directory.relativize(bundle.resolve(".in-progress")).toString();
                    IOException failure = null;
                    try {
                        if (!committed && Files.isDirectory(bundle)) Files.write(bundle.resolve(".aborted"), new byte[] {1});
                    } catch (IOException problem) { failure = problem; }
                    finally {
                        if (lease != null) try { lease.close(); }
                        catch (IOException problem) { if (failure == null) failure = problem; else failure.addSuppressed(problem); }
                    }
                    if (failure != null) throw failure;
                }

                @Override
                public void commit(Path core) throws IOException {
                    operation = "pending-write"; operationPath = directory.relativize(bundle.resolve("pending.bin")).toString();
                    writePulses(bundle, snapshot);
                    force(bundle);
                    writeNanos = System.nanoTime() - writeStarted;
                    phase = "COMMITTING";
                    long start = System.nanoTime();
                    operation = "pointer-commit"; operationPath = "CURRENT/PREVIOUS";
                    commitBundle(bundle);
                    committed = true;
                    lastValid = bundle.getFileName()
                        .toString();
                    commitNanos = System.nanoTime() - start;
                    state.profileCheckpointCommit(commitNanos);
                    lastSuccessMillis = System.currentTimeMillis();
                    try {
                        cleanupBundles(bundle);
                    } catch (IOException | RuntimeException cleanupFailure) {
                        // CURRENT is durable already. Maintenance cannot fail this save.
                        cleanupFailures++;
                        System.err.println("SMOG old bundle cleanup deferred: " + cleanupFailure);
                    }
                }
            };
        });
    }

    private void commitBundle(Path bundle) throws IOException {
        Path previous = directory.resolve("PREVIOUS");
        byte[] oldPrevious = Files.exists(previous) ? Files.readAllBytes(previous) : null;
        try {
            // Never promote an uncommitted or known-corrupt bundle to PREVIOUS.
            if (lastValid != null) {
                pointer("PREVIOUS", lastValid);
            }
            pointer(
                "CURRENT",
                bundle.getFileName()
                    .toString());
        } catch (IOException failure) {
            // A rejected CURRENT replacement must not leave both pointers on the same bundle.
            // A crash during this rollback still retains a complete valid CURRENT bundle.
            try {
                if (oldPrevious == null) {
                    Files.deleteIfExists(previous);
                    force(directory);
                } else {
                    pointer("PREVIOUS", new String(oldPrevious, StandardCharsets.US_ASCII).trim());
                }
            } catch (IOException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    /** Writer-only, after both durable pointer replacements. Never follows symlinks. */
    private void cleanupBundles(Path committed) throws IOException {
        Set<String> keep = new HashSet<>();
        keep.add(committed.getFileName().toString());
        for (String pointer : new String[] { "CURRENT", "PREVIOUS" }) {
            Path path = directory.resolve(pointer);
            if (!Files.exists(path)) continue;
            String name = new String(Files.readAllBytes(path), StandardCharsets.US_ASCII).trim();
            if (!name.matches("bundle-[0-9a-f-]{36}")) throw new IOException("Invalid cleanup pointer");
            keep.add(name);
        }
        try (java.nio.file.DirectoryStream<Path> bundles = Files.newDirectoryStream(directory, "bundle-*")) {
            for (Path path : bundles) {
                String name = path.getFileName().toString();
                if (!name.matches("bundle-[0-9a-f-]{36}") || keep.contains(name)
                    || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                // Incomplete writes (including another operation's temporary payloads)
                // are deliberately left alone. Completed crash orphans can be collected.
                boolean aborted = Files.isRegularFile(path.resolve(".aborted"), LinkOption.NOFOLLOW_LINKS);
                if (!aborted && (!Files.isRegularFile(path.resolve("core/manifest"), LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(path.resolve("pending.bin"), LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(path.resolve("pending.sha256"), LinkOption.NOFOLLOW_LINKS))) continue;
                Path marker = path.resolve(".in-progress");
                if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) continue;
                    // Process death releases the OS lock; a live writer's full payload
                    // must be protected even before CURRENT points to it.
                    try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(marker,
                        java.nio.file.StandardOpenOption.WRITE)) {
                        java.nio.channels.FileLock lease;
                        try { lease = channel.tryLock(); }
                        catch (java.nio.channels.OverlappingFileLockException activeWriter) { continue; }
                        if (lease == null) continue;
                        lease.release();
                    }
                }
                try (java.util.stream.Stream<Path> tree = Files.walk(path)) {
                    Path[] ordered = tree.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
                    for (Path item : ordered) Files.deleteIfExists(item);
                }
            }
        }
    }

    private static void writePulses(Path bundle, List<PendingPulse> snapshot) throws IOException {
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(bundle.resolve("pending.bin")))) {
            out.writeInt(0x534D5051);
            out.writeInt(1);
            out.writeInt(snapshot.size());
            for (PendingPulse pulse : snapshot) {
                out.writeInt(pulse.position.x);
                out.writeInt(pulse.position.y);
                out.writeInt(pulse.position.z);
                out.writeLong(pulse.amount);
                out.writeBoolean(pulse.chunkOnly);
                out.writeBoolean(pulse.adjacent);
            }
        }
        Files.write(bundle.resolve("pending.sha256"), hash(Files.readAllBytes(bundle.resolve("pending.bin"))));
        force(bundle.resolve("pending.bin"));
        force(bundle.resolve("pending.sha256"));
    }

    /** The only waiting path: bounded final flush after gameplay has stopped. */
    public void saveAndAwaitForShutdown(DimensionState state, List<PendingPulse> pulses, long timeoutMillis)
        throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        this.state = state;
        this.pulses = pulses;
        pollSaveCompletion();
        if (active == null && committedVersion != version) {
            save(state, pulses);
        } else if (active != null && cutVersion >= 0 && cutVersion != version) {
            requested = true;
        }
        while (active != null || requested) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("SMOG final checkpoint timeout in " + phase + "; data not discarded");
            }
            state.geometry(Math.min(TimeUnit.MILLISECONDS.toNanos(2), remaining));
            if ("REQUESTED".equals(state.checkpointPhase())) {
                state.finishPendingPart(20);
            }
            if ("WRITING".equals(state.checkpointPhase()) && active != null) {
                try {
                    active.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (java.util.concurrent.ExecutionException exception) {
                    pollSaveCompletion();
                    throw new IOException("SMOG final checkpoint failed: " + error, exception.getCause());
                }
            }
            pollSaveCompletion();
            if (active == null && committedVersion != version && !"FAILED".equals(phase)) {
                requested = true;
                startRequested();
            }
        }
        if ("FAILED".equals(phase)) {
            throw new IOException("SMOG final checkpoint failed: " + error);
        }
    }

    public boolean inProgress() {
        return active != null || requested;
    }

    public String status() {
        String currentPhase = phase;
        if (active != null && !active.isDone()
            && !"WRITING".equals(currentPhase)
            && !"COMMITTING".equals(currentPhase)) {
            currentPhase = state.checkpointPhase();
        }
        return "save=" + currentPhase
            + " bundle="
            + lastValid
            + " commitsSession="
            + completed
            + " coreGeneration="
            + (state == null ? 0 : state.checkpointGeneration())
            + " lastSuccessMillis="
            + lastSuccessMillis
            + " requests="
            + requests
            + " coalesced="
            + coalesced
            + " waitMs="
            + safeCutWaitNanos / 1e6
            + " captureWallMs="
            + (state == null ? 0 : state.captureWallNanos / 1e6)
            + " captureCpuMs="
            + (state == null ? 0 : state.captureCpuNanos / 1e6)
            + " queueCopyMs="
            + queueCopyNanos / 1e6
            + " writeFsyncMs="
            + writeNanos / 1e6
            + " commitMs="
            + commitNanos / 1e6
            + " ioCpuTotalMs="
            + (state == null ? 0 : state.ioCpuNanos / 1e6)
            + " maxServerMs="
            + Math.max(maxServerNanos, state == null ? 0 : state.maxCaptureTickNanos) / 1e6
            + " maxCaptureMutationMs="
            + (state == null ? 0 : state.maxCaptureMutationNanos / 1e6)
            + " error="
            + error;
    }

    private void pointer(String pointer, String value) throws IOException {
        Path temp = directory.resolve(pointer + ".tmp");
        Files.write(temp, value.getBytes(StandardCharsets.US_ASCII));
        force(temp);
        try {
            Files.move(
                temp,
                directory.resolve(pointer),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            throw new IOException("Atomic SMOG pointer required", exception);
        }
        force(directory);
    }

    private static void force(Path path) throws IOException {
        boolean directory = Files.isDirectory(path);
        if (directory && System.getProperty("os.name", "")
            .startsWith("Windows")) {
            // Windows rejects FileChannel.open(directory) even when file writes work.
            // Regular checkpoint files still require fsync and atomic pointer moves.
            return;
        }
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel
            .open(path, directory ? java.nio.file.StandardOpenOption.READ : java.nio.file.StandardOpenOption.WRITE)) {
            channel.force(true);
        } catch (UnsupportedOperationException unsupported) {
            if (!directory) {
                throw new IOException("SMOG file synchronization unsupported", unsupported);
            }
        } catch (AccessDeniedException unsupportedDirectorySync) {
            if (!directory) {
                throw unsupportedDirectorySync;
            }
        }
    }

    private List<PendingPulse> readPulses(Path path, int dimension) throws IOException {
        if (!java.util.Arrays
            .equals(hash(Files.readAllBytes(path)), Files.readAllBytes(path.resolveSibling("pending.sha256")))) {
            throw new IOException("SMOG queue checksum mismatch");
        }
        List<PendingPulse> pulses = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(Files.newInputStream(path))) {
            if (in.readInt() != 0x534D5051 || in.readInt() != 1) {
                throw new IOException("Unsupported SMOG queue");
            }
            int count = in.readInt();
            if (count < 0 || count > 1000000) {
                throw new IOException("Invalid SMOG queue size");
            }
            for (int i = 0; i < count; i++) {
                pulses.add(
                    new PendingPulse(
                        new BlockPos(dimension, in.readInt(), in.readInt(), in.readInt()),
                        in.readLong(),
                        in.readBoolean(),
                        in.readBoolean()));
            }
            if (in.read() != -1) {
                throw new IOException("Trailing queue data");
            }
        }
        return pulses;
    }

    private static byte[] hash(byte[] bytes) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}

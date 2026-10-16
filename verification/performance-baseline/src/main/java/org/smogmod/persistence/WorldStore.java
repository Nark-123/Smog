package org.smogmod.persistence;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.smogmod.compat.gt5u.PendingPulse;

import smog.BlockPos;
import smog.DimensionState;
import smog.SmogConfig;
import smog.WorldSnapshotProvider;

/**
 * Commit core checkpoint and deferred emissions together by an atomic pointer. Server thread only.
 */
public final class WorldStore {

    private final Path directory;

    private String lastValid;

    public WorldStore(Path directory) {
        this.directory = directory;
    }

    public DimensionState load(int dimension, WorldSnapshotProvider provider, List<PendingPulse> pulses)
        throws IOException {
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
                lastValid = name;
                if (failure != null) {
                    System.err.println("SMOG recovered PREVIOUS after: " + failure);
                }
                return state;
            } catch (IOException exception) {
                failure = exception;
            }
        }
        if (failure != null) {
            throw failure;
        }
        return new DimensionState(dimension, SmogConfig.throat8(), provider);
    }

    public void save(DimensionState state, List<PendingPulse> pulses) throws Exception {
        String name = "bundle-" + UUID.randomUUID();
        Path bundle = directory.resolve(name);
        Files.createDirectories(bundle);
        CompletableFuture<Path> save = state.requestSave(bundle.resolve("core"));
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!save.isDone()) {
            if (System.nanoTime() >= end) {
                throw new IOException("SMOG checkpoint timeout");
            }
            state.geometry(TimeUnit.MILLISECONDS.toNanos(2));
            state.finishPendingPart(20);
            Thread.yield();
        }
        save.get();
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(bundle.resolve("pending.bin")))) {
            out.writeInt(0x534D5051);
            out.writeInt(1);
            out.writeInt(pulses.size());
            for (PendingPulse pulse : pulses) {
                out.writeInt(pulse.position.x);
                out.writeInt(pulse.position.y);
                out.writeInt(pulse.position.z);
                out.writeLong(pulse.amount);
                out.writeBoolean(pulse.chunkOnly);
                out.writeBoolean(pulse.adjacent);
            }
        }
        // Hash the queue too: a corrupt deferred event must not become a different emission.
        Files.write(bundle.resolve("pending.sha256"), hash(Files.readAllBytes(bundle.resolve("pending.bin"))));
        force(bundle.resolve("pending.bin"));
        force(bundle.resolve("pending.sha256"));
        force(bundle);
        if (lastValid != null) {
            pointer("PREVIOUS", lastValid);
        }
        pointer("CURRENT", name);
        lastValid = name;
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
        if (directory && System.getProperty("os.name", "").startsWith("Windows")) {
            // Windows rejects FileChannel.open(directory) even when file writes work.
            // Regular checkpoint files still require fsync and atomic pointer moves.
            return;
        }
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
            path,
            directory ? java.nio.file.StandardOpenOption.READ : java.nio.file.StandardOpenOption.WRITE)) {
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

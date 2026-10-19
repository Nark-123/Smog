package org.smogmod.world;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.Chunk;

import smog.CubeKey;
import smog.ImmutableCubeSnapshot;
import smog.SmogConfig;
import smog.WorldSnapshotProvider;

/**
 * Only registered loaded Chunk references are read. No world getBlock/provideChunk calls.
 */
public final class ForgeWorldSnapshotProvider implements WorldSnapshotProvider {

    private static final class Column {

        final Chunk chunk;

        final long epoch;

        long revision;

        Column(Chunk chunk, long epoch) {
            this.chunk = chunk;
            this.epoch = epoch;
        }
    }

    private final Thread owner = Thread.currentThread();

    private final int dimension;

    private long nextEpoch;

    private final Map<Long, Column> columns = new HashMap<>();

    private final Map<CubeKey, ImmutableCubeSnapshot> snapshots = new HashMap<>();

    /** Owner-thread-only diagnostic counters; no persistent data or per-block timers. */
    private final Diagnostics diagnostics = new Diagnostics();

    public static final class Diagnostics {

        public long hits, misses, unavailable, buildNanos, maxBuildNanos, buildFailures;
        public long invalidations, invalidateNanos, maxInvalidateNanos, evictedSnapshots;
        public long loads, unloads, revisions;

        private void reset() {
            hits = misses = unavailable = buildNanos = maxBuildNanos = buildFailures = 0;
            invalidations = invalidateNanos = maxInvalidateNanos = evictedSnapshots = 0;
            loads = unloads = revisions = 0;
        }
    }

    public Diagnostics diagnostics() {
        checkThread();
        return diagnostics;
    }

    public void resetDiagnostics() {
        checkThread();
        diagnostics.reset();
    }

    public int loadedColumnCount() {
        checkThread();
        return columns.size();
    }

    public int cachedSnapshotCount() {
        checkThread();
        return snapshots.size();
    }

    public ForgeWorldSnapshotProvider(int dimension) {
        this.dimension = dimension;
    }

    public void checkThread() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("SMOG world access outside owner thread");
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    public void load(Chunk chunk) {
        checkThread();
        diagnostics.loads++;
        Column old = columns.get(key(chunk.xPosition, chunk.zPosition));
        if (old != null && old.chunk == chunk) {
            return;
        }
        columns.put(key(chunk.xPosition, chunk.zPosition), new Column(chunk, ++nextEpoch));
        discard(chunk.xPosition, chunk.zPosition);
    }

    public long unload(int x, int z) {
        checkThread();
        diagnostics.unloads++;
        Column old = columns.remove(key(x, z));
        discard(x, z);
        return old == null ? nextEpoch : old.epoch;
    }

    public void revise(int x, int z) {
        checkThread();
        diagnostics.revisions++;
        Column column = columns.get(key(x, z));
        if (column != null) {
            column.revision++;
            discard(x, z);
        }
    }

    private void discard(int x, int z) {
        long start = System.nanoTime();
        int before = snapshots.size();
        snapshots.keySet()
            .removeIf(c -> c.x == x && c.z == z);
        long duration = System.nanoTime() - start;
        diagnostics.invalidations++;
        diagnostics.evictedSnapshots += before - snapshots.size();
        diagnostics.invalidateNanos += duration;
        diagnostics.maxInvalidateNanos = Math.max(diagnostics.maxInvalidateNanos, duration);
    }

    @Override
    public boolean isReady(CubeKey cube) {
        checkThread();
        return cube.dimension == dimension && cube.y >= 0 && cube.y < 16 && columns.containsKey(key(cube.x, cube.z));
    }

    @Override
    public ImmutableCubeSnapshot snapshotIfReady(CubeKey cube) {
        if (!isReady(cube)) {
            diagnostics.unavailable++;
            return null;
        }
        ImmutableCubeSnapshot cached = snapshots.get(cube);
        if (cached != null) {
            diagnostics.hits++;
            return cached;
        }
        diagnostics.misses++;
        long buildStart = System.nanoTime();
        try {
            Column column = columns.get(key(cube.x, cube.z));
            byte[] air = new byte[4096];
            byte[] atmosphere = new byte[4096];
            for (int y = 0; y < 16; y++) {
                int worldY = cube.y * 16 + y;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        Block block = column.chunk.getBlock(x, worldY, z);
                        int metadata = column.chunk.getBlockMetadata(x, worldY, z);
                        boolean open = block == Blocks.air || block == Blocks.fire
                            || block == Blocks.torch
                            || block == Blocks.redstone_wire;
                        if (block instanceof BlockDoor) {
                            if ((metadata & 8) != 0 && worldY > 0) {
                                metadata = column.chunk.getBlockMetadata(x, worldY - 1, z);
                            }
                            open = (metadata & 4) != 0;
                        } else if (block instanceof BlockTrapDoor || block instanceof BlockFenceGate) {
                            open = (metadata & 4) != 0;
                        }
                        int index = x + 16 * y + 256 * z;
                        air[index] = (byte) (open ? 1 : 0);
                        if (open && worldY == 255) {
                            atmosphere[index] = SmogConfig.Y_PLUS;
                        }
                    }
                }
            }
            cached = new ImmutableCubeSnapshot(cube, column.epoch, column.revision, air, atmosphere);
            snapshots.put(cube, cached);
            return cached;
        } catch (RuntimeException error) {
            diagnostics.buildFailures++;
            throw error;
        } finally {
            long duration = System.nanoTime() - buildStart;
            diagnostics.buildNanos += duration;
            diagnostics.maxBuildNanos = Math.max(diagnostics.maxBuildNanos, duration);
        }
    }
}

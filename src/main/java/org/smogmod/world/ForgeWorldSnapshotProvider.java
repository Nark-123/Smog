package org.smogmod.world;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
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

    private static final int SECTION_COUNT = 16;

    private static final class Column {

        final Chunk chunk;
        final long epoch;
        // Exact vertical-section revisions; no false invalidation at other heights.
        final long[] revisions = new long[SECTION_COUNT];
        final ImmutableCubeSnapshot[] snapshots = new ImmutableCubeSnapshot[SECTION_COUNT];

        Column(Chunk chunk, long epoch) {
            this.chunk = chunk;
            this.epoch = epoch;
        }
    }

    private final Thread owner = Thread.currentThread();

    private final int dimension;

    private long nextEpoch;

    private final Map<Long, Column> columns = new HashMap<>();

    private int cachedSnapshots;
    private final int snapshotLimit = Math
        .max(16, Math.min(32768, Integer.getInteger("smog.snapshotCacheSections", 2048)));
    private final LinkedHashMap<CubeKey, ImmutableCubeSnapshot> snapshotLru = new LinkedHashMap<>(16, .75f, true);

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
        return cachedSnapshots;
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
        long k = key(chunk.xPosition, chunk.zPosition);
        Column old = columns.get(k);
        if (old != null && old.chunk == chunk) {
            return;
        }
        long started = System.nanoTime();
        if (old != null) {
            dropSnapshots(old);
        }
        columns.put(k, new Column(chunk, ++nextEpoch));
        recordInvalidation(started);
    }

    public long unload(int x, int z) {
        checkThread();
        diagnostics.unloads++;
        long started = System.nanoTime();
        Column old = columns.remove(key(x, z));
        if (old != null) {
            dropSnapshots(old);
        }
        recordInvalidation(started);
        return old == null ? nextEpoch : old.epoch;
    }

    /**
     * Compatibility entry point for callers that do not know the Y coordinate.
     * The runtime uses reviseBlock/reviseRange so normal edits remain local.
     */
    public void revise(int x, int z) {
        checkThread();
        diagnostics.revisions++;
        Column column = columns.get(key(x, z));
        if (column == null) {
            return;
        }
        long started = System.nanoTime();
        for (int y = 0; y < SECTION_COUNT; y++) {
            expireSection(column, y);
        }
        recordInvalidation(started);
    }

    /** Invalidates exactly the changed section and upper door-dependent section. */
    public void reviseBlock(int worldX, int worldY, int worldZ) {
        checkThread();
        diagnostics.revisions++;
        if (worldY < 0 || worldY >= 256) {
            return;
        }
        Column column = columns.get(key(worldX >> 4, worldZ >> 4));
        if (column == null) {
            return;
        }
        long started = System.nanoTime();
        int section = worldY >> 4;
        expireSection(column, section);
        // The upper half of a door reads metadata from y - 1, even across 16^3 cubes.
        if ((worldY & 15) == 15 && section + 1 < SECTION_COUNT) {
            expireSection(column, section + 1);
        }
        recordInvalidation(started);
    }

    /** Inclusive world-space Y interval, bounded to Minecraft's 0..255. */
    public void reviseRange(int columnX, int columnZ, int minY, int maxY) {
        checkThread();
        if (minY > maxY) {
            throw new IllegalArgumentException("Reversed Y range");
        }
        diagnostics.revisions++;
        int lower = Math.max(0, minY), upper = Math.min(255, maxY);
        if (lower > upper) {
            return;
        }
        Column column = columns.get(key(columnX, columnZ));
        if (column == null) {
            return;
        }
        long started = System.nanoTime();
        int lastSection = upper >> 4;
        // Only an edit to the last block of a section influences the section above.
        if ((upper & 15) == 15 && lastSection < SECTION_COUNT - 1) {
            lastSection++;
        }
        for (int section = lower >> 4; section <= lastSection; section++) {
            expireSection(column, section);
        }
        recordInvalidation(started);
    }

    private void expireSection(Column column, int section) {
        column.revisions[section]++;
        if (column.snapshots[section] != null) {
            snapshotLru.remove(column.snapshots[section].key);
            column.snapshots[section] = null;
            cachedSnapshots--;
            diagnostics.evictedSnapshots++;
        }
    }

    private void dropSnapshots(Column column) {
        for (int section = 0; section < SECTION_COUNT; section++) {
            if (column.snapshots[section] != null) {
                snapshotLru.remove(column.snapshots[section].key);
                column.snapshots[section] = null;
                cachedSnapshots--;
                diagnostics.evictedSnapshots++;
            }
        }
    }

    private void recordInvalidation(long started) {
        long duration = System.nanoTime() - started;
        diagnostics.invalidations++;
        diagnostics.invalidateNanos += duration;
        diagnostics.maxInvalidateNanos = Math.max(diagnostics.maxInvalidateNanos, duration);
    }

    @Override
    public boolean isReady(CubeKey cube) {
        checkThread();
        return cube.dimension == dimension && cube.y >= 0
            && cube.y < SECTION_COUNT
            && columns.containsKey(key(cube.x, cube.z));
    }

    @Override
    public boolean matchesSnapshot(CubeKey cube, ImmutableCubeSnapshot captured) {
        checkThread();
        if (cube.dimension != dimension || cube.y < 0 || cube.y >= SECTION_COUNT) {
            return captured == null;
        }
        Column column = columns.get(key(cube.x, cube.z));
        if (column == null) {
            return captured == null;
        }
        return captured != null && cube.equals(captured.key)
            && captured.epoch == column.epoch
            && captured.revision == column.revisions[cube.y];
    }

    @Override
    public ImmutableCubeSnapshot snapshotIfReady(CubeKey cube) {
        checkThread();
        Column column = cube.dimension == dimension && cube.y >= 0 && cube.y < SECTION_COUNT
            ? columns.get(key(cube.x, cube.z))
            : null;
        if (column == null) {
            diagnostics.unavailable++;
            return null;
        }
        ImmutableCubeSnapshot cached = column.snapshots[cube.y];
        if (cached != null) {
            snapshotLru.get(cube);
            diagnostics.hits++;
            return cached;
        }
        diagnostics.misses++;
        long buildStart = System.nanoTime();
        try {
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
            cached = new ImmutableCubeSnapshot(cube, column.epoch, column.revisions[cube.y], air, atmosphere);
            column.snapshots[cube.y] = cached;
            cachedSnapshots++;
            snapshotLru.put(cube, cached);
            while (snapshotLru.size() > snapshotLimit) {
                Iterator<Map.Entry<CubeKey, ImmutableCubeSnapshot>> it = snapshotLru.entrySet()
                    .iterator();
                Map.Entry<CubeKey, ImmutableCubeSnapshot> e = it.next();
                CubeKey key = e.getKey();
                Column old = columns.get(key(key.x, key.z));
                if (old != null && old.snapshots[key.y] == e.getValue()) {
                    old.snapshots[key.y] = null;
                    cachedSnapshots--;
                    diagnostics.evictedSnapshots++;
                }
                it.remove();
            }
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

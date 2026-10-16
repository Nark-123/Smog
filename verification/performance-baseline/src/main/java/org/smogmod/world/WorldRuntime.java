package org.smogmod.world;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

import org.smogmod.compat.gt5u.PendingPulse;
import org.smogmod.persistence.WorldStore;

import smog.BlockPos;
import smog.CubeKey;
import smog.DimensionState;
import smog.ImmutableCubeSnapshot;
import smog.SpatialQueries;
import smog.WorldAdapters;

public final class WorldRuntime implements AutoCloseable {

    public final WorldServer world;

    public final ForgeWorldSnapshotProvider provider;

    public final DimensionState state;

    public final List<PendingPulse> pending = new ArrayList<>();

    private final Set<BlockPos> changes = new HashSet<>();

    private final WorldStore store;

    private boolean seeded;

    private long lastTick = Long.MIN_VALUE;

    public long acceptedPulses;

    public WorldRuntime(WorldServer world) throws Exception {
        this.world = world;
        provider = new ForgeWorldSnapshotProvider(world.provider.dimensionId);
        store = new WorldStore(
            world.getSaveHandler()
                .getWorldDirectory()
                .toPath()
                .resolve("data/smog/dimension_" + world.provider.dimensionId));
        state = store.load(world.provider.dimensionId, provider, pending);
    }

    public void accept(PendingPulse pulse) {
        provider.checkThread();
        if (pending.size() >= 1000000) {
            throw new IllegalStateException("SMOG pending queue full; refusing silent loss");
        }
        if (pulse.amount != 0) {
            pending.add(pulse);
            acceptedPulses++;
        }
    }

    public void load(Chunk chunk) {
        provider.load(chunk);
        WorldAdapters.columnReady(state, chunk.xPosition, chunk.zPosition);
    }

    public void unload(Chunk chunk) {
        long epoch = provider.unload(chunk.xPosition, chunk.zPosition);
        for (int y = 0; y < 16; y++) {
            WorldAdapters
                .cubeUnloading(state, new CubeKey(state.dimension, chunk.xPosition, y, chunk.zPosition), epoch);
        }
    }

    public void changed(int x, int y, int z) {
        provider.revise(x >> 4, z >> 4);
        changes.add(new BlockPos(state.dimension, x, y, z));
    }

    public void invalidate(BlockPos min, BlockPos max) {
        for (Object value : ((ChunkProviderServer) world.getChunkProvider()).loadedChunks) {
            Chunk chunk = (Chunk) value;
            if ((long) chunk.xPosition * 16 <= max.x && (long) chunk.xPosition * 16 + 15 >= min.x
                && (long) chunk.zPosition * 16 <= max.z
                && (long) chunk.zPosition * 16 + 15 >= min.z) {
                provider.revise(chunk.xPosition, chunk.zPosition);
            }
        }
        state.invalidateRegion(min, max);
    }

    public void tick() {
        provider.checkThread();
        long now = world.getTotalWorldTime();
        if (lastTick == now) {
            return;
        }
        lastTick = now;
        if (!seeded) {
            for (Object chunk : ((ChunkProviderServer) world.getChunkProvider()).loadedChunks) {
                provider.load((Chunk) chunk);
            }
            state.resumeReadyCubes();
            seeded = true;
        }
        if (!changes.isEmpty()) {
            state.invalidateBlocks(changes);
            changes.clear();
        }
        for (Iterator<PendingPulse> iterator = pending.iterator(); iterator.hasNext();) {
            if (deliver(iterator.next())) {
                iterator.remove();
            }
        }
        state.tick(2000000L, 20, false);
        if (now % 6000 == 0) {
            save();
        }
    }

    private boolean deliver(PendingPulse pulse) {
        BlockPos position = pulse.position;
        if (pulse.amount < 0) {
            if (pulse.chunkOnly || pulse.adjacent) {
                SpatialQueries.removeChunk(state, position.x >> 4, position.z >> 4, -(double) pulse.amount);
            } else {
                state.queueRemoveMass(position, -(double) pulse.amount);
            }
            return true;
        }
        if (pulse.chunkOnly) {
            return deliverColumn(pulse);
        }
        if (state.queueAddMass(position, pulse.amount)) {
            return true;
        }
        if (pulse.adjacent) {
            for (int[] side : SIDES) {
                if (state.queueAddMass(position.offset(side[0], side[1], side[2]), pulse.amount)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final int[][] SIDES = { { 0, 1, 0 }, { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 }, { 0, 0, -1 },
        { 0, -1, 0 } };

    /**
     * Unknown Y: choose the first gas voxel in every loaded section, weighted by section gas volume.
     */
    private boolean deliverColumn(PendingPulse pulse) {
        List<BlockPos> points = new ArrayList<>();
        List<Integer> volumes = new ArrayList<>();
        int total = 0;
        for (int section = 0; section < 16; section++) {
            ImmutableCubeSnapshot snapshot = provider
                .snapshotIfReady(new CubeKey(state.dimension, pulse.position.x >> 4, section, pulse.position.z >> 4));
            if (snapshot == null) {
                continue;
            }
            BlockPos first = null;
            int volume = 0;
            for (int y = section * 16; y < section * 16 + 16; y++) {
                for (int z = pulse.position.z; z < pulse.position.z + 16; z++) {
                    for (int x = pulse.position.x; x < pulse.position.x + 16; x++) {
                        if (snapshot.airAt(x, y, z)) {
                            if (first == null) {
                                first = new BlockPos(state.dimension, x, y, z);
                            }
                            volume++;
                        }
                    }
                }
            }
            if (first != null) {
                points.add(first);
                volumes.add(volume);
                total += volume;
            }
        }
        if (total == 0) {
            return false;
        }
        double remaining = pulse.amount;
        for (int i = 0; i < points.size(); i++) {
            double part = i == points.size() - 1 ? remaining : pulse.amount * (double) volumes.get(i) / total;
            if (!state.queueAddMass(points.get(i), part)) {
                throw new IllegalStateException("SMOG snapshot changed during delivery");
            }
            remaining -= part;
        }
        return true;
    }

    public void save() {
        provider.checkThread();
        try {
            store.save(state, pending);
        } catch (Exception exception) {
            throw new IllegalStateException("SMOG world save failed", exception);
        }
    }

    @Override
    public void close() {
        save();
        state.close();
    }
}

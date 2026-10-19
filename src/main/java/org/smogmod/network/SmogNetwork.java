package org.smogmod.network;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraftforge.event.world.ChunkWatchEvent;

import org.smogmod.compat.gt5u.SmogGt5uBridge;
import org.smogmod.world.WorldRuntime;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import smog.SpatialQueries;

public final class SmogNetwork {

    public static final SmogNetwork INSTANCE = new SmogNetwork();

    private static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("smog");

    private final Map<UUID, Map<ChunkCoordIntPair, Integer>> watched = new HashMap<>();

    public void init() {
        CHANNEL.registerMessage(PollutionMessage.Handler.class, PollutionMessage.class, 0, Side.CLIENT);
    }

    private void send(EntityPlayerMP player, ChunkCoordIntPair chunk, int amount, boolean local) {
        CHANNEL.sendTo(new PollutionMessage(player.dimension, chunk.chunkXPos, chunk.chunkZPos, amount, local), player);
    }

    @SubscribeEvent
    public void watch(ChunkWatchEvent.Watch e) {
        int amount = SmogGt5uBridge.read(e.player.worldObj, e.chunk.chunkXPos, e.chunk.chunkZPos);
        watched.computeIfAbsent(e.player.getUniqueID(), id -> new HashMap<>())
            .put(e.chunk, amount);
        send(e.player, e.chunk, amount, false);
    }

    @SubscribeEvent
    public void unwatch(ChunkWatchEvent.UnWatch e) {
        Map<ChunkCoordIntPair, Integer> chunks = watched.get(e.player.getUniqueID());
        if (chunks != null) {
            chunks.remove(e.chunk);
        }
        send(e.player, e.chunk, 0, false);
    }

    @SubscribeEvent
    public void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        watched.remove(e.player.getUniqueID());
    }

    @SubscribeEvent
    public void dimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        watched.remove(e.player.getUniqueID());
    }

    public void tick(WorldRuntime runtime) {
        if (runtime.world.getTotalWorldTime() % 20 != 0) {
            return;
        }
        Set<Long> requested = new HashSet<>();
        for (Object item : runtime.world.playerEntities) {
            EntityPlayerMP player = (EntityPlayerMP) item;
            Map<ChunkCoordIntPair, Integer> chunks = watched.get(player.getUniqueID());
            if (chunks != null) {
                for (ChunkCoordIntPair chunk : chunks.keySet()) {
                    requested.add(SpatialQueries.chunkKey(chunk.chunkXPos, chunk.chunkZPos));
                }
            }
        }
        SpatialQueries.ChunkMassBatch batch = SpatialQueries.chunkMasses(runtime.state, requested);
        runtime.networkBatches++;
        runtime.networkUniqueChunks = requested.size();
        runtime.networkFullScans = requested.isEmpty() ? 0 : 1;
        runtime.networkCubeVisits = batch.cubeVisits;
        runtime.networkPackets = 0;
        for (Object item : runtime.world.playerEntities) {
            EntityPlayerMP player = (EntityPlayerMP) item;
            Map<ChunkCoordIntPair, Integer> chunks = watched.get(player.getUniqueID());
            if (chunks != null) {
                for (Map.Entry<ChunkCoordIntPair, Integer> entry : chunks.entrySet()) {
                    ChunkCoordIntPair chunk = entry.getKey();
                    int amount = SmogGt5uBridge
                        .clamp(batch.masses.get(SpatialQueries.chunkKey(chunk.chunkXPos, chunk.chunkZPos)));
                    if (amount != entry.getValue()) {
                        send(player, chunk, amount, false);
                        runtime.networkPackets++;
                        entry.setValue(amount);
                    }
                }
            }
            int amount = SmogGt5uBridge.local(
                runtime.world,
                (int) Math.floor(player.posX),
                (int) Math.floor(player.posY + player.getEyeHeight()),
                (int) Math.floor(player.posZ));
            send(player, new ChunkCoordIntPair(0, 0), amount, true);
            runtime.networkPackets++;
        }
    }

    public void clear() {
        watched.clear();
    }
}

package org.smogmod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.client.Minecraft;
import net.minecraft.world.ChunkCoordIntPair;

import org.smogmod.network.PollutionMessage;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import gregtech.GTMod;

public final class ClientProxy extends CommonProxy {

    private final ConcurrentLinkedQueue<PollutionMessage> inbox = new ConcurrentLinkedQueue<>();

    private final Map<String, Integer> values = new ConcurrentHashMap<>();

    private final Map<Integer, Integer> localValues = new ConcurrentHashMap<>();

    private Object activeWorld;

    private volatile int local;

    @Override
    public void init() {
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    @Override
    public void receive(PollutionMessage message) {
        inbox.add(message);
    }

    private String key(int d, int x, int z) {
        return d + ":" + x + ":" + z;
    }

    @Override
    public int readClient(int d, int x, int z) {
        Integer value = values.get(key(d, x, z));
        return value == null ? 0 : value;
    }

    @Override
    public int localClient() {
        return local;
    }

    @SubscribeEvent
    public void disconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent e) {
        inbox.clear();
        values.clear();
        localValues.clear();
        activeWorld = null;
        local = 0;
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        PollutionMessage m;
        while ((m = inbox.poll()) != null) {
            if (m.local) {
                localValues.put(m.dimension, m.amount);
            } else {
                values.put(key(m.dimension, m.x, m.z), m.amount);
                if (mc.theWorld != null && mc.theWorld.provider.dimensionId == m.dimension) {
                    GTMod.clientProxy().mPollutionRenderer.processPacket(new ChunkCoordIntPair(m.x, m.z), m.amount);
                }
            }
        }
        if (mc.theWorld == null) {
            activeWorld = null;
            local = 0;
            return;
        }
        int dimension = mc.theWorld.provider.dimensionId;
        if (activeWorld != mc.theWorld) {
            activeWorld = mc.theWorld;
            for (Map.Entry<String, Integer> entry : values.entrySet()) {
                String[] k = entry.getKey()
                    .split(":");
                if (Integer.parseInt(k[0]) == dimension) {
                    GTMod.clientProxy().mPollutionRenderer.processPacket(
                        new ChunkCoordIntPair(Integer.parseInt(k[1]), Integer.parseInt(k[2])),
                        entry.getValue());
                }
            }
        }
        Integer value = localValues.get(dimension);
        local = value == null ? 0 : value;
    }
}

package org.smogmod.world;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

public final class Worlds {

    private static final Map<World, WorldRuntime> WORLDS = new IdentityHashMap<>();

    private static Thread owner;

    private Worlds() {}

    public static void start() {
        if (!WORLDS.isEmpty()) {
            throw new IllegalStateException("SMOG dimensions from the previous server were not closed");
        }
        owner = Thread.currentThread();
    }

    public static WorldRuntime get(World world) {
        if (world.isRemote) {
            throw new IllegalArgumentException("SMOG graph is server only");
        }
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("SMOG mutation outside server thread");
        }
        WorldRuntime runtime = WORLDS.get(world);
        if (runtime == null) {
            try {
                runtime = new WorldRuntime((WorldServer) world);
                WORLDS.put(world, runtime);
            } catch (Exception exception) {
                throw new IllegalStateException("Cannot load SMOG dimension", exception);
            }
        }
        return runtime;
    }

    public static WorldRuntime existing(World world) {
        return WORLDS.get(world);
    }

    public static void unload(World world) {
        WorldRuntime runtime = WORLDS.get(world);
        if (runtime != null) {
            runtime.close();
            WORLDS.remove(world);
        }
    }

    public static void stop() {
        RuntimeException failure = null;
        java.util.Iterator<Map.Entry<World, WorldRuntime>> worlds = WORLDS.entrySet()
            .iterator();
        while (worlds.hasNext()) {
            WorldRuntime runtime = worlds.next()
                .getValue();
            try {
                runtime.close();
                worlds.remove();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        owner = null;
    }
}

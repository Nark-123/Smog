package org.smogmod;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;

import org.smogmod.commands.SmogCommand;
import org.smogmod.network.SmogNetwork;
import org.smogmod.world.WorldRuntime;
import org.smogmod.world.Worlds;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.versioning.ComparableVersion;
import gregtech.GTMod;

@Mod(
    modid = "smog",
    name = "SMOG",
    version = Tags.VERSION,
    dependencies = "required-after:gregtech;required-after:unimixins@[0.1.23,);required-after:gtnhlib")
public final class SmogMod {

    @SidedProxy(clientSide = "org.smogmod.ClientProxy", serverSide = "org.smogmod.CommonProxy")
    public static CommonProxy proxy;

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("smog.enabled", "true"));
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        if (!enabled()) {
            return;
        }
        String version = Loader.instance()
            .getIndexedModList()
            .get("gregtech_nh")
            .getVersion();
        if (new ComparableVersion(version).compareTo(new ComparableVersion("5.09.51.482")) < 0) {
            throw new IllegalStateException("SMOG requires GT5U >= 5.09.51.482, found " + version);
        }
        if (Loader.isModLoaded("cubicchunks") || Loader.isModLoaded("CubicChunks")) {
            throw new IllegalStateException("SMOG Cubic Chunks game adapter is not implemented");
        }
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (enabled()) {
            MinecraftForge.EVENT_BUS.register(this);
            FMLCommonHandler.instance()
                .bus()
                .register(this);
            SmogNetwork.INSTANCE.init();
            MinecraftForge.EVENT_BUS.register(SmogNetwork.INSTANCE);
            FMLCommonHandler.instance()
                .bus()
                .register(SmogNetwork.INSTANCE);
            proxy.init();
        }
    }

    @Mod.EventHandler
    public void serverAboutToStart(FMLServerAboutToStartEvent event) {
        if (enabled()) {
            Worlds.start();
        }
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        if (!enabled()) {
            return;
        }
        if (!GTMod.proxy.mPollution) {
            throw new IllegalStateException("SMOG needs GT machine-side pollution enabled");
        }
        event.registerServerCommand(new SmogCommand());
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        if (enabled()) {
            Worlds.stop();
            SmogNetwork.INSTANCE.clear();
        }
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        if (enabled()) {
            org.smogmod.diagnostics.GameMethodSmoke.start();
        }
    }

    @SubscribeEvent
    public void worldLoad(WorldEvent.Load event) {
        if (!event.world.isRemote) {
            Worlds.get(event.world);
        }
    }

    @SubscribeEvent
    public void worldSave(WorldEvent.Save event) {
        WorldRuntime runtime = Worlds.existing(event.world);
        if (runtime != null) {
            runtime.save();
        }
    }

    @SubscribeEvent
    public void worldUnload(WorldEvent.Unload event) {
        if (!event.world.isRemote) {
            Worlds.unload(event.world);
        }
    }

    @SubscribeEvent
    public void chunkLoad(ChunkEvent.Load event) {
        if (!event.world.isRemote) {
            Worlds.get(event.world)
                .load(event.getChunk());
        }
    }

    @SubscribeEvent
    public void chunkUnload(ChunkEvent.Unload event) {
        WorldRuntime runtime = Worlds.existing(event.world);
        if (runtime != null) {
            runtime.unload(event.getChunk());
        }
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) Worlds.beginServerTick();
        else Worlds.endServerTick();
    }

    @SubscribeEvent
    public void worldTick(TickEvent.WorldTickEvent event) {
        if (!event.world.isRemote && event.phase == TickEvent.Phase.END) {
            WorldRuntime runtime = Worlds.get(event.world);
            runtime.tick();
            SmogNetwork.INSTANCE.tick(runtime);
            org.smogmod.compat.gt5u.LocalEffects.tick(runtime);
            org.smogmod.diagnostics.GameMethodSmoke.tick(runtime);
        }
    }
}

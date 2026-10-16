package org.smogmod.diagnostics;

import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.world.Explosion;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;

import org.smogmod.compat.gt5u.PendingPulse;
import org.smogmod.world.WorldRuntime;
import org.smogmod.world.Worlds;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.ITexture;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchMuffler;
import gregtech.common.pollution.Pollution;
import gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace;
import gtPlusPlus.core.item.general.ItemAirFilter;
import gtPlusPlus.xmod.gregtech.api.metatileentity.implementations.MTEHatchMufflerAdvanced;

/**
 * Calls actual transformed GT methods in an isolated world. This is not an assembled EBF recipe test.
 */
public final class GameMethodSmoke {

    private static WorldServer testWorld;

    private static int ticks;

    private GameMethodSmoke() {}

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError("SMOG fixture: " + message);
        }
    }

    private static BaseMetaTileEntity attach(WorldServer world, MetaTileEntity meta, int x, int y, int z,
        ForgeDirection facing) {
        world.setBlock(x, y, z, GregTechAPI.sBlockMachines, 0, 3);
        BaseMetaTileEntity base = new BaseMetaTileEntity();
        world.setTileEntity(x, y, z, base);
        base.setOwnerName("SMOG_fixture");
        base.setOwnerUuid(
            java.util.UUID.nameUUIDFromBytes("SMOG_fixture".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        meta.setBaseMetaTileEntity(base);
        base.setFrontFacing(facing);
        return base;
    }

    private static void event(WorldRuntime runtime, int previous, int x, int y, int z, long amount) {
        check(runtime.pending.size() == previous + 1, "expected exactly one impulse");
        PendingPulse pulse = runtime.pending.get(previous);
        check(
            pulse.position.x == x && pulse.position.y == y
                && pulse.position.z == z
                && pulse.amount == amount
                && !pulse.chunkOnly,
            "wrong outlet or post-filter amount");
        System.out.println("SMOG_FIXTURE_EVENT " + x + "," + y + "," + z + " amount=" + amount);
    }

    public static void start() {
        if (!Boolean.getBoolean("smog.gameSmoke")) {
            return;
        }
        testWorld = MinecraftServer.getServer()
            .worldServerForDimension(0);
        check(
            "SMOG_INTEGRATION_SMOKE".equals(
                testWorld.getWorldInfo()
                    .getWorldName()),
            "isolated fixture world required");
        WorldRuntime runtime = Worlds.get(testWorld);
        check(runtime.state.generatedMass() == 0 && runtime.pending.isEmpty(), "fixture must start fresh");
        MTEElectricBlastFurnace ebf = new MTEElectricBlastFurnace("smog.fixture.ebf");
        MTEHatchMuffler first = new MTEHatchMuffler("smog.fixture.muffler", 2, new String[0], new ITexture[0][][]);
        MTEHatchMuffler second = new MTEHatchMuffler("smog.fixture.second", 2, new String[0], new ITexture[0][][]);
        attach(testWorld, ebf, 0, 100, 0, ForgeDirection.NORTH);
        attach(testWorld, first, 2, 100, 0, ForgeDirection.EAST);
        attach(testWorld, second, 6, 100, 0, ForgeDirection.UP);
        ebf.mMufflerHatches.add(first);
        int before = runtime.pending.size();
        check(ebf.polluteEnvironment(10000), "EBF basic vent");
        event(runtime, before, 3, 100, 0, first.calculatePollutionReduction(10000));
        testWorld.setBlock(3, 100, 0, Blocks.stone);
        before = runtime.pending.size();
        check(!ebf.polluteEnvironment(10000), "blocked muffler must fail");
        check(runtime.pending.size() == before, "blocked muffler emitted");
        testWorld.setBlockToAir(3, 100, 0);
        check(ebf.polluteEnvironment(0), "buffer release after unblock");
        event(runtime, before, 3, 100, 0, first.calculatePollutionReduction(10000));
        before = runtime.pending.size();
        check(ebf.polluteEnvironment(0), "stopped EBF");
        check(runtime.pending.size() == before, "stopped EBF emitted");
        ebf.mMufflerHatches.add(second);
        before = runtime.pending.size();
        check(ebf.polluteEnvironment(20000), "two mufflers");
        check(runtime.pending.size() == before + 2, "two mufflers must emit twice");
        PendingPulse up = runtime.pending.get(before + 1);
        System.out.println(
            "SMOG_FIXTURE_TWO_MUFFLERS first=" + runtime.pending.get(before).amount
                + " second="
                + up.amount
                + " secondXYZ="
                + up.position);
        check(up.position.x == 6 && up.position.y == 101 && up.position.z == 0, "second axis outlet");
        MTEHatchMufflerAdvanced advanced = new MTEHatchMufflerAdvanced(
            "smog.fixture.advanced",
            2,
            new String[0],
            new ITexture[0][][]);
        attach(testWorld, advanced, 10, 100, 0, ForgeDirection.EAST);
        for (Object item : Item.itemRegistry) {
            if (item instanceof ItemAirFilter) {
                advanced.mInventory[0] = new ItemStack((Item) item, 1, 0);
                break;
            }
        }
        check(advanced.mInventory[0] != null, "air filter registration missing");
        long damage = ItemAirFilter.getFilterDamage(advanced.mInventory[0]);
        int filtered = advanced.calculatePollutionReduction(10000);
        before = runtime.pending.size();
        check(advanced.polluteEnvironment(ebf, 10000), "advanced vent");
        event(runtime, before, 11, 100, 0, filtered);
        check(ItemAirFilter.getFilterDamage(advanced.mInventory[0]) == damage + 1, "filter wear must occur once");
        TileEntityFurnace independent = new TileEntityFurnace();
        testWorld.setBlock(0, 102, 0, Blocks.furnace);
        testWorld.setTileEntity(0, 102, 0, independent);
        before = runtime.pending.size();
        Pollution.addPollution(independent, 123);
        event(runtime, before, 0, 102, 0, 123);
        before = runtime.pending.size();
        new Explosion(testWorld, null, -4.5, 105.5, -4.5, 1).doExplosionA();
        check(runtime.pending.size() == before + 1, "non-TE explosion route");
        PendingPulse explosion = runtime.pending.get(before);
        check(
            explosion.position.x == -5 && explosion.position.y == 105 && explosion.position.z == -5,
            "explosion XYZ lost");
        // Remove fixture machines before their ordinary tile tick; accepted pulses remain one-shot events.
        for (int x : new int[] { 0, 2, 6, 10 }) {
            testWorld.setBlockToAir(x, 100, 0);
        }
        testWorld.setBlockToAir(0, 102, 0);
        System.out.println("SMOG_GAME_METHOD_FIXTURE_PASS");
    }

    public static void tick(WorldRuntime runtime) {
        if (testWorld != runtime.world || ++ticks < 40) {
            return;
        }
        check(Math.abs(runtime.state.balanceError()) < 1e-6, "mass balance");
        runtime.save();
        System.out.println("SMOG_GAME_SHUTDOWN_READY pending=" + runtime.pending.size());
        testWorld = null;
        MinecraftServer.getServer()
            .initiateShutdown();
    }
}

package org.smogmod.compat.gt5u;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import org.smogmod.world.Worlds;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import smog.BlockPos;
import smog.SpatialQueries;

public final class SmogGt5uBridge {

    private SmogGt5uBridge() {}

    public static void emitPulse(World world, int x, int y, int z, long amount) {
        accept(world, x, y, z, amount, false, false);
    }

    public static void removeAt(World world, int x, int y, int z, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Negative removal");
        }
        emitPulse(world, x, y, z, -amount);
    }

    public static void muffler(IGregTechTileEntity hatch, int filteredAmount) {
        ForgeDirection side = hatch.getFrontFacing();
        emitPulse(
            hatch.getWorld(),
            hatch.getXCoord() + side.offsetX,
            hatch.getYCoord() + side.offsetY,
            hatch.getZCoord() + side.offsetZ,
            filteredAmount);
    }

    public static void tile(TileEntity tile, int amount) {
        accept(tile.getWorldObj(), tile.xCoord, tile.yCoord, tile.zCoord, amount, false, true);
    }

    public static void column(World world, int chunkX, int chunkZ, int amount) {
        accept(world, chunkX * 16, 0, chunkZ * 16, amount, true, false);
    }

    private static void accept(World world, int x, int y, int z, long amount, boolean column, boolean adjacent) {
        if (world.isRemote || amount == 0) {
            return;
        }
        Worlds.get(world)
            .accept(new PendingPulse(new BlockPos(world.provider.dimensionId, x, y, z), amount, column, adjacent));
    }

    public static int read(World world, int chunkX, int chunkZ) {
        if (world.isRemote) {
            return org.smogmod.SmogMod.proxy.readClient(world.provider.dimensionId, chunkX, chunkZ);
        }
        return clamp(SpatialQueries.chunkMass(Worlds.get(world).state, chunkX, chunkZ));
    }

    public static int local(World world, int x, int y, int z) {
        return clamp(Worlds.get(world).state.sample(new BlockPos(world.provider.dimensionId, x, y, z)) * 512);
    }

    private static int clamp(double amount) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, amount));
    }
}

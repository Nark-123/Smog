package org.smogmod.mixin.late;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.ChunkDataEvent;

import org.smogmod.compat.gt5u.EmissionContext;
import org.smogmod.compat.gt5u.SmogGt5uBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.common.pollution.Pollution;

@Mixin(value = Pollution.class, remap = false)
public abstract class MixinPollution {

    @Inject(
        method = "addPollution(Lnet/minecraft/tileentity/TileEntity;I)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1)
    private static void smog$tile(TileEntity tile, int amount, CallbackInfo info) {
        SmogGt5uBridge.tile(tile, amount);
        info.cancel();
    }

    @Inject(method = "addPollution(Lnet/minecraft/world/World;III)V", at = @At("HEAD"), cancellable = true, require = 1)
    private static void smog$column(World world, int x, int z, int amount, CallbackInfo info) {
        EmissionContext context = EmissionContext.current();
        if (context != null && context.world == world) {
            SmogGt5uBridge.emitPulse(world, context.x, context.y, context.z, amount);
        } else {
            SmogGt5uBridge.column(world, x, z, amount);
        }
        info.cancel();
    }

    @Inject(method = "getPollution(Lnet/minecraft/world/World;II)I", at = @At("HEAD"), cancellable = true, require = 1)
    private static void smog$read(World world, int x, int z, CallbackInfoReturnable<Integer> info) {
        if (!world.isRemote) {
            info.setReturnValue(SmogGt5uBridge.read(world, x, z));
        } else {
            info.setReturnValue(org.smogmod.SmogMod.proxy.readClient(world.provider.dimensionId, x, z));
        }
    }

    @Inject(
        method = "hasPollution(Lnet/minecraft/world/chunk/Chunk;)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 1)
    private static void smog$has(Chunk chunk, CallbackInfoReturnable<Boolean> info) {
        info.setReturnValue(SmogGt5uBridge.read(chunk.worldObj, chunk.xPosition, chunk.zPosition) > 0);
    }

    @Inject(method = "onWorldTick", at = @At("HEAD"), cancellable = true, require = 1)
    private static void smog$stopOldSimulation(TickEvent.WorldTickEvent event, CallbackInfo info) {
        info.cancel();
    }

    @Inject(method = "migrate", at = @At("HEAD"), cancellable = true, require = 1)
    private static void smog$ignoreLegacy(ChunkDataEvent.Load event, CallbackInfo info) {
        info.cancel();
    }
}

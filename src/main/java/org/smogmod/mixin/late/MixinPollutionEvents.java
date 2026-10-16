package org.smogmod.mixin.late;

import net.minecraftforge.event.world.ChunkWatchEvent;
import net.minecraftforge.event.world.WorldEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "gregtech.common.pollution.Pollution$GT_PollutionEventHandler", remap = false)
public abstract class MixinPollutionEvents {

    @Inject(method = "chunkWatch", at = @At("HEAD"), cancellable = true, require = 1)
    private void smog$stopOldPacket(ChunkWatchEvent.Watch event, CallbackInfo info) {
        info.cancel();
    }

    @Inject(method = "onWorldLoad", at = @At("HEAD"), cancellable = true, require = 1)
    private void smog$stopOldLoad(WorldEvent.Load event, CallbackInfo info) {
        info.cancel();
    }
}

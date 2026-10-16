package org.smogmod.mixin.late;

import org.smogmod.SmogMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

@Mixin(targets = "gregtech.common.pollution.PollutionRenderer", remap = false)
public abstract class MixinRenderer {

    @ModifyExpressionValue(
        method = "onClientTick",
        at = @At(value = "INVOKE", target = "Lgregtech/common/pollution/GTClientPollutionMap;getPollution(DD)I"),
        require = 1)
    private int smog$local(int original) {
        return SmogMod.proxy.localClient();
    }
}

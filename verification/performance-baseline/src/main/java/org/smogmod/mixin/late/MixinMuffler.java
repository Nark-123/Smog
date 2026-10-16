package org.smogmod.mixin.late;

import org.smogmod.compat.gt5u.SmogGt5uBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/**
 * Receives the amount after the original filter, only after the original exit checks succeed.
 */
@Mixin(
    targets = { "gregtech.api.metatileentity.implementations.MTEHatchMuffler",
        "gtPlusPlus.xmod.gregtech.api.metatileentity.implementations.MTEHatchMufflerAdvanced" },
    remap = false)
public abstract class MixinMuffler {

    @Redirect(
        method = "polluteEnvironment(Lgregtech/api/metatileentity/MetaTileEntity;I)Z",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/common/pollution/Pollution;addPollution(Lgregtech/api/interfaces/tileentity/IGregTechTileEntity;I)V",
            ordinal = 0),
        require = 1,
        allow = 1)
    private void smog$emitOutlet(IGregTechTileEntity hatch, int filteredAmount) {
        SmogGt5uBridge.muffler(hatch, filteredAmount);
    }
}

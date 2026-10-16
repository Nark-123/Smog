package org.smogmod.mixin.early;

import net.minecraft.world.Explosion;
import net.minecraft.world.World;

import org.smogmod.compat.gt5u.EmissionContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

@Mixin(Explosion.class)
public abstract class MixinExplosionSource {

    @Shadow
    private World worldObj;

    @Shadow
    public double explosionX;

    @Shadow
    public double explosionY;

    @Shadow
    public double explosionZ;

    @WrapMethod(method = "doExplosionA", require = 1)
    private void smog$position(Operation<Void> original) {
        try (EmissionContext ignored = new EmissionContext(
            worldObj,
            (int) Math.floor(explosionX),
            (int) Math.floor(explosionY),
            (int) Math.floor(explosionZ))) {
            original.call();
        }
    }
}

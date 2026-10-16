package org.smogmod.mixin.early;

import net.minecraft.block.Block;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import org.smogmod.world.WorldRuntime;
import org.smogmod.world.Worlds;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Chunk.class)
public abstract class MixinChunkChanges {

    @Shadow
    public World worldObj;

    @Shadow
    public int xPosition;

    @Shadow
    public int zPosition;

    @Inject(method = "func_150807_a", at = @At("RETURN"), require = 1)
    private void smog$block(int x, int y, int z, Block block, int metadata, CallbackInfoReturnable<Boolean> info) {
        smog$changed(x, y, z, info.getReturnValue());
    }

    @Inject(method = "setBlockMetadata", at = @At("RETURN"), require = 1)
    private void smog$metadata(int x, int y, int z, int metadata, CallbackInfoReturnable<Boolean> info) {
        smog$changed(x, y, z, info.getReturnValue());
    }

    private void smog$changed(int x, int y, int z, boolean changed) {
        if (!changed || worldObj.isRemote) {
            return;
        }
        WorldRuntime runtime = Worlds.existing(worldObj);
        if (runtime != null) {
            runtime.changed(xPosition * 16 + x, y, zPosition * 16 + z);
        }
    }
}

package org.smogmod.compat.gt5u;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

import org.smogmod.world.WorldRuntime;

import gregtech.GTMod;
import gregtech.api.hazards.HazardProtection;

/**
 * First integration: GT potion thresholds use head-local concentration times reference volume 512.
 */
public final class LocalEffects {

    private LocalEffects() {}

    public static void tick(WorldRuntime runtime) {
        if (runtime.world.getTotalWorldTime() % 1200 != 0) {
            return;
        }
        for (Object object : runtime.world.loadedEntityList) {
            if (!(object instanceof EntityLivingBase)) {
                continue;
            }
            EntityLivingBase entity = (EntityLivingBase) object;
            if (entity instanceof EntityPlayer && ((EntityPlayer) entity).capabilities.isCreativeMode) {
                continue;
            }
            if (HazardProtection.isWearingFullGasHazmat(entity)) {
                continue;
            }
            int pollution = SmogGt5uBridge.local(
                runtime.world,
                (int) Math.floor(entity.posX),
                (int) Math.floor(entity.posY + entity.getEyeHeight()),
                (int) Math.floor(entity.posZ));
            if (pollution <= 400000 || pollution <= GTMod.proxy.mPollutionSmogLimit) {
                continue;
            }
            int choice = runtime.world.rand.nextInt(3);
            if (choice == 1) {
                entity.addPotionEffect(
                    new PotionEffect(Potion.weakness.id, Math.min(pollution / 1000, 1000), pollution / 400000));
            }
            if (choice == 1 || choice == 2) {
                entity.addPotionEffect(
                    new PotionEffect(Potion.moveSlowdown.id, Math.min(pollution / 1000, 1000), pollution / 400000));
            }
            entity.addPotionEffect(
                new PotionEffect(Potion.digSlowdown.id, Math.min(pollution / 1000, 1000), pollution / 400000));
            if (pollution <= GTMod.proxy.mPollutionPoisonLimit) {
                continue;
            }
            choice = runtime.world.rand.nextInt(4);
            if (choice == 1) {
                entity.addPotionEffect(new PotionEffect(Potion.confusion.id, Math.min(pollution / 2000, 1000), 1));
            }
            if (choice == 1 || choice == 2) {
                entity.addPotionEffect(
                    new PotionEffect(Potion.poison.id, Math.min(pollution / 4000, 1000), pollution / 500000));
            }
            if (choice != 0) {
                entity.addPotionEffect(new PotionEffect(Potion.blindness.id, Math.min(pollution / 2000, 1000), 1));
            }
            entity.addPotionEffect(new PotionEffect(Potion.hunger.id, pollution / 500000));
        }
    }
}

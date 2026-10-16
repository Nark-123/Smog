package org.smogmod.mixin;

import com.gtnewhorizon.gtnhmixins.builders.IMixins;
import com.gtnewhorizon.gtnhmixins.builders.MixinBuilder;

public enum SmogMixins implements IMixins {

    WORLD(new MixinBuilder("SMOG loaded-world changes and exact explosion context").setPhase(Phase.EARLY)
        .addCommonMixins("MixinChunkChanges", "MixinExplosionSource")
        .setApplyIf(() -> !"false".equalsIgnoreCase(System.getProperty("smog.enabled", "true")))),
    GT(new MixinBuilder("SMOG pollution sink").setPhase(Phase.LATE)
        .addCommonMixins("MixinMuffler", "MixinPollution", "MixinPollutionEvents")
        .addClientMixins("MixinRenderer")
        .setApplyIf(() -> !"false".equalsIgnoreCase(System.getProperty("smog.enabled", "true"))));

    private final MixinBuilder builder;

    SmogMixins(MixinBuilder builder) {
        this.builder = builder;
    }

    @Override
    public MixinBuilder getBuilder() {
        return builder;
    }
}

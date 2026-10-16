package org.smogmod.mixin;

import java.util.List;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;
import com.gtnewhorizon.gtnhmixins.builders.IMixins;

@LateMixin
public final class LateLoader implements ILateMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins.smog.late.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        return IMixins.getLateMixins(SmogMixins.class, loadedMods);
    }
}

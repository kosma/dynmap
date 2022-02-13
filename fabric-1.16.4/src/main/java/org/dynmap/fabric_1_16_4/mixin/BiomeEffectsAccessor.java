package org.dynmap.fabric_1_16_4.mixin;

import net.minecraft.world.level.biome.BiomeSpecialEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BiomeSpecialEffects.class)
public interface BiomeEffectsAccessor {
    @Accessor
    int getWaterColor();
}

package com.killer560.hub.roomsim.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The synched health key (private on {@link LivingEntity}, 26.3 and 26.2 alike, javap): the sim's P5 dragons send
 * Hypixel's 1,000,000,000 health, which {@code setHealth} would clamp to the vanilla max of 200. Only
 * {@code BossFight.SimDragon} uses it.
 */
@Mixin(LivingEntity.class)
public interface SimLivingHealthAccessor {

    @Accessor("DATA_HEALTH_ID")
    static EntityDataAccessor<Float> killer560smod$healthId() {
        throw new AssertionError();
    }
}

package com.killer560.hub.objecthider.mixin;

import com.killer560.hub.objecthider.ObjectHiderConfig;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Object Hider's Arrow Particles (arrowpart-1117): an arrow spawns its particles on the client with
 * {@code Level.addParticle} (javap 26.3): the CRIT trail of a critical arrow in {@code AbstractArrow.tick} and the
 * water BUBBLE trail in {@code addBubbleParticles}. A packet hook never sees them, so each call is wrapped and skipped.
 */
@Mixin(AbstractArrow.class)
public abstract class ObjectHiderArrowMixin {

    @WrapOperation(method = {"tick", "addBubbleParticles"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
    private void killer560smod$objectHider$arrowParticle(Level level, ParticleOptions particle, double x, double y, double z,
                                                        double dx, double dy, double dz, Operation<Void> original) {
        if (!ObjectHiderConfig.getInstance().isHideArrowParticles()) original.call(level, particle, x, y, z, dx, dy, dz);
    }
}

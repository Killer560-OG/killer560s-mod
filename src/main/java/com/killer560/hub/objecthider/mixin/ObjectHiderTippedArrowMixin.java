package com.killer560.hub.objecthider.mixin;

import com.killer560.hub.objecthider.ObjectHiderConfig;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Arrow Particles, the tipped arrow's effect swirl ({@code makeParticle} in flight and in the ground, {@code
 *  handleEntityEvent} on a hit; ENTITY_EFFECT, javap 26.3). See {@link ObjectHiderArrowMixin}. */
@Mixin(Arrow.class)
public abstract class ObjectHiderTippedArrowMixin {

    @WrapOperation(method = {"makeParticle", "handleEntityEvent"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
    private void killer560smod$objectHider$tippedParticle(Level level, ParticleOptions particle, double x, double y, double z,
                                                         double dx, double dy, double dz, Operation<Void> original) {
        if (!ObjectHiderConfig.getInstance().isHideArrowParticles()) original.call(level, particle, x, y, z, dx, dy, dz);
    }
}

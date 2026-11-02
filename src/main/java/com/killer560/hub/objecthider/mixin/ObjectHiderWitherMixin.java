package com.killer560.hub.objecthider.mixin;

import com.killer560.hub.objecthider.ObjectHiderFeature;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Object Hider's Witherborn Wither (killer560, 2026-11-01): the hidden wither's own particles. {@code WitherBoss.aiStep}
 * spawns them on the client with {@code Level.addParticle(ParticleOptions, DDDDDD)} - smoke from its heads and the swirls
 * of its spawning shield (javap 26.3 and 26.2: three calls, the only ones in the method). A packet hook never sees them,
 * so each call is wrapped here and skipped for a wither {@link ObjectHiderFeature#hidesWitherParticles} names.
 */
@Mixin(WitherBoss.class)
public abstract class ObjectHiderWitherMixin {

    @Redirect(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"),
            require = 0)
    private void killer560smod$objectHider$witherParticle(Level level, ParticleOptions particle, double x, double y, double z,
                                                         double dx, double dy, double dz) {
        if (level.isClientSide() && ObjectHiderFeature.hidesWitherParticles((WitherBoss) (Object) this)) {
            return;
        }
        level.addParticle(particle, x, y, z, dx, dy, dz);
    }
}

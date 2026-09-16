package com.killer560.hub.objecthider.mixin;

import com.killer560.hub.objecthider.ObjectHiderFeature;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The pack's particle cancel point, same target Devonian's {@code ParticleEngineMixin} uses.
 *  javap-verified against the 26.1.2 merged jar: {@code ParticleEngine.add(Particle)} is the single funnel -
 *  {@code createParticle(...)} builds the particle via {@code makeParticle} and then calls {@code add} on it,
 *  and {@code ClientLevel.addParticle} goes through {@code createParticle} - so both server-sent
 *  ({@code ClientboundLevelParticlesPacket}) and client-generated particles (block cracks, wither-cloak smoke)
 *  pass through here. Dropping the particle before it is tracked is purely visual; nothing else observes it. */
@Mixin(ParticleEngine.class)
public abstract class ObjectHiderParticleMixin {

    @Inject(method = "add", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$objectHider$add(Particle particle, CallbackInfo ci) {
        if (ObjectHiderFeature.shouldHideParticle(particle)) {
            ci.cancel();
        }
    }
}

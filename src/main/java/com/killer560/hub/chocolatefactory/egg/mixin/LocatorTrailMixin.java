package com.killer560.hub.chocolatefactory.egg.mixin;

import com.killer560.hub.chocolatefactory.egg.LocatorTrail;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads the Egglocator's particle trail from the level-particles packet, the same packet SkyHanni reads. Checked with javap on
 * 26.1.2 and 26.2: {@code ClientPacketListener.handleParticleEvent(ClientboundLevelParticlesPacket)V} and the packet getters
 * {@code getParticle, getMaxSpeed, getCount, getX, getY, getZ} exist with the same descriptors on both.
 */
@Mixin(ClientPacketListener.class)
public abstract class LocatorTrailMixin {

    @Inject(method = "handleParticleEvent", at = @At("HEAD"), require = 0)
    private void killer560smod$locatorTrail(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        LocatorTrail.onParticlePacket(packet, System.currentTimeMillis());
    }
}

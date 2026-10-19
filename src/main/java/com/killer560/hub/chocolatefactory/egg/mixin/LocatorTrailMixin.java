package com.killer560.hub.chocolatefactory.egg.mixin;

import com.killer560.hub.chocolatefactory.egg.LocatorTrail;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads the Egglocator's particle trail from the level-particles packet, at the point SkyHanni's MixinClientPacketListener
 * reads it: just after {@code PacketUtils.ensureRunningOnSameThread}. At HEAD (as this was until 2026-10-19) the handler runs
 * twice per packet - first on the Netty thread, where ensureRunningOnSameThread re-queues the packet and throws, then again
 * on the render thread - so every trail point arrived twice, half of them on a thread racing the render thread's list.
 * <p>
 * Checked with javap on 26.1.2 and 26.2: {@code handleParticleEvent(ClientboundLevelParticlesPacket)V} calls
 * {@code PacketUtils.ensureRunningOnSameThread(Packet, PacketListener, PacketProcessor)V} once, at offset 9, on both; the
 * packet getters {@code getParticle, getMaxSpeed, getCount, getX, getY, getZ} have the same descriptors on both.
 */
@Mixin(ClientPacketListener.class)
public abstract class LocatorTrailMixin {

    @Inject(method = "handleParticleEvent", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
            shift = At.Shift.AFTER), require = 0)
    private void killer560smod$locatorTrail(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        LocatorTrail.onParticlePacket(packet, System.currentTimeMillis());
    }
}

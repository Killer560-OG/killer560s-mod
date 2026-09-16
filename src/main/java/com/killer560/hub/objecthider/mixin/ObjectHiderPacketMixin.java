package com.killer560.hub.objecthider.mixin;

import com.killer560.hub.objecthider.ObjectHiderFeature;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two packet-level cancels the render layer can't express:
 * <ul>
 * <li>{@code handleParticleEvent} - the orb dust / wither-shield hearts / Wither King witch clouds are only
 *     identifiable by the packet's own flags and origin ({@code count}, {@code overrideLimiter},
 *     {@code alwaysShow}, x/y/z); by the time a {@code Particle} object exists that information is gone.</li>
 * <li>{@code handleUpdateMobEffect} - "there is no blindness" has no render hook; Devonian
 *     {@code misc/hiders/DisableBlindness.kt} drops the packet, and so does this.</li>
 * </ul>
 * Both hooks sit right AFTER {@code PacketUtils.ensureRunningOnSameThread(packet, this,
 * minecraft.packetProcessor())} - javap-verified as instruction 9 of both handlers in the 26.1.2 jar - for the
 * same reason as {@code BloodCampPacketMixin}/{@code DungeonAlertsPacketMixin}: a HEAD hook runs twice (once on
 * the network thread, which then throws {@code RunningOnDifferentThreadException} and reschedules). Injecting
 * after that call means the hook only ever runs on the main thread, and cancelling there returns before vanilla
 * applies the packet. {@code require = 0} so a future mapping change degrades to "hider off", never a crash.
 */
@Mixin(ClientPacketListener.class)
public abstract class ObjectHiderPacketMixin {

    private static final String ENSURE_SAME_THREAD =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleParticleEvent",
            at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER),
            cancellable = true, require = 0)
    private void killer560smod$objectHider$onParticlePacket(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        if (ObjectHiderFeature.shouldCancelParticlePacket(packet)) {
            ci.cancel();
        }
    }

    @Inject(method = "handleUpdateMobEffect",
            at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER),
            cancellable = true, require = 0)
    private void killer560smod$objectHider$onMobEffect(ClientboundUpdateMobEffectPacket packet, CallbackInfo ci) {
        if (ObjectHiderFeature.shouldCancelMobEffect(packet)) {
            ci.cancel();
        }
    }
}

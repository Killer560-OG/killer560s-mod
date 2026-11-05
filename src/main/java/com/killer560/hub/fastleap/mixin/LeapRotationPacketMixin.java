package com.killer560.hub.fastleap.mixin;

import com.killer560.hub.fastleap.LeapRotation;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link LeapRotation} around vanilla's handling of a server teleport: right after
 * {@code PacketUtils.ensureRunningOnSameThread} in {@code handleMovePlayer} (client thread, before vanilla applies it; the
 * point SmoothTpPositionMixin uses, 26.2 and 26.3) and at its RETURN (applied, accept sent). A throw is swallowed: an
 * exception out of a packet handler disconnects the client.
 */
@Mixin(ClientPacketListener.class)
public abstract class LeapRotationPacketMixin {

    @Unique
    private static final String ENSURE_SAME_THREAD =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
    private void killer560smod$leapRotBefore(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        try {
            LeapRotation.beforeTeleport(packet);
        } catch (RuntimeException e) {
            killer560smod$leapRot$threw(e);
        }
    }

    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void killer560smod$leapRotAfter(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        try {
            LeapRotation.afterTeleport(packet);
        } catch (RuntimeException e) {
            killer560smod$leapRot$threw(e);
        }
    }

    @Unique
    private static void killer560smod$leapRot$threw(RuntimeException e) {
        ModLog.get("killer560smod-leaprot").error("[LeapRotation] teleport hook threw", e);
    }
}

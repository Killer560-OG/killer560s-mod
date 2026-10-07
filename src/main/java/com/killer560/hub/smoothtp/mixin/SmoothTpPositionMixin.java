package com.killer560.hub.smoothtp.mixin;

import com.killer560.hub.smoothtp.SmoothTeleport;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Read-only look at every server position packet, for Smooth Teleport's glide-or-snap decision.
 *
 * <p>The same injection point as {@code ap3/mixin/Ap3PositionPacketMixin}: right after
 * {@code PacketUtils.ensureRunningOnSameThread} in {@code handleMovePlayer(ClientboundPlayerPositionPacket)}, which is
 * the first call on both 26.1.2 and 26.2 (javap, 2026-10-07) - client thread, before vanilla applies the packet, so the
 * player's position here is still the one being replaced. The target is computed the way vanilla does
 * ({@code PositionMoveRotation.calculateAbsolute}). Nothing is changed or cancelled, and a throw is swallowed: an
 * exception out of a packet handler disconnects the client.
 */
@Mixin(ClientPacketListener.class)
public abstract class SmoothTpPositionMixin {

    @Unique
    private static final String ENSURE_SAME_THREAD =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
    private void killer560smod$smoothTpPosition(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        try {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || packet == null || packet.change() == null) {
                return;
            }
            PositionMoveRotation target = PositionMoveRotation.calculateAbsolute(
                    PositionMoveRotation.of(player), packet.change(), packet.relatives());
            SmoothTeleport.onServerPosition(player.position(), target.position());
        } catch (RuntimeException e) {
            killer560smod$smoothTp$threw(e);
        }
    }

    @Unique
    private static void killer560smod$smoothTp$threw(RuntimeException e) {
        ModLog.get("killer560smod-smoothtp").error("[SmoothTeleport] position packet hook threw", e);
    }
}

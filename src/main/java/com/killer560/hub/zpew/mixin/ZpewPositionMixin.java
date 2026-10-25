package com.killer560.hub.zpew.mixin;

import com.killer560.hub.util.ModLog;
import com.killer560.hub.zpew.ZeroPingEtherwarp;
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
 * Every server position packet, for Zero Ping Etherwarp's match against its predictions: right after
 * {@code PacketUtils.ensureRunningOnSameThread} in {@code handleMovePlayer} (the point {@code SmoothTpPositionMixin} uses,
 * 26.1.2 and 26.2), before vanilla applies it. The target is computed as vanilla does. Read-only; a throw is swallowed.
 */
@Mixin(ClientPacketListener.class)
public abstract class ZpewPositionMixin {

    @Unique
    private static final String ENSURE_SAME_THREAD =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
    private void killer560smod$zpewPosition(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        try {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || packet == null || packet.change() == null) {
                return;
            }
            PositionMoveRotation target = PositionMoveRotation.calculateAbsolute(
                    PositionMoveRotation.of(player), packet.change(), packet.relatives());
            ZeroPingEtherwarp.onServerPosition(player.position(), target.position());
        } catch (Throwable t) {
            killer560smod$zpew$threw(t);
        }
    }

    @Unique
    private static void killer560smod$zpew$threw(Throwable t) {
        ModLog.get("killer560smod-zpew").error("[ZPEW] position hook threw", t);
    }
}

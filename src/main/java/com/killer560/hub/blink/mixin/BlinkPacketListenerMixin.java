package com.killer560.hub.blink.mixin;

import com.killer560.hub.blink.BlinkFeature;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The server packets that end or interrupt a Blink hold, each right after {@code PacketUtils.ensureRunningOnSameThread}
 * (render thread, before vanilla acts on the packet - so whatever vanilla sends in reply goes out after what was held):
 * {@code handleMovePlayer} (a teleport: release, honor it), {@code handleLogin} / {@code handleRespawn} (a new world: the
 * held moves are dropped), {@code handleConfigurationStart} (release everything). All four begin with that call on 26.1.2
 * and 26.2 (javap, 2026-10-25), the point {@code smoothtp/mixin/SmoothTpPositionMixin} uses. Nothing is changed or
 * canceled, and a throw is swallowed: an exception out of a packet handler disconnects the client.
 */
@Mixin(ClientPacketListener.class)
public abstract class BlinkPacketListenerMixin {

    @Unique
    private static final String ENSURE_SAME_THREAD =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
    private void killer560smod$blinkPosition(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        try {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || packet == null || packet.change() == null) {
                BlinkFeature.onServerPosition(null, null);
                return;
            }
            PositionMoveRotation target = PositionMoveRotation.calculateAbsolute(
                    PositionMoveRotation.of(player), packet.change(), packet.relatives());
            BlinkFeature.onServerPosition(player.position(), target.position());
        } catch (Throwable t) {
            killer560smod$blink$threw(t);
        }
    }

    @Inject(method = "handleLogin", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
    private void killer560smod$blinkLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        try {
            BlinkFeature.onWorldChange();
        } catch (Throwable t) {
            killer560smod$blink$threw(t);
        }
    }

    @Inject(method = "handleRespawn", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER))
    private void killer560smod$blinkRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        try {
            BlinkFeature.onWorldChange();
        } catch (Throwable t) {
            killer560smod$blink$threw(t);
        }
    }

    @Inject(method = "handleConfigurationStart", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD,
            shift = At.Shift.AFTER))
    private void killer560smod$blinkConfiguration(ClientboundStartConfigurationPacket packet, CallbackInfo ci) {
        try {
            BlinkFeature.onConfigurationStart();
        } catch (Throwable t) {
            killer560smod$blink$threw(t);
        }
    }

    @Unique
    private static void killer560smod$blink$threw(Throwable t) {
        ModLog.get("killer560smod-blink").error("[Blink] packet hook threw", t);
    }
}

package com.killer560.hub.zpew.mixin;

import com.killer560.hub.zpew.ZeroPingEtherwarp;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Aimed Warps (cheat build): the yaw that last really went on the wire. The tail of {@code public void send(Packet,
 * ChannelFutureListener, boolean)} on {@code Connection} (the signature Blink's hold uses, javap 26.1.2 and 26.2) - a packet
 * Blink holds is cancelled at the head and never reaches here; when it is let go it is sent again and does. Blink Charge
 * keeps turn-only ticks off the wire, so his running yaw can be a whole turn from what the server has, and a burst's first
 * look must be a turn from what the server has (GrimAC AimModulo360 flags a jump of more than 320).
 */
@Mixin(Connection.class)
public abstract class ZpewWireMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("RETURN"))
    private void killer560smod$zpewWire(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && packet instanceof ServerboundMovePlayerPacket move
                && move.hasRotation()) {
            ZeroPingEtherwarp.onWireYaw(move.getYRot(0f));
        }
    }
}

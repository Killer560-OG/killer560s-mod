package com.killer560.hub.blink.mixin;

import com.killer560.hub.blink.BlinkFeature;
import com.killer560.hub.util.ModLog;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blink's hold: the head of {@code public void send(Packet, ChannelFutureListener, boolean)} on {@code Connection}
 * (javap 26.1.2 and 26.2, 2026-10-25: the same signature on both; the one- and two-argument {@code send}s call it). Every
 * outgoing packet passes here after every feature's listener-level tap, so holding here makes only the wire late. A throw
 * is swallowed and the packet goes.
 */
@Mixin(Connection.class)
public abstract class BlinkConnectionMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void killer560smod$blinkHold(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        try {
            if (BlinkFeature.onSend((Connection) (Object) this, packet, listener, flush)) {
                ci.cancel();
            }
        } catch (Throwable t) {
            killer560smod$blink$threw(t);
        }
    }

    @Unique
    private static void killer560smod$blink$threw(Throwable t) {
        ModLog.get("killer560smod-blink").error("[Blink] send hook threw - the packet still went", t);
    }
}

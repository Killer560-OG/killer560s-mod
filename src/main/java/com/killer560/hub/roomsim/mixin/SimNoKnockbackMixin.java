package com.killer560.hub.roomsim.mixin;

import com.killer560.hub.roomsim.SimLaunch;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The sim player takes no knockback (SimLaunch): every packet his sim server sends goes through {@link SimLaunch#filter}.
 * {@code send(Packet, ChannelFutureListener)} is the one both {@code send} overloads end in; same descriptor on 26.3 and
 * 26.2 (javap, 2026-11-05).
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class SimNoKnockbackMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
            at = @At("HEAD"), cancellable = true)
    private void killer560smod$simNoKnockback(Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
        if (!((Object) this instanceof ServerGamePacketListenerImpl game)) {
            return;
        }
        Packet<?> out = SimLaunch.filter(game.player, packet);
        if (out != packet) {
            ci.cancel();
            if (out != null) {
                ((ServerCommonPacketListenerImpl) (Object) this).send(out, listener);
            }
        }
    }
}

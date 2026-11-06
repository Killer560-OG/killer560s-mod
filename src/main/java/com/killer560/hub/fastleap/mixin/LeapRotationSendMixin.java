package com.killer560.hub.fastleap.mixin;

import com.killer560.hub.fastleap.LeapRotation;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link LeapRotation}: whether a use_item went on the wire this tick (leapsame's same-tick wait). The tail of {@code send(Packet, ChannelFutureListener,
 *  boolean)} (ZpewWireMixin's point): a packet Blink holds is canceled at the head and only counts once it is let go. */
@Mixin(Connection.class)
public abstract class LeapRotationSendMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("RETURN"))
    private void killer560smod$leapRotSent(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        LeapRotation.onSent(packet);
    }
}

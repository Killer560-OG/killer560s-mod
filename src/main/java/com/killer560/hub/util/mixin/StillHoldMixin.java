package com.killer560.hub.util.mixin;

import com.killer560.hub.util.StillHold;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link StillHold}: a chat, command or container-close packet leaves only while he stands still. Same method as LeapRotationSendMixin. */
@Mixin(Connection.class)
public abstract class StillHoldMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void killer560smod$stillHold(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (StillHold.hold(packet)) {
            ci.cancel();
        }
    }
}

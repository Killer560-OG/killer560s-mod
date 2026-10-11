package com.killer560.hub.lagdisplay.mixin;

import com.killer560.hub.util.PingTracker;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands every pong to {@link PingTracker}. {@code handlePongResponse(ClientboundPongResponsePacket)V} is the same on
 *  26.1.2 and 26.2 (javap) and runs on the Netty thread; vanilla's own handling (the F3 chart) is left alone. */
@Mixin(ClientPacketListener.class)
public abstract class PingPongMixin {

    @Inject(method = "handlePongResponse", at = @At("HEAD"))
    private void killer560smod$onPong(ClientboundPongResponsePacket packet, CallbackInfo ci) {
        try {
            PingTracker.onPong(packet.time());
        } catch (RuntimeException ignored) {
            // Never let a throw reach the packet path: it disconnects from the server.
        }
    }
}

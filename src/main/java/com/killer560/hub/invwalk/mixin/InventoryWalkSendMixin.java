package com.killer560.hub.invwalk.mixin;

import com.killer560.hub.invwalk.InventoryWalkFeature;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inventory Walk's tap on what this client sends: its container clicks, for the log, Pause After Click and Stop To Click
 * (a click made while walking is held until a still tick has reached the server, then sent as it was).
 * {@code public void send(Packet<?>)} on {@code ClientCommonPacketListenerImpl}, the target {@code SmoothTpOutboundMixin}
 * uses (26.1.2 and 26.2). Only a held click is canceled (and sent later); a throw is swallowed so the packet goes.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class InventoryWalkSendMixin {

    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    private void killer560smod$invWalkTap(Packet<?> packet, CallbackInfo ci) {
        try {
            if (InventoryWalkFeature.onPacketSent(packet)) {
                ci.cancel();
            }
        } catch (Throwable t) {
            killer560smod$invWalk$threw(t);
        }
    }

    @Unique
    private static void killer560smod$invWalk$threw(Throwable t) {
        ModLog.get("killer560smod-invwalk").error("[InvWalk] send tap threw - the packet still went", t);
    }
}

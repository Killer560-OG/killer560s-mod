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
 * Inventory Walk's read-only tap on what this client sends (its container clicks, for Pause After Click and the log).
 * {@code public void send(Packet<?>)} on {@code ClientCommonPacketListenerImpl}, the target {@code SmoothTpOutboundMixin}
 * uses (26.1.2 and 26.2). Nothing is canceled; a throw is swallowed so the packet still goes.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class InventoryWalkSendMixin {

    @Inject(method = "send", at = @At("HEAD"))
    private void killer560smod$invWalkTap(Packet<?> packet, CallbackInfo ci) {
        try {
            InventoryWalkFeature.onPacketSent(packet);
        } catch (Throwable t) {
            killer560smod$invWalk$threw(t);
        }
    }

    @Unique
    private static void killer560smod$invWalk$threw(Throwable t) {
        ModLog.get("killer560smod-invwalk").error("[InvWalk] send tap threw - the packet still went", t);
    }
}

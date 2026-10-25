package com.killer560.hub.zpew.mixin;

import com.killer560.hub.util.ModLog;
import com.killer560.hub.zpew.ZeroPingEtherwarp;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Zero Ping Etherwarp's read-only tap on what this client sends: {@code public void send(Packet<?>)} on
 * {@code ClientCommonPacketListenerImpl} (26.1.2 and 26.2; the target {@code SmoothTpOutboundMixin} uses). Above Blink's
 * hold, so a warp is predicted when it is made, not when a burst releases it. Nothing is canceled.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ZpewSendMixin {

    @Inject(method = "send", at = @At("HEAD"))
    private void killer560smod$zpewTap(Packet<?> packet, CallbackInfo ci) {
        try {
            ZeroPingEtherwarp.onPacketSent(packet);
        } catch (Throwable t) {
            killer560smod$zpew$threw(t);
        }
    }

    @Unique
    private static void killer560smod$zpew$threw(Throwable t) {
        ModLog.get("killer560smod-zpew").error("[ZPEW] send tap threw - the packet still went", t);
    }
}

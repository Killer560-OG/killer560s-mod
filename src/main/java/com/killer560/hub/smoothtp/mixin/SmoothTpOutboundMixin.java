package com.killer560.hub.smoothtp.mixin;

import com.killer560.hub.smoothtp.SmoothTeleport;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Read-only tap on what this client sends, so Smooth Teleport knows a teleport item was used (and with which look)
 * before the server's position packet arrives. {@code public void send(Packet<?>)} on
 * {@code ClientCommonPacketListenerImpl} is on 26.1.2 and 26.2 (javap, 2026-10-07; the same target
 * {@code dungeonextras/mixin/OutboundBreakMixin} uses); every use packet - vanilla's, Auto Routes', the Interactive
 * Map's - passes through it. Nothing is cancelled, copied or sent; a throw is swallowed so the packet still goes.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class SmoothTpOutboundMixin {

    @Inject(method = "send", at = @At("HEAD"))
    private void killer560smod$smoothTpOutbound(Packet<?> packet, CallbackInfo ci) {
        try {
            SmoothTeleport.onPacketSent(packet);
        } catch (Throwable t) {
            killer560smod$smoothTp$threw(t);
        }
    }

    @Unique
    private static void killer560smod$smoothTp$threw(Throwable t) {
        ModLog.get("killer560smod-smoothtp").error("[SmoothTeleport] outbound tap threw - the packet still went", t);
    }
}

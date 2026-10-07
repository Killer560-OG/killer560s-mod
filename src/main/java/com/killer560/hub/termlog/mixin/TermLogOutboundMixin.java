package com.killer560.hub.termlog.mixin;

import com.killer560.hub.termlog.TerminalOpenLogger;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Read-only tap on what this client sends, for the Terminal Open Logger. {@code public void send(Packet<?>)} on
 * {@code ClientCommonPacketListenerImpl} (javap 26.1.2 and 26.2, 2026-10-07; the same target SmoothTpOutboundMixin and
 * OutboundBreakMixin use). Nothing is cancelled or touched; {@link TerminalOpenLogger#onPacketSent} never throws, and
 * is wrapped again here because this is the send path of every packet.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class TermLogOutboundMixin {

    @Inject(method = "send", at = @At("HEAD"))
    private void killer560smod$termLogOutbound(Packet<?> packet, CallbackInfo ci) {
        try {
            TerminalOpenLogger.onPacketSent(packet);
        } catch (Throwable ignored) {
            // read-only: the packet goes regardless
        }
    }
}

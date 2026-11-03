package com.killer560.hub.miningqol.mixin;

import com.killer560.hub.miningqol.AttackHoldProbe;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * READ-ONLY tap on what this client sends, for {@link AttackHoldProbe}'s ring of recent packets. Target:
 * {@code public void send(net.minecraft.network.protocol.Packet<?>)} on {@code ClientCommonPacketListenerImpl} (javap,
 * 26.3 and 26.2), which ClientPacketListener inherits, the same target OutboundBreakMixin reads. At HEAD, nothing is
 * canceled or changed; any throw is swallowed (this sits on the send path of every packet).
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class MiningSendTapMixin {

    @Inject(method = "send", at = @At("HEAD"), require = 0)
    private void killer560smod$miningSendTap(Packet<?> packet, CallbackInfo ci) {
        try {
            AttackHoldProbe.onSend(packet);
        } catch (Throwable ignored) {
            // diagnostics only: never break a send
        }
    }
}

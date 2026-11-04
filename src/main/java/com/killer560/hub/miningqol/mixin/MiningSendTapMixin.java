package com.killer560.hub.miningqol.mixin;

import com.killer560.hub.miningqol.AttackHoldProbe;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tap on what this client sends, for {@link AttackHoldProbe}'s ring of recent packets. Target:
 * {@code public void send(net.minecraft.network.protocol.Packet<?>)} on {@code ClientCommonPacketListenerImpl} (javap,
 * 26.3 and 26.2), which ClientPacketListener inherits, the same target OutboundBreakMixin reads. Any throw is swallowed
 * (this sits on the send path of every packet). One packet is dropped, on Hypixel only: the dig's
 * CHANGE_DESTROY_DIRECTION ({@link AttackHoldProbe#dropOnHypixel}; killer560's 2026-11-03 19:40 log: both real stalls
 * began with it, and one ping later Hypixel cleared the crack and dropped the dig while the client kept swinging).
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class MiningSendTapMixin {

    @Inject(method = "send", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$miningSendTap(Packet<?> packet, CallbackInfo ci) {
        try {
            if (AttackHoldProbe.dropOnHypixel(packet)) {
                ci.cancel();
                return;
            }
            AttackHoldProbe.onSend(packet);
        } catch (Throwable ignored) {
            // never break a send
        }
    }
}

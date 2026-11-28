package com.killer560.hub.miningqol.mixin;

import com.killer560.hub.miningqol.HeldBreakResume;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops one packet on Hypixel: the dig's CHANGE_DESTROY_DIRECTION ({@link HeldBreakResume#dropOnHypixel}). Target:
 * {@code public void send(net.minecraft.network.protocol.Packet<?>)} on {@code ClientCommonPacketListenerImpl} (javap,
 * 26.3 and 26.2). Any throw is swallowed (this sits on the send path of every packet).
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class MiningSendTapMixin {

    @Inject(method = "send", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$miningSendTap(Packet<?> packet, CallbackInfo ci) {
        try {
            if (com.killer560.hub.util.RemoveFor20.ACTIVE && HeldBreakResume.dropOnHypixel(packet)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // never break a send
        }
    }
}

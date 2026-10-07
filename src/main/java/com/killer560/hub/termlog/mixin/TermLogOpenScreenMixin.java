package com.killer560.hub.termlog.mixin;

import com.killer560.hub.termlog.TerminalOpenLogger;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Terminal Open Logger's outcome: a container opening, read at the HEAD of
 * {@code ClientPacketListener.handleOpenScreen(ClientboundOpenScreenPacket)} (javap 26.1.2 and 26.2, 2026-10-07).
 * The HEAD runs once on the network thread (before vanilla hops) and once on the render thread; the logger only
 * acts on the second. Read-only, and a throw out of a clientbound handler disconnects, so it is caught twice.
 */
@Mixin(ClientPacketListener.class)
public abstract class TermLogOpenScreenMixin {

    @Inject(method = "handleOpenScreen", at = @At("HEAD"))
    private void killer560smod$termLogOpenScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        try {
            TerminalOpenLogger.onOpenScreen(packet);
        } catch (Throwable ignored) {
            // read-only: the screen opens regardless
        }
    }
}

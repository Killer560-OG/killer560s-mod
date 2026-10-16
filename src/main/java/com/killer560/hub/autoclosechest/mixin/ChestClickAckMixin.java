package com.killer560.hub.autoclosechest.mixin;

import com.killer560.hub.util.ModLog;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every block-click ack the server sends, as it arrives - see {@code util/ChestWindows#noteAck}. Observes only. */
@Mixin(ClientPacketListener.class)
public abstract class ChestClickAckMixin {

    @Inject(method = "handleBlockChangedAck", at = @At("HEAD"))
    private void killer560smod$noteClickAck(ClientboundBlockChangedAckPacket packet, CallbackInfo ci) {
        try {
            com.killer560.hub.util.ChestWindows.noteAck(packet.sequence());
        } catch (RuntimeException e) {
            // A throw out of a clientbound packet hook disconnects him (ClientCommonPacketListenerImpl.onPacketError).
            ModLog.get("killer560smod-autoclosechest").error("[AutoCloseChest] block ack hook threw", e);
        }
    }
}

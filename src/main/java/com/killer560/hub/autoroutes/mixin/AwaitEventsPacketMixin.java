package com.killer560.hub.autoroutes.mixin;

import com.killer560.hub.autoroutes.AwaitEvents;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Auto Routes' await: an item pickup, and who picked it up. Same hook point as {@code DungeonAlertsPacketMixin}
 * (javap-verified, 26.1.2 and 26.2): right after {@code PacketUtils.ensureRunningOnSameThread}, so on the main thread
 * and before vanilla removes the item entity. Read-only, never cancels.
 */
@Mixin(ClientPacketListener.class)
public abstract class AwaitEventsPacketMixin {

    private static final String ENSURE_SAME_THREAD =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleTakeItemEntity", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER), require = 0)
    private void killer560smod$awaitTakeItem(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
        try {
            AwaitEvents.onTakeItem(packet.getItemId(), packet.getPlayerId());
        } catch (RuntimeException ignored) {
            // never breaks packet handling
        }
    }
}

package com.killer560.hub.itemalerts.mixin;

import com.killer560.hub.itemalerts.ItemAlertsFeature;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks the inventory dirty when a slot or a whole container is set (Item Alerts). TAIL only: on the netty thread both
 *  handlers bail at their first line and re-queue to the render thread, so TAIL runs once, there, after the slots are set
 *  (same targets as SpiritLeapContentMixin). */
@Mixin(ClientPacketListener.class)
public abstract class ItemAlertsPacketMixin {

    @Inject(method = "handleContainerContent", at = @At("TAIL"), require = 0)
    private void killer560smod$itemAlertsContents(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        ItemAlertsFeature.onContainerPacket(packet.containerId(), true);
    }

    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"), require = 0)
    private void killer560smod$itemAlertsSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        ItemAlertsFeature.onContainerPacket(packet.getContainerId(), false);
    }
}

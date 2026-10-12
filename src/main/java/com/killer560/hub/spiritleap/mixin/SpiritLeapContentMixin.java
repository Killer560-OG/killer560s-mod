package com.killer560.hub.spiritleap.mixin;

import com.killer560.hub.spiritleap.SpiritLeapOverlayFeature;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the custom leap menu when a container's slots were last replaced whole (a contents packet) or touched one at a
 * time (a slot packet), so it can take a complete menu on the first frame instead of waiting out its settle window
 * (2026-10-11, the leap delay). TAIL only: on the netty thread both handlers bail out at their first line (they
 * re-queue themselves to the render thread), so TAIL is reached once, on the render thread, after the slots are set.
 * Targets checked with javap on 26.1.2 and 26.2 ({@code handleContainerContent}, {@code handleContainerSetSlot}).
 */
@Mixin(ClientPacketListener.class)
public abstract class SpiritLeapContentMixin {

    @Inject(method = "handleContainerContent", at = @At("TAIL"), require = 0)
    private void killer560smod$leapFullContents(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        SpiritLeapOverlayFeature.onFullContents(packet.containerId());
    }

    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"), require = 0)
    private void killer560smod$leapSetSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        SpiritLeapOverlayFeature.onSetSlot(packet.getContainerId());
    }
}

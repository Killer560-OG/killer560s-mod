package com.killer560.hub.islands.mixin;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.islands.AutoHarpFeature;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets Auto Melody's Harp click a note the moment its click-row slot shows it, not at the next client tick's end
 *  (harpmiss5-1121). TAIL only: on the netty thread the handler re-queues itself to the render thread, so TAIL runs
 *  once, there, after the slot is set. */
@Mixin(ClientPacketListener.class)
public abstract class AutoHarpSlotMixin {

    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void killer560smod$harpSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        if (BuildVariant.CHEAT_FEATURES_ENABLED) {
            AutoHarpFeature.onSlot(packet.getContainerId(), packet.getSlot());
        }
    }
}

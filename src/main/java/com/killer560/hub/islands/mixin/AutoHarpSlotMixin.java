package com.killer560.hub.islands.mixin;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.islands.AutoHarpFeature;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets Auto Melody's Harp click a note the moment its click-row slot shows it, not at the next client tick's end
 *  (harpmiss5-1121). On the netty thread the handler re-queues itself to the render thread and throws, so HEAD runs
 *  twice per packet (netty, then render: Auto Harp times its steps by the netty call, harpmiss-1122) and TAIL once,
 *  on the render thread, after the slot is set. */
@Mixin(ClientPacketListener.class)
public abstract class AutoHarpSlotMixin {

    @Inject(method = "handleContainerSetSlot", at = @At("HEAD"))
    private void killer560smod$harpSlotHead(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        if (BuildVariant.CHEAT_FEATURES_ENABLED) {
            AutoHarpFeature.onSlotHead(packet, packet.getContainerId(), packet.getSlot(), packet.getItem());
        }
    }

    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void killer560smod$harpSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        if (BuildVariant.CHEAT_FEATURES_ENABLED) {
            AutoHarpFeature.onSlot(packet.getContainerId(), packet.getSlot());
        }
    }

    /** HEAD runs on the netty thread first, as for slots: Auto Harp times a pling (its click's answer) by that call. */
    @Inject(method = "handleSoundEvent", at = @At("HEAD"))
    private void killer560smod$harpSoundHead(ClientboundSoundPacket packet, CallbackInfo ci) {
        if (BuildVariant.CHEAT_FEATURES_ENABLED) {
            AutoHarpFeature.onSoundHead(packet);
        }
    }
}

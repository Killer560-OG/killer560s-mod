package com.killer560.hub.invwalk.mixin;

import com.killer560.hub.invwalk.InventoryWalkFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inventory Walk's "[InvWalk] server closed" line: {@code handleContainerClose(ClientboundContainerClosePacket)} on
 * {@code ClientPacketListener} (26.2 and 26.3), at HEAD on the render thread only, while the screen is still open (the
 * netty pass is the one that hands the packet over and throws).
 */
@Mixin(ClientPacketListener.class)
public abstract class InventoryWalkCloseMixin {

    @Inject(method = "handleContainerClose", at = @At("HEAD"))
    private void killer560smod$invWalkServerClose(CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) {
            InventoryWalkFeature.onServerClose();
        }
    }
}

package com.killer560.hub.fastleap.mixin;

import com.killer560.hub.fastleap.LeapManager;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Queue Leap: movement comes back the moment the server teleport has been applied (cheat build only). */
@Mixin(ClientPacketListener.class)
public abstract class QueueLeapTeleportMixin {

    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void killer560smod$queueLeapTeleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            LeapManager.onServerTeleport();
        }
    }
}

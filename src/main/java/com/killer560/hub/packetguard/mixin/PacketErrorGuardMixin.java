package com.killer560.hub.packetguard.mixin;

import com.killer560.hub.packetguard.PacketGuard;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** See {@link PacketGuard}: a packet error thrown by this mod's own code is logged and skipped instead of disconnecting.
 *  {@code onPacketError(Packet, Exception)} has the same descriptor on 26.1.2, 26.2 and 26.3 (javap 2026-11-02), and
 *  {@code ClientPacketListener} does not override it. A cancellable HEAD inject composes with any other mod's hook. */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class PacketErrorGuardMixin {

    @Inject(method = "onPacketError", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$keepConnectedOnOwnThrow(Packet<?> packet, Exception error, CallbackInfo ci) {
        boolean keep;
        try {
            keep = PacketGuard.keepConnected(packet, error);
        } catch (RuntimeException e) {
            keep = false;
        }
        if (keep) {
            ci.cancel();
        }
    }
}

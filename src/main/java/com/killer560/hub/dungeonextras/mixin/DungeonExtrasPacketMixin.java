package com.killer560.hub.dungeonextras.mixin;

import com.killer560.hub.dungeonextras.MageBeamFeature;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Custom Mage Beam packet hook. Injected after {@code PacketUtils.ensureRunningOnSameThread} (verified with javap on
 *  the 26.1.2 merged jar: first call in {@code handleParticleEvent}), so it only runs on the main thread. */
@Mixin(ClientPacketListener.class)
public abstract class DungeonExtrasPacketMixin {

    private static final String ENSURE_SAME_THREAD =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleParticleEvent", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER),
            cancellable = true, require = 0)
    private void killer560smod$dungeonExtrasOnParticle(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        if (MageBeamFeature.onParticlePacket(packet)) {
            ci.cancel();
        }
    }
}

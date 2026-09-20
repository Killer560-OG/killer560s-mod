package com.killer560.hub.dungeonextras.mixin;

import com.killer560.hub.dungeonextras.MageBeamFeature;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
        try {
            if (MageBeamFeature.onParticlePacket(packet)) {
                ci.cancel();
            }
        } catch (RuntimeException e) {
            killer560smod$dungeonExtras$hookThrew("particles", e);
        }
    }

    // An exception out of a clientbound packet hook does not just log: ClientCommonPacketListenerImpl
    // .onPacketError disconnects the client with disconnect.packetError ("Network Protocol Error"). Fail
    // closed to a log line and let vanilla handle the packet - see BloodCampPacketMixin for the real case.
    @Unique
    private static void killer560smod$dungeonExtras$hookThrew(String what, RuntimeException e) {
        LoggerFactory.getLogger("killer560smod-dungeonextras").error("[DungeonExtras] {} packet hook threw", what, e);
    }
}

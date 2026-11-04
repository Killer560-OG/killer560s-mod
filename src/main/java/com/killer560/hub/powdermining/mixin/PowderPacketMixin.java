package com.killer560.hub.powdermining.mixin;

import com.killer560.hub.powdermining.LockpickHelper;
import com.killer560.hub.powdermining.PowderPackets;
import com.killer560.hub.powdermining.TreasureChests;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Treasure chest blocks and the lockpick particles, read the tick they arrive. The block handlers at RETURN (as
 * MiningBlockUpdateMixin: reached only on the client thread, after the change is in the level); the particle handler after
 * {@code PacketUtils.ensureRunningOnSameThread} (as DungeonExtrasPacketMixin). Nothing is canceled. A throw out of a packet
 * hook disconnects him, so each one is caught.
 */
@Mixin(ClientPacketListener.class)
public abstract class PowderPacketMixin {

    private static final String ENSURE_SAME_THREAD =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleBlockUpdate", at = @At("RETURN"), require = 0)
    private void killer560smod$powderBlock(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        try {
            TreasureChests.onBlockChanged(packet.getPos());
        } catch (RuntimeException e) {
            killer560smod$powder$threw("blockUpdate", e);
        }
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("RETURN"), require = 0)
    private void killer560smod$powderSection(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        try {
            PowderPackets.onSectionChanged(packet);
        } catch (RuntimeException e) {
            killer560smod$powder$threw("sectionUpdate", e);
        }
    }

    @Inject(method = "handleParticleEvent", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER),
            require = 0)
    private void killer560smod$powderParticle(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        try {
            LockpickHelper.onParticle(packet);
        } catch (RuntimeException e) {
            killer560smod$powder$threw("particles", e);
        }
    }

    @Unique
    private static void killer560smod$powder$threw(String what, RuntimeException e) {
        ModLog.get("killer560smod-powdermining").error("[PowderMining] {} packet hook threw", what, e);
    }
}

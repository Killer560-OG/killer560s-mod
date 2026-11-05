package com.killer560.hub.miningqol.mixin;

import com.killer560.hub.compat.McPackets;
import com.killer560.hub.miningqol.MiningHighlights;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The mining block highlights follow the server's block changes the tick they arrive (killer560, 2026-11-03: "make it
 * update stuff like highlighted blocks faster"). At RETURN, as LiveMapPacketListenerMixin does: the first call of each
 * handler on the network thread throws out of ensureRunningOnSameThread to reschedule, so RETURN is reached only on the
 * client thread, once, after the change is in the level. All three are public void handlers taking just the packet
 * (javap, 26.3 and 26.2). A throw out of a packet hook disconnects him, so each one is caught.
 */
@Mixin(ClientPacketListener.class)
public abstract class MiningBlockUpdateMixin {

    @Inject(method = "handleBlockUpdate", at = @At("RETURN"), require = 0)
    private void killer560smod$miningBlock(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        try {
            MiningHighlights.onBlockChanged(packet.getPos());
            com.killer560.hub.miningqol.CommissionProgress.onBlockChanged(packet.getPos());
            com.killer560.hub.playerstats.PlayerStatsFeature.onBlockChanged(packet.getPos());
        } catch (RuntimeException e) {
            killer560smod$mining$threw("blockUpdate", e);
        }
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("RETURN"), require = 0)
    private void killer560smod$miningSection(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        try {
            MiningHighlights.onSectionChanged(packet);
        } catch (RuntimeException e) {
            killer560smod$mining$threw("sectionUpdate", e);
        }
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"), require = 0)
    private void killer560smod$miningChunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        try {
            MiningHighlights.onChunkLoaded(McPackets.chunkX(packet), McPackets.chunkZ(packet));
        } catch (RuntimeException e) {
            killer560smod$mining$threw("levelChunk", e);
        }
    }

    @Unique
    private static void killer560smod$mining$threw(String what, RuntimeException e) {
        ModLog.get("killer560smod-miningqol").error("[MiningQol] {} packet hook threw", what, e);
    }
}

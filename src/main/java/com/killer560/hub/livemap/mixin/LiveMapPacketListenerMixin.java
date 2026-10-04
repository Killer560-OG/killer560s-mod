package com.killer560.hub.livemap.mixin;

import com.killer560.hub.livemap.LiveMapConfig;
import com.killer560.hub.livemap.autoclear.ClearExecutor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import com.killer560.hub.livemap.autoclear.LevelEtherGrid;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import com.killer560.hub.util.ModLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Interactive Map packet hooks (QUOI InteractiveMap / ClearExecutor):
 * <ul>
 * <li>"Keep chunks loaded" - QUOI cancels {@code ClientboundForgetLevelChunkPacket} so far rooms stay scannable and
 * raycastable for long teleport paths.
 * <li>{@code ClientboundPlayerPositionPacket} - the executor's "server synced the last teleport" signal.
 * </ul>
 * Both inject after {@code PacketUtils.ensureRunningOnSameThread}, so they run once, on the client thread.
 */
@Mixin(ClientPacketListener.class)
public abstract class LiveMapPacketListenerMixin {

    private static final String ENSURE_SAME_THREAD =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleForgetLevelChunk", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER),
            cancellable = true, require = 0)
    private void killer560smod$liveMapKeepChunks(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        try {
            if (LiveMapConfig.getInstance().isKeepChunksLoaded()) {
                ci.cancel();
            }
        } catch (RuntimeException e) {
            killer560smod$liveMap$hookThrew("forgetChunk", e);
        }
    }

    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER),
            require = 0)
    private void killer560smod$liveMapPositionSync(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        try {
            ClearExecutor.onServerPositionPacket();
            // Auto Routes' landing check: a teleport really happened (see RouteExecutor#landed).
            com.killer560.hub.autoroutes.RouteExecutor.onServerPositionPacket();
        } catch (RuntimeException e) {
            killer560smod$liveMap$hookThrew("movePlayer", e);
        }
    }

    // THE PATHFINDER'S BLOCK CACHE (LevelEtherGrid) forgets a section the moment a block in it changes.
    //
    // At RETURN, not after ensureRunningOnSameThread: the planner reads the cache from its own thread, and
    // invalidating BEFORE vanilla applies the change would let a search refill the section from the old blocks
    // in between. When the handler is first called on the network thread, ensureRunningOnSameThread throws to
    // reschedule it, so RETURN is only ever reached on the client thread, once, after the change is in. Targets
    // checked with javap against both merged jars (26.1.2 and 26.2): all three are public void handlers taking
    // just the packet.

    @Inject(method = "handleBlockUpdate", at = @At("RETURN"), require = 0)
    private void killer560smod$etherGridBlock(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        try {
            LevelEtherGrid.invalidate(packet.getPos());
        } catch (RuntimeException e) {
            killer560smod$liveMap$hookThrew("blockUpdate", e);
        }
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("RETURN"), require = 0)
    private void killer560smod$etherGridSection(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        try {
            LevelEtherGrid.invalidate(packet);
        } catch (RuntimeException e) {
            killer560smod$liveMap$hookThrew("sectionUpdate", e);
        }
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"), require = 0)
    private void killer560smod$etherGridChunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        try {
            LevelEtherGrid.invalidateColumn(packet.getX(), packet.getZ());
        } catch (RuntimeException e) {
            killer560smod$liveMap$hookThrew("levelChunk", e);
        }
    }

    // An exception out of a clientbound packet hook does not just log: ClientCommonPacketListenerImpl
    // .onPacketError disconnects the client with disconnect.packetError ("Network Protocol Error"). Fail
    // closed to a log line and let vanilla handle the packet - see BloodCampPacketMixin for the real case.
    @Unique
    private static void killer560smod$liveMap$hookThrew(String what, RuntimeException e) {
        ModLog.get("killer560smod-livemap").error("[LiveMap] {} packet hook threw", what, e);
    }
}

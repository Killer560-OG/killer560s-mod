package com.killer560.hub.puzzlesolvers.mixin;

import com.killer560.hub.autopuzzles.AutoBeams;
import com.killer560.hub.puzzlesolvers.TeleportMazeSolverFeature;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Read-only packet observation for the puzzle solvers/autos. javap-checked against the 26.1.2 merged jar: both
 *  handlers start with {@code PacketUtils.ensureRunningOnSameThread(packet, this, minecraft.packetProcessor())}, so
 *  the hooks sit right AFTER that call - main thread only, before vanilla applies the packet (the player's bounding
 *  box is still the pre-teleport one, as QUOI's Teleport Maze logic expects). Never cancels. {@code require = 0}.
 *  <p>
 *  Guarded (2026-09-20): an exception out of a clientbound packet handler is not just a log line -
 *  {@code ClientCommonPacketListenerImpl.onPacketError} disconnects the client with
 *  {@code disconnect.packetError} ("Network Protocol Error"). That is how one NPE in Blood Camp was kicking
 *  killer560 off p3sim seconds after joining, so every packet hook now fails closed to a log line. */
@Mixin(ClientPacketListener.class)
public abstract class PuzzlePacketMixin {

    private static final String ENSURE_SAME_THREAD =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER), require = 0)
    private void killer560smod$puzzles$onMovePlayer(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        try {
            TeleportMazeSolverFeature.onPlayerPosition(packet);
        } catch (RuntimeException e) {
            killer560smod$puzzles$hookThrew("movePlayer", e);
        }
    }

    @Inject(method = "handleSoundEvent", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER), require = 0)
    private void killer560smod$puzzles$onSound(ClientboundSoundPacket packet, CallbackInfo ci) {
        try {
            AutoBeams.onSound(packet);
        } catch (RuntimeException e) {
            killer560smod$puzzles$hookThrew("sound", e);
        }
    }

    @Unique
    private static void killer560smod$puzzles$hookThrew(String what, RuntimeException e) {
        LoggerFactory.getLogger("killer560smod-puzzlesolvers").error("[PuzzleSolvers] {} packet hook threw", what, e);
    }
}

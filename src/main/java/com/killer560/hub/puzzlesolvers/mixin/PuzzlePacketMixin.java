package com.killer560.hub.puzzlesolvers.mixin;

import com.killer560.hub.autopuzzles.AutoBeams;
import com.killer560.hub.puzzlesolvers.TeleportMazeSolverFeature;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Read-only packet observation for the puzzle solvers/autos. javap-checked against the 26.1.2 merged jar: both
 *  handlers start with {@code PacketUtils.ensureRunningOnSameThread(packet, this, minecraft.packetProcessor())}, so
 *  the hooks sit right AFTER that call - main thread only, before vanilla applies the packet (the player's bounding
 *  box is still the pre-teleport one, as QUOI's Teleport Maze logic expects). Never cancels. {@code require = 0}. */
@Mixin(ClientPacketListener.class)
public abstract class PuzzlePacketMixin {

    private static final String ENSURE_SAME_THREAD =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER), require = 0)
    private void killer560smod$puzzles$onMovePlayer(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        TeleportMazeSolverFeature.onPlayerPosition(packet);
    }

    @Inject(method = "handleSoundEvent", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER), require = 0)
    private void killer560smod$puzzles$onSound(ClientboundSoundPacket packet, CallbackInfo ci) {
        AutoBeams.onSound(packet);
    }
}

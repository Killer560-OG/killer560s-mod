package com.killer560.hub.terminals.mixin;

import com.killer560.hub.terminals.TerminalSolverFeature;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells the Terminal Solver which window slots the server has described (termplus-1112): a board counts as loaded only
 *  once every one has been. TAIL only: on the netty thread these handlers re-queue themselves to the render thread, so
 *  TAIL runs once, there, after the slots are set. */
@Mixin(ClientPacketListener.class)
public abstract class TerminalBoardLoadMixin {

    @Inject(method = "handleOpenScreen", at = @At("TAIL"))
    private void killer560smod$boardOpen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        TerminalSolverFeature.onBoardOpen(packet.getContainerId());
    }

    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void killer560smod$boardContents(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        TerminalSolverFeature.onBoardContents(packet.containerId(), packet.items());
    }

    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void killer560smod$boardSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        TerminalSolverFeature.onBoardSlot(packet.getContainerId(), packet.getSlot());
    }
}

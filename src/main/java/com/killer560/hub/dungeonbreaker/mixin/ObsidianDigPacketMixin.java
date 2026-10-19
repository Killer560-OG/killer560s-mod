package com.killer560.hub.dungeonbreaker.mixin;

import com.killer560.hub.util.ModLog;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The server's break-progress packets, for the obsidian dig's log ({@code dungeonbreaker.ObsidianDig}). Observes only.
 * {@code handleBlockDestruction(ClientboundBlockDestructionPacket)} starts with {@code PacketUtils.ensureRunningOnSameThread}
 * on both 26.1.2 and 26.2 (javap, 2026-10-19), so the hook sits after it and runs once, on the render thread.
 */
@Mixin(ClientPacketListener.class)
public abstract class ObsidianDigPacketMixin {

    private static final String ENSURE_SAME_THREAD =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleBlockDestruction", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD,
            shift = At.Shift.AFTER), require = 0)
    private void killer560smod$obsidianStage(ClientboundBlockDestructionPacket packet, CallbackInfo ci) {
        try {
            com.killer560.hub.dungeonbreaker.ObsidianDig.onServerStage(packet.getPos(), packet.getProgress());
        } catch (RuntimeException e) {
            // A throw out of a clientbound packet hook disconnects him (ClientCommonPacketListenerImpl.onPacketError).
            ModLog.get("killer560smod-dungeonbreaker").error("[DungeonBreaker] destruction stage hook threw", e);
        }
    }
}

package com.killer560.hub.pathfinding.mixin;

import com.killer560.hub.pathfinding.AutoWalker;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Auto Fairy Souls' knockback and death failsafes (killer560, 2026-10-11: "it needs failsafes for if it takes damage, knockback,
 * dies"): a server velocity packet for the LOCAL player is handed to {@link AutoWalker#onServerMotion}. Read-only.
 * <p>
 * javap on both 26.1.2 and 26.2: {@code public void handleSetEntityMotion(ClientboundSetEntityMotionPacket)} begins
 * with {@code PacketUtils.ensureRunningOnSameThread(packet, this, minecraft.packetProcessor())}; the hook sits right
 * after it, on the main thread. The packet is a record with {@code id()} and {@code movement()}. Fails closed to a
 * log line: a throw out of a packet handler disconnects the client.
 */
@Mixin(ClientPacketListener.class)
public abstract class PathMotionPacketMixin {

    private static final String ENSURE_SAME_THREAD =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleSetEntityMotion", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER), require = 0)
    private void killer560smod$pathMotion(ClientboundSetEntityMotionPacket packet, CallbackInfo ci) {
        try {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null && packet != null && packet.id() == player.getId()) {
                AutoWalker.onServerMotion(packet.movement());
            }
        } catch (RuntimeException e) {
            killer560smod$pathMotionThrew(e);
        }
    }

    /** Death failsafe: the server's "you died" packet (javap both versions: same ensureRunningOnSameThread head,
     *  record accessor {@code playerId()}). With immediate respawn the client never sees itself at 0 health. */
    @Inject(method = "handlePlayerCombatKill", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER), require = 0)
    private void killer560smod$pathDeath(net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
        try {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null && packet != null && packet.playerId() == player.getId()) {
                AutoWalker.onServerDeath();
            }
        } catch (RuntimeException e) {
            killer560smod$pathMotionThrew(e);
        }
    }

    @Unique
    private static void killer560smod$pathMotionThrew(RuntimeException e) {
        ModLog.get("killer560smod-pathfinding").error("[Pathfinding] motion packet hook threw", e);
    }
}

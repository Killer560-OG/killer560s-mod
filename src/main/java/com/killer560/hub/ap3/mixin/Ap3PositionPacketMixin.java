package com.killer560.hub.ap3.mixin;

import com.killer560.hub.ap3.Ap3Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Read-only observation of the server's position corrections for AP3's align diagnostics - killer560 (2026-09-21, real
 * Hypixel): "it kind of does this short lag at the end moving me". A {@code ClientboundPlayerPositionPacket} that
 * arrives during an align, or in the 10 ticks after it, is Hypixel putting him somewhere else than where the client
 * walked; {@link Ap3Executor#onServerPositionPacket} logs its delta from the client position and the dev align line
 * counts them ("server corrections N"), so the next test log says whether the movement was rejected.
 * <p>
 * Exactly the injection two live mixins already use ({@code puzzlesolvers/PuzzlePacketMixin},
 * {@code livemap/LiveMapPacketListenerMixin}), javap-checked again on the 26.1.2 jar:
 * {@code public void handleMovePlayer(ClientboundPlayerPositionPacket)} on {@code ClientPacketListener} begins with
 * {@code PacketUtils.ensureRunningOnSameThread(packet, this, minecraft.packetProcessor())}, so the hook sits right
 * after it - main thread, BEFORE vanilla applies the packet, so the client position read here is still the one the
 * server is correcting. The absolute target is computed the way vanilla does
 * ({@code PositionMoveRotation.calculateAbsolute(of(player), change, relatives)}); nothing is changed or cancelled.
 * Fails closed to a log line: an exception out of a packet handler disconnects the client ("Network Protocol Error").
 */
@Mixin(ClientPacketListener.class)
public abstract class Ap3PositionPacketMixin {

    private static final String ENSURE_SAME_THREAD =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER), require = 0)
    private void killer560smod$ap3ServerPosition(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        try {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || packet == null || packet.change() == null) {
                return;
            }
            PositionMoveRotation target = PositionMoveRotation.calculateAbsolute(
                    PositionMoveRotation.of(player), packet.change(), packet.relatives());
            Vec3 to = target.position();
            Vec3 at = player.position();
            Ap3Executor.onServerPositionPacket(to.x - at.x, to.y - at.y, to.z - at.z);
        } catch (RuntimeException e) {
            killer560smod$ap3$hookThrew(e);
        }
    }

    @Unique
    private static void killer560smod$ap3$hookThrew(RuntimeException e) {
        LoggerFactory.getLogger("killer560smod-ap3").error("[AP3] position packet hook threw", e);
    }
}

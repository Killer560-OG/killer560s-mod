package com.killer560.hub.autoroutes.mixin;

import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.RightClick;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The other half of {@link RightClickSendMixin}: vanilla answers a server teleport with an accept and a position-and-look
 * packet sent on the {@code Connection} directly (javap 26.1.2 and 26.2: {@code Connection.send} twice inside
 * {@code public void handleMovePlayer(ClientboundPlayerPositionPacket)}), which the listener-level send tap never sees.
 * At RETURN the teleport is applied and that packet has gone, so {@link RightClick#onTeleportAnswered} records what it
 * told the server. RETURN is reached once, on the client thread (the first call on the network thread throws out of
 * {@code ensureRunningOnSameThread} to reschedule). Read-only; a throw is swallowed - a clientbound hook that throws
 * disconnects him.
 */
@Mixin(ClientPacketListener.class)
public abstract class RightClickTeleportMixin {

    @Unique
    private static final String ENSURE_SAME_THREAD =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    /**
     * Before vanilla applies the packet and answers it (the injection point {@code SmoothTpPositionMixin} and
     * {@code Ap3PositionPacketMixin} use, client thread, both versions): an Auto Routes path hop landing may turn the body to
     * the next hop's look here, so the answer carries it ({@code RouteExecutor.beforeTeleportAnswer}). The target is
     * computed as vanilla does. Nothing is cancelled; a throw is swallowed.
     */
    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = ENSURE_SAME_THREAD, shift = At.Shift.AFTER),
            require = 0)
    private void killer560smod$routeBeforeAnswer(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || packet == null || packet.change() == null || !mc.isSameThread()) {
                return;
            }
            net.minecraft.world.entity.PositionMoveRotation target = net.minecraft.world.entity.PositionMoveRotation
                    .calculateAbsolute(net.minecraft.world.entity.PositionMoveRotation.of(mc.player), packet.change(),
                            packet.relatives());
            com.killer560.hub.autoroutes.RouteExecutor.beforeTeleportAnswer(mc.player, target.position());
        } catch (Throwable t) {
            killer560smod$rightClickTeleport$threw(t);
        }
    }

    @Inject(method = "handleMovePlayer", at = @At("RETURN"), require = 0)
    private void killer560smod$rightClickTeleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.isSameThread()) {
                RightClick.onTeleportAnswered(mc.player);
            }
        } catch (Throwable t) {
            killer560smod$rightClickTeleport$threw(t);
        }
    }

    @Unique
    private static void killer560smod$rightClickTeleport$threw(Throwable t) {
        ModLog.get("killer560smod-rightclick").error("[RightClick] teleport hook threw", t);
    }
}

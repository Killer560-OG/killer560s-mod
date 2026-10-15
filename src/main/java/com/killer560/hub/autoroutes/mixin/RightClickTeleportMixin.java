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

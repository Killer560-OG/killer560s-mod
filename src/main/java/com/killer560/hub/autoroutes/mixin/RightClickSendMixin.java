package com.killer560.hub.autoroutes.mixin;

import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.RightClick;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link RightClick}'s source of truth: the movement packets, right clicks on blocks and item uses as they LEAVE this
 * client, in the order the server receives them - vanilla's, every feature's and the sim's own. {@code public void
 * send(Packet<?>)} on {@code ClientCommonPacketListenerImpl} (javap 26.1.2 and 26.2, the target {@code OutboundBreakMixin}
 * and {@code SmoothTpOutboundMixin} use). Read-only, at HEAD, client thread only; a throw is swallowed so the packet goes.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class RightClickSendMixin {

    @Inject(method = "send", at = @At("HEAD"), require = 0)
    private void killer560smod$rightClickTap(Packet<?> packet, CallbackInfo ci) {
        try {
            if (packet instanceof ServerboundMovePlayerPacket || packet instanceof ServerboundUseItemOnPacket
                    || packet instanceof ServerboundUseItemPacket) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null || !mc.isSameThread()) {
                    return;
                }
                if (packet instanceof ServerboundMovePlayerPacket move) {
                    RightClick.onMove(mc.player, move);
                } else if (packet instanceof ServerboundUseItemOnPacket useOn) {
                    RightClick.onUseOn(mc.player, useOn);
                } else {
                    RightClick.onUse(mc.player, (ServerboundUseItemPacket) packet);
                }
                if (!(packet instanceof ServerboundMovePlayerPacket)) {
                    // A use of ours as it leaves the client: what a sceptre BAT node counts its first downward uses by (the sim's
                    // callback answers the game mode's calls at the head, so those are not seen). Was in BlockClickGapSendMixin.
                    com.killer560.hub.autoroutes.AwaitEvents.onUseSent();
                    // His own right click mid-route (a manual etherwarp): its teleport is not a server correction.
                    com.killer560.hub.autoroutes.RouteExecutor.onUseSent();
                }
            }
        } catch (Throwable t) {
            killer560smod$rightClickTap$threw(t);
        }
    }

    @Unique
    private static void killer560smod$rightClickTap$threw(Throwable t) {
        ModLog.get("killer560smod-rightclick").error("[RightClick] send tap threw - the packet still went", t);
    }
}

package com.killer560.hub.autoroutes.mixin;

import com.killer560.hub.util.BlockClickGap;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link BlockClickGap}'s source of truth: the right click on a block as it LEAVES this client, which is what Hypixel
 * receives. It used to be fed from the RETURN of {@code MultiPlayerGameMode.useItemOn}, which the dungeon sim's own client
 * callback never reaches for an ability item (Fabric's UseBlockCallback answers SUCCESS at the method's head, sends the
 * packet itself and skips RETURN), so a plain block click with an Aspect of the Void was invisible to the hold in the sim
 * while the sim server still dropped the use after it (testkit 1015-sim-clickgap, 2026-10-15). {@code public void
 * send(Packet<?>)} on {@code ClientCommonPacketListenerImpl} (javap 26.1.2, the target {@code OutboundBreakMixin} and
 * {@code SmoothTpOutboundMixin} use). Read-only, at HEAD, client thread only; a throw is swallowed so the packet goes.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class BlockClickGapSendMixin {

    @Inject(method = "send", at = @At("HEAD"), require = 0)
    private void killer560smod$blockClickGap(Packet<?> packet, CallbackInfo ci) {
        try {
            // A use of ours as it leaves the client (a held key's block click, or a plain item use): what a sceptre BAT
            // node counts its first downward uses by. Not the game mode's calls - the sim's callback answers those at the head.
            if (packet instanceof ServerboundUseItemOnPacket || packet instanceof net.minecraft.network.protocol.game.ServerboundUseItemPacket) {
                com.killer560.hub.autoroutes.AwaitEvents.onUseSent();
            }
            if (packet instanceof ServerboundUseItemOnPacket useOn) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null && mc.isSameThread()) {
                    BlockClickGap.onBlockClick(mc.player.getItemInHand(useOn.getHand()), useOn.getHitResult().getBlockPos());
                }
            }
        } catch (Throwable t) {
            killer560smod$blockClickGap$threw(t);
        }
    }

    @Unique
    private static void killer560smod$blockClickGap$threw(Throwable t) {
        ModLog.get("killer560smod-blockclickgap").error("[BlockClickGap] send tap threw - the packet still went", t);
    }
}

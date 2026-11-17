package com.killer560.hub.roomsim.mixin;

import com.killer560.hub.roomsim.SimClassAbilities;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The sim's class keys (simclasses-1116): on Hypixel, in a dungeon, Drop ({@code DROP_ITEM}) is the class ultimate and
 * Drop Stack ({@code DROP_ALL_ITEMS}) the class ability, and nothing is dropped. {@code public void
 * handlePlayerAction(ServerboundPlayerActionPacket)} - entered first on the network thread (which reschedules it onto
 * the server thread) and then on the server thread, the only one acted on.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SimDropActionMixin {

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void killer560smod$simClassKey(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        ServerboundPlayerActionPacket.Action action = packet.getAction();
        if (action != ServerboundPlayerActionPacket.Action.DROP_ITEM
                && action != ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS) {
            return;
        }
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (self.player != null && self.player.level().getServer().isSameThread()
                && SimClassAbilities.onDrop(self.player, action == ServerboundPlayerActionPacket.Action.DROP_ITEM)) {
            ci.cancel();
        }
    }
}

package com.killer560.hub.roomsim.mixin;

import com.killer560.hub.roomsim.SimAbilities;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The sim's server takes the look a use packet arrives under - the last movement packet's - before vanilla snaps the
 * player to the use packet's own rotation ({@code absSnapRotationTo}, javap 26.1.2), because Hypixel's 1.8 server traces
 * the use's pairing ray along that heard look ({@code SimAbilities.takenAsClickHalf}, util/RightClick). {@code public void
 * handleUseItem(ServerboundUseItemPacket)} - javap on the 26.1.2 and 26.2 merged jars, the same on both. Entered first on
 * the network thread (which reschedules it) and then on the server thread; only the second is read. Read-only.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SimUseLookMixin {

    @Inject(method = "handleUseItem", at = @At("HEAD"))
    private void killer560smod$simHeardLook(ServerboundUseItemPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (self.player != null && self.player.level().getServer().isSameThread()) {
            SimAbilities.noteUseArrives(self.player);
        }
    }
}

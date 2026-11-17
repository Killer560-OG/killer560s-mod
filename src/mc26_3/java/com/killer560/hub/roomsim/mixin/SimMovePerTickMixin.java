package com.killer560.hub.roomsim.mixin;

import com.killer560.hub.roomsim.SimState;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The sim's server takes more than one position a client tick, as Hypixel does (simbosswarpkick-1116). 26.3's
 * {@code handleMovePlayer} reads {@code receivedPositionThisTick} (set by a position, cleared only by the client's tick
 * end; javap 26.3, offsets 62/81) and kicks a second position with "Invalid move player packet received". 26.2 has no such
 * field, so its copy of this class is empty. The NaN / non-finite look check before it is untouched.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SimMovePerTickMixin {

    @ModifyExpressionValue(method = "handleMovePlayer",
            at = @At(value = "FIELD", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;receivedPositionThisTick:Z",
                    opcode = org.objectweb.asm.Opcodes.GETFIELD))
    private boolean killer560smod$simManyPositions(boolean seen) {
        return seen && !SimState.allowExtraPosition();
    }
}

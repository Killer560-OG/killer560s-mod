package com.killer560.hub.simonsays.mixin;

import com.killer560.hub.simonsays.SimonSaysFeature;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands Simon Says every block the server sets, in arrival order, before it is applied (flake-ss-1120): after a client
 * freeze the whole backlog is applied before the next tick, and a lantern that lit and went dark inside it was never
 * seen by the per-tick poll. {@code ClientLevel.setServerVerifiedBlockState(BlockPos, BlockState, int)} is public void
 * with that shape on 26.2 and 26.3 (javap), called for single block updates and section updates alike.
 */
@Mixin(ClientLevel.class)
public abstract class SimonSaysGridMixin {

    @Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"))
    private void killer560smod$simonGrid(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        try {
            SimonSaysFeature.onServerBlock((ClientLevel) (Object) this, pos, state);
        } catch (RuntimeException e) {
            // a throw on the packet path disconnects him
        }
    }
}

package com.killer560.hub.dungeonbreaker.mixin;

import com.killer560.hub.dungeonbreaker.DungeonBreakerFeature;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The BreakerGuard hook on the vanilla method invoked the instant a player starts mining a block. */
@Mixin(MultiPlayerGameMode.class)
public abstract class DungeonBreakerMixin {

    /** The guard: a Dungeon Breaker never starts a dig on a Fairy room block, or on a secret with "Don't break secrets"
     *  on. At HEAD so nothing at all is sent for that click. */
    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void killer560smod$breakerGuard(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (DungeonBreakerFeature.shouldBlockStart(pos)) {
            cir.setReturnValue(false);
        }
    }
}

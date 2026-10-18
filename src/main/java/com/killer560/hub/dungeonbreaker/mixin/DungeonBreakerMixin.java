package com.killer560.hub.dungeonbreaker.mixin;

import com.killer560.hub.dungeonbreaker.DungeonBreakerFeature;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Injects at the TAIL of the one real vanilla method invoked the instant a player starts mining a block
 *  - by this point the real mine-start packet has already been sent, so this only ever adds a client-side
 *  visual prediction on top, never anything sent to the server itself. See
 *  {@link DungeonBreakerFeature}'s own class doc for the real mechanic this is built on. */
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

    @Inject(method = "startDestroyBlock", at = @At("TAIL"))
    private void killer560smod$zeroPingBreak(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        DungeonBreakerFeature.onStartDestroyBlock(pos);
    }
}

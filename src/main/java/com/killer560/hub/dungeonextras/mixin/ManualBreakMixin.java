package com.killer560.hub.dungeonextras.mixin;

import com.killer560.hub.dungeonextras.ManualBreakMonitor;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Counts what vanilla does when killer560 breaks by hand, so the aura's rate can be compared with his own
 * (2026-09-24: "I swear me holding break breaks faster than the auto breaker").
 * <p>
 * Injected at TAIL so the real call has already happened and its return value is known - this only ever READS.
 * Nothing here sends a packet, cancels anything, or changes what vanilla did.
 * <p>
 * There WAS a second hook on continueDestroyBlock. It never fired - the signature is not what it was written
 * against - and it is gone rather than left in place looking like coverage, which is how the Experimentation
 * Table shipped unprotected. It is no loss: with a Dungeon Breaker every block goes instantly, so vanilla never
 * reaches the continue path, and startDestroyBlock alone gives the whole count.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class ManualBreakMixin {

    @Inject(method = "startDestroyBlock", at = @At("TAIL"))
    private void killer560smod$countManualStart(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        ManualBreakMonitor.onStartDestroyBlock(pos, Boolean.TRUE.equals(cir.getReturnValue()));
    }

}

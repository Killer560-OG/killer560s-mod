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
 * There WAS a second hook on continueDestroyBlock, and it never fired. I first put that down to a wrong
 * signature; javap against the 26.1.2 mapped jar says otherwise - {@code public boolean
 * continueDestroyBlock(BlockPos, Direction)} is exactly what it was written against. It never fired because the
 * game never calls it here: with a Dungeon Breaker every block goes instantly through startDestroyBlock, so
 * vanilla has no partially-mined block to continue. Removing it was still right, but for that reason and not the
 * one first recorded here.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class ManualBreakMixin {

    @Inject(method = "startDestroyBlock", at = @At("TAIL"))
    private void killer560smod$countManualStart(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        ManualBreakMonitor.onStartDestroyBlock(pos, Boolean.TRUE.equals(cir.getReturnValue()));
    }

}

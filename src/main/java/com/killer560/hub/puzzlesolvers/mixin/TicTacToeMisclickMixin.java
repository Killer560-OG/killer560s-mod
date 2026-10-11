package com.killer560.hub.puzzlesolvers.mixin;

import com.killer560.hub.puzzlesolvers.TicTacToeSolverFeature;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tic Tac Toe "Prevent Misclicks" (killer560, 2026-10-09). Same hook as {@code SimonSaysMisclickMixin}: every
 * right-click on a block goes through {@code MultiPlayerGameMode#useItemOn}, and failing it at HEAD sends nothing.
 * {@link TicTacToeSolverFeature#shouldBlockClick} answers true only for one of the nine board squares that is not the
 * highlighted one, so every other block in the game is untouched.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class TicTacToeMisclickMixin {

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void killer560smod$tttBlockMisclick(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult,
                                                CallbackInfoReturnable<InteractionResult> cir) {
        if (TicTacToeSolverFeature.shouldBlockClick(hitResult.getBlockPos(), player.isShiftKeyDown())) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}

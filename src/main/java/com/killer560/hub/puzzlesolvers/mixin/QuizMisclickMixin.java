package com.killer560.hub.puzzlesolvers.mixin;

import com.killer560.hub.puzzlesolvers.QuizSolverFeature;
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
 * Quiz "Block Wrong Answers" (2026-10-25). Same hook as {@code TicTacToeMisclickMixin}: every right-click on a block goes
 * through {@code MultiPlayerGameMode#useItemOn}, and failing it at HEAD sends nothing.
 * {@link QuizSolverFeature#shouldBlockClick} answers true only next to a wrong answer pillar once the right one is known.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class QuizMisclickMixin {

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void killer560smod$quizBlockWrongAnswer(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult,
                                                    CallbackInfoReturnable<InteractionResult> cir) {
        if (QuizSolverFeature.shouldBlockClick(hitResult.getBlockPos(), player.isShiftKeyDown())) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}

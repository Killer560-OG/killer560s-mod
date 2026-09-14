package com.killer560.hub.puzzlesolvers.mixin;

import com.killer560.hub.puzzlesolvers.WaterSolverFeature;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Injects at the TAIL of the same real vanilla right-click-a-block method Boulder Solver's mixin uses -
 *  by this point the real interact packet has already been sent, so this only ever records which lever
 *  time has been consumed, never anything sent to the server itself. Only counts a click whose real
 *  result was {@code SUCCESS} - a non-SUCCESS result means the click either didn't register server-side
 *  or was a real Hypixel double-fire onto the off hand (a known real quirk), neither of which should be
 *  counted as a real lever flick. See {@link WaterSolverFeature}'s own class doc for the real puzzle this
 *  is built on. */
@Mixin(MultiPlayerGameMode.class)
public abstract class WaterSolverMixin {

    @Inject(method = "useItemOn", at = @At("TAIL"))
    private void killer560smod$onUseItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult,
                                            CallbackInfoReturnable<InteractionResult> cir) {
        if (cir.getReturnValue() == InteractionResult.SUCCESS) {
            WaterSolverFeature.onLeverClick(hitResult.getBlockPos());
        }
    }
}

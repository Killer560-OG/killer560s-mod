package com.killer560.hub.puzzlesolvers.mixin;

import com.killer560.hub.puzzlesolvers.BoulderSolverFeature;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Injects at the TAIL of the one real vanilla method invoked whenever the player right-clicks a block -
 *  by this point the real interact packet has already been sent, so this only ever removes a solved
 *  position from the solver's own tracked list, never anything sent to the server itself. See
 *  {@link BoulderSolverFeature}'s own class doc for the real puzzle this is built on. */
@Mixin(MultiPlayerGameMode.class)
public abstract class BoulderSolverMixin {

    @Inject(method = "useItemOn", at = @At("TAIL"))
    private void killer560smod$onUseItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult,
                                            CallbackInfoReturnable<InteractionResult> cir) {
        BoulderSolverFeature.onPlayerInteract(hitResult.getBlockPos());
    }
}

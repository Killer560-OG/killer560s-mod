package com.killer560.hub.simonsays.mixin;

import com.killer560.hub.simonsays.SimonSaysFeature;
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
 * "Prevent Misclicks" - killer560's own request. {@code MultiPlayerGameMode#useItemOn} is the one real
 * method every right-click-a-block interaction in the whole game funnels through, including this mod's
 * OWN Trigger Bot/Auto Solve clicks ({@code SimonSaysFeature} calls it directly) - so injecting here
 * catches a real player misclick without needing a separate "is this a real click or the bot's own"
 * distinction: {@link SimonSaysFeature#shouldBlockClick} only ever returns true for a position that
 * ISN'T the currently-correct target, and the bot never intentionally clicks a wrong one, so this can
 * never block the bot's own clicks by construction.
 * <p>
 * Deliberately narrow-scoped to avoid the real risk a Mixin on this specific method carries (it's
 * called for EVERY block interaction in the game, not just Simon Says): the HEAD-injected check bails
 * immediately unless the exact target position is one of the 16 real Simon Says grid button
 * coordinates, which only ever matters during an actively-tracked device.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class SimonSaysMisclickMixin {

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void killer560smod$blockMisclick(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult,
                                              CallbackInfoReturnable<InteractionResult> cir) {
        if (SimonSaysFeature.shouldBlockClick(hitResult.getBlockPos(), player.isShiftKeyDown())) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}

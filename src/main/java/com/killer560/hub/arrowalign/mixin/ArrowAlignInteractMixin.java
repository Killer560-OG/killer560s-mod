package com.killer560.hub.arrowalign.mixin;

import com.killer560.hub.arrowalign.ArrowAlignFeature;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Arrow Align's Prevent Misclicks + real-click tracking. In 26.1.2 {@code MultiPlayerGameMode#interact(Player, Entity,
 * EntityHitResult, InteractionHand)} is the single entity right-click path (the old interactAt was merged into it; it
 * sends the interact packet then runs {@code Player#interactOn}) - the entity counterpart of the {@code useItemOn}
 * hook {@code SimonSaysMisclickMixin} uses. Bails immediately for anything that isn't a tracked arrow frame.
 * <p>
 * A blocked click returns {@code SUCCESS_SERVER} rather than FAIL: {@code Minecraft#startUseItem} only returns on a
 * {@code Success} (and only swings for SwingSource.CLIENT), whereas FAIL/PASS fall through to {@code useItem} with
 * the held item - so this cancels the whole right-click with no packet, no swing and no item use, the same effect
 * as Odin/NoammAddons cancelling their interact event.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class ArrowAlignInteractMixin {

    @Inject(method = "interact(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/EntityHitResult;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$arrowAlignInteract(Player player, Entity entity, EntityHitResult hitResult,
                                                 InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (ArrowAlignFeature.onInteractAttempt(entity, player.isShiftKeyDown())) {
            cir.setReturnValue(InteractionResult.SUCCESS_SERVER);
        }
    }
}

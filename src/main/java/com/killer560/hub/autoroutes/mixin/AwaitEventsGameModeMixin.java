package com.killer560.hub.autoroutes.mixin;

import com.killer560.hub.autoroutes.AwaitEvents;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Auto Routes' await: our own right click on a block. {@code public InteractionResult useItemOn(LocalPlayer,
 * InteractionHand, BlockHitResult)} (javap-verified on the 26.1.2 and 26.2 merged jars) is where vanilla's right click
 * and every block click in this mod go. Read-only, at RETURN: a FAIL there is a click refused client-side (edit mode's
 * breaker picking, a block outside the world border) and sent no packet, so it is not counted.
 * <p>
 * Also {@code useItem(Player, InteractionHand)} and {@code attack(Player, Entity)} (same jars): a use or hit of ours,
 * which is what a crypt or prince kill is attributed to.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class AwaitEventsGameModeMixin {

    @Inject(method = "useItemOn", at = @At("RETURN"), require = 0)
    private void killer560smod$awaitBlockClick(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
                                               CallbackInfoReturnable<InteractionResult> cir) {
        try {
            if (cir.getReturnValue() != InteractionResult.FAIL && hit != null) {
                AwaitEvents.onLocalBlockClick(hit.getBlockPos());
            }
        } catch (RuntimeException ignored) {
            // never breaks a click
        }
    }

    @Inject(method = "useItem", at = @At("HEAD"), require = 0)
    private void killer560smod$awaitUse(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        AwaitEvents.onLocalWeaponUse();
    }

    @Inject(method = "attack", at = @At("HEAD"), require = 0)
    private void killer560smod$awaitAttack(Player player, Entity target, CallbackInfo ci) {
        AwaitEvents.onLocalWeaponUse();
    }
}

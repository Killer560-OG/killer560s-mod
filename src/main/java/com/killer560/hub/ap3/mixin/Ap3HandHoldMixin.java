package com.killer560.hub.ap3.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A use of his that reaches {@code public InteractionResult useItem(Player, InteractionHand)} outside
 * {@code Minecraft.startUseItem} (held off there by {@code LeftClickEtherwarpMinecraftMixin}) waits too while AP3's
 * held-back act has this tick ({@code Ap3Executor.holdsHisHand}, ap3holddig-1110): his use_item ahead of the act's hotbar
 * change in one server tick is GrimAC PacketOrderE, and the act would wait for good under a use every tick.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class Ap3HandHoldMixin {

    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private void killer560smod$ap3HoldsUse(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (com.killer560.hub.ap3.Ap3Executor.holdsHisHand()) cir.setReturnValue(InteractionResult.PASS);
    }
}

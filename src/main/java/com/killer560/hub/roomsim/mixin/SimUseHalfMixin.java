package com.killer560.hub.roomsim.mixin;

import com.killer560.hub.roomsim.SimAbilities;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla's right click in the sim sends both halves Hypixel gets from it. On Hypixel, a right click on stone with a
 * sword or shovel (every ability item is one) PASSes on the client, so {@code Minecraft.startUseItem} sends the click
 * on the block and then the use, in the same tick, and the 1.8 server pairs them. The sim's client hook claims such a
 * click for its ability ({@code SimAbilities.onClientUse}, SUCCESS so vanilla predicts nothing for a reskinned block
 * item), which ended {@code startUseItem} after the block half; util/RightClick then held that click unpaired for good,
 * and every later warp whose heard look met a wall waited a tick (96-ar-chain after a crypt node's Hyperion click). When
 * the hook claimed the click, this hands {@code startUseItem} a PASS so it goes on to {@code gameMode.useItem} - the use
 * half - exactly as on Hypixel; the block half has already gone (Fabric sent it for the SUCCESS). Only vanilla's own right
 * click: a feature calling {@code gameMode.useItemOn} itself sends what it sends.
 * <p>
 * Target: {@code private void startUseItem()} calling {@code MultiPlayerGameMode.useItemOn(LocalPlayer, InteractionHand,
 * BlockHitResult)} - javap on the 26.1.2 and 26.2 merged jars, the same on both.
 */
@Mixin(Minecraft.class)
public abstract class SimUseHalfMixin {

    @WrapOperation(method = "startUseItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/"
            + "MultiPlayerGameMode;useItemOn(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/InteractionHand;"
            + "Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult killer560smod$simUseHalf(MultiPlayerGameMode gameMode, LocalPlayer player, InteractionHand hand,
                                                       BlockHitResult hit, Operation<InteractionResult> original) {
        int before = SimAbilities.blockClicksClaimed();
        InteractionResult result = original.call(gameMode, player, hand, hit);
        return SimAbilities.blockClicksClaimed() != before && result instanceof InteractionResult.Success
                ? InteractionResult.PASS : result;
    }
}

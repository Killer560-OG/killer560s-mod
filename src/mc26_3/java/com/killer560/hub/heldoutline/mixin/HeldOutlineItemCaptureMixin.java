package com.killer560.hub.heldoutline.mixin;

import com.killer560.hub.heldoutline.HeldOutlineRenderer;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.3 copy. 26.3 has no {@code ItemInHandRenderer.renderItem}: {@code FirstPersonHandsAndItemsRenderer.submitArmWithItem}
 * submits the hand's {@code ItemStackRenderState} (main or off hand, picked by the {@code InteractionHand} argument)
 * straight through {@code ItemStackRenderState.submit} (javap, 26.3). So this marks which hand is being drawn from
 * HEAD to every RETURN, and {@link HeldOutlineItemStateMixin} records the submit itself - at that method's HEAD, so it
 * sees the pose after every other mod's transforms (Held Item Transform injects just before the same submit call).
 * Read-only: the vanilla draw is untouched.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class HeldOutlineItemCaptureMixin {

    @Inject(method = "submitArmWithItem", at = @At("HEAD"), require = 0)
    private void killer560smod$heldOutlineArmBegin(PlayerRenderState player, FirstPersonHandsAndItemsRenderState state,
                                                   float frameInterp, float xRot, InteractionHand hand, float attack,
                                                   ItemStack itemStack, float inverseArmHeight, PoseStack poseStack,
                                                   SubmitNodeCollector collector, int light, CallbackInfo ci) {
        HeldOutlineRenderer.beginArm(hand == InteractionHand.MAIN_HAND);
    }

    @Inject(method = "submitArmWithItem", at = @At("RETURN"), require = 0)
    private void killer560smod$heldOutlineArmEnd(CallbackInfo ci) {
        HeldOutlineRenderer.endArm();
    }
}

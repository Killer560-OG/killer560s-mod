package com.killer560.hub.helditem.mixin;

import com.killer560.hub.helditem.HeldItemConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Held Item Transform hooks on the first-person hand renderer - see {@link HeldItemConfig}. 26.3 copy.
 *
 * <p>26.3 removed {@code ItemInHandRenderer}. Drawing moved to {@code FirstPersonHandsAndItemsRenderer} (reached as
 * {@code GameRenderer.firstPersonHandsAndItemsRenderer}) and works from render states; the equip state moved to
 * {@code FirstPersonHandsAndItems} ({@link HeldItemEquipMixin}). The hooks are the same ones, verified with javap on
 * the 26.3 merged jar:
 * <ul>
 * <li>{@code submitArmWithItem(PlayerRenderState, FirstPersonHandsAndItemsRenderState, float, float, InteractionHand,
 *     float attack, ItemStack, float inverseArmHeight, PoseStack, SubmitNodeCollector, int)} - offset right after its
 *     one {@code PoseStack.pushPose()}; rotation + scale right before each {@code ItemStackRenderState.submit(PoseStack,
 *     SubmitNodeCollector, int, int, int)} (two call sites, the charged crossbow and the general one, only one runs per
 *     call); swing progress via {@code attack}, still the third float parameter (local 6: the method passes it to
 *     {@code renderPlayerArm} as the swing).</li>
 * <li>{@code submitHandsWithItems} - the look-around sway is now {@code PoseStack.rotateDegrees(Axis, float)}, called
 *     exactly twice in that method, with (viewXRot - xBob) and (viewYRot - yBob); the angle is index 1.</li>
 * </ul>
 * The arm's side comes from {@code PlayerRenderState.avatarRenderState.mainArm} instead of the player entity.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class HeldItemInHandRendererMixin {

    @Inject(method = "submitArmWithItem",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", shift = At.Shift.AFTER),
            require = 0)
    private void killer560smod$heldItemOffset(PlayerRenderState player, FirstPersonHandsAndItemsRenderState state,
                                              float frameInterp, float xRot, InteractionHand hand, float attack,
                                              ItemStack itemStack, float inverseArmHeight, PoseStack poseStack,
                                              SubmitNodeCollector submitNodeCollector, int lightCoords,
                                              CallbackInfo ci) {
        if (!HeldItemConfig.isActive()) return;
        if (itemStack.isEmpty()) return;
        HeldItemConfig.HandTransform t = HeldItemConfig.getInstance().forHand(hand == InteractionHand.MAIN_HAND);
        if (t.x == 0f && t.y == 0f && t.z == 0f) return;
        float sign = killer560smod$armSign(player, hand);
        poseStack.translate(t.x * sign, t.y, t.z);
    }

    @Inject(method = "submitArmWithItem",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"),
            require = 0)
    private void killer560smod$heldItemRotateScale(PlayerRenderState player, FirstPersonHandsAndItemsRenderState state,
                                                   float frameInterp, float xRot, InteractionHand hand, float attack,
                                                   ItemStack itemStack, float inverseArmHeight, PoseStack poseStack,
                                                   SubmitNodeCollector submitNodeCollector, int lightCoords,
                                                   CallbackInfo ci) {
        if (!HeldItemConfig.isActive()) return;
        HeldItemConfig.HandTransform t = HeldItemConfig.getInstance().forHand(hand == InteractionHand.MAIN_HAND);
        float sign = killer560smod$armSign(player, hand);
        if (t.rotX != 0f) com.killer560.hub.compat.McRender.rotate(poseStack, Axis.XP.rotationDegrees(t.rotX));
        if (t.rotY != 0f) com.killer560.hub.compat.McRender.rotate(poseStack, Axis.YP.rotationDegrees(t.rotY * sign));
        if (t.rotZ != 0f) com.killer560.hub.compat.McRender.rotate(poseStack, Axis.ZP.rotationDegrees(t.rotZ * sign));
        if (t.scale != 1.0f) poseStack.scale(t.scale, t.scale, t.scale);
    }

    /** {@code attack} is the swing progress (0 = at rest); pinning it to 0 removes the swing arc/rotation. */
    @ModifyVariable(method = "submitArmWithItem", at = @At("HEAD"), ordinal = 2, argsOnly = true, require = 0)
    private float killer560smod$heldItemNoSwing(float attack) {
        if (!HeldItemConfig.isActive()) return attack;
        return HeldItemConfig.getInstance().isNoSwing() ? 0f : attack;
    }

    /** The two {@code PoseStack.rotateDegrees} calls in submitHandsWithItems are the look-around sway; zeroing their
     *  angle keeps the item still. */
    @ModifyArg(method = "submitHandsWithItems",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;rotateDegrees(Lcom/mojang/math/Axis;F)V"),
            index = 1, require = 0)
    private float killer560smod$heldItemNoSway(float degrees) {
        if (!HeldItemConfig.isActive()) return degrees;
        return HeldItemConfig.getInstance().isNoHandSway() ? 0f : degrees;
    }

    /** Swing Range (killer560, 2026-11-01): {@code swingArm(float, PoseStack, int, HumanoidArm)} makes exactly one
     *  {@code PoseStack.translate} (javap 26.2 and 26.3), the item's travel on a hit; it is scaled by the setting. A
     *  WrapOperation, so another mod hooking the same call still gets it. */
    @WrapOperation(method = "swingArm",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"),
            require = 0)
    private void killer560smod$swingRange(PoseStack poseStack, float x, float y, float z, Operation<Void> original) {
        float r = HeldItemConfig.isActive() ? HeldItemConfig.getInstance().getSwingRange() : 1f;
        if (r != 1f) {
            HeldItemConfig.swingRangeApplied++;
        }
        original.call(poseStack, x * r, y * r, z * r);
    }

    private static float killer560smod$armSign(PlayerRenderState player, InteractionHand hand) {
        HumanoidArm main = player.avatarRenderState != null && player.avatarRenderState.mainArm != null
                ? player.avatarRenderState.mainArm : HumanoidArm.RIGHT;
        HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? main : main.getOpposite();
        return arm == HumanoidArm.RIGHT ? 1f : -1f;
    }
}

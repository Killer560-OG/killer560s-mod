package com.killer560.hub.helditem.mixin;

import com.killer560.hub.helditem.HeldItemConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Held Item Transform hooks on the first-person hand renderer - see {@link HeldItemConfig}. Hook points
 *  ported from NoammAddons' 26.1.2 {@code MixinItemInHandRenderer} (Animations feature), verified against
 *  the 26.1.2 merged jar with javap:
 *  <ul>
 *  <li>{@code renderArmWithItem(AbstractClientPlayer, float, float, InteractionHand, float attack, ItemStack,
 *      float inverseArmHeight, PoseStack, SubmitNodeCollector, int)} - offset right after its
 *      {@code PoseStack.pushPose()}, rotation+scale right before each {@code renderItem(...)} submit
 *      (two call sites, only one runs per call), swing progress via the {@code attack} arg.</li>
 *  <li>{@code shouldInstantlyReplaceVisibleItem} + {@code tick} TAIL (main/off hand height fields) - equip.</li>
 *  <li>{@code renderHandsWithItems} - the two {@code Axis.rotationDegrees(F)} view-bob sway rotations.</li>
 *  </ul>
 *  Every injector is {@code require = 0}; every one starts with a single static boolean read. */
@Mixin(ItemInHandRenderer.class)
public abstract class HeldItemInHandRendererMixin {

    @Shadow private float mainHandHeight;
    @Shadow private float oMainHandHeight;
    @Shadow private float offHandHeight;
    @Shadow private float oOffHandHeight;

    @Inject(method = "renderArmWithItem",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", shift = At.Shift.AFTER),
            require = 0)
    private void killer560smod$heldItemOffset(AbstractClientPlayer player, float frameInterp, float xRot,
                                              InteractionHand hand, float attack, ItemStack itemStack,
                                              float inverseArmHeight, PoseStack poseStack,
                                              SubmitNodeCollector submitNodeCollector, int lightCoords,
                                              CallbackInfo ci) {
        if (!HeldItemConfig.isActive()) return;
        if (itemStack.isEmpty()) return;
        HeldItemConfig.HandTransform t = HeldItemConfig.getInstance().forHand(hand == InteractionHand.MAIN_HAND);
        if (t.x == 0f && t.y == 0f && t.z == 0f) return;
        float sign = killer560smod$armSign(player, hand);
        poseStack.translate(t.x * sign, t.y, t.z);
    }

    @Inject(method = "renderArmWithItem",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"),
            require = 0)
    private void killer560smod$heldItemRotateScale(AbstractClientPlayer player, float frameInterp, float xRot,
                                                   InteractionHand hand, float attack, ItemStack itemStack,
                                                   float inverseArmHeight, PoseStack poseStack,
                                                   SubmitNodeCollector submitNodeCollector, int lightCoords,
                                                   CallbackInfo ci) {
        if (!HeldItemConfig.isActive()) return;
        HeldItemConfig.HandTransform t = HeldItemConfig.getInstance().forHand(hand == InteractionHand.MAIN_HAND);
        float sign = killer560smod$armSign(player, hand);
        if (t.rotX != 0f) poseStack.mulPose(Axis.XP.rotationDegrees(t.rotX));
        if (t.rotY != 0f) poseStack.mulPose(Axis.YP.rotationDegrees(t.rotY * sign));
        if (t.rotZ != 0f) poseStack.mulPose(Axis.ZP.rotationDegrees(t.rotZ * sign));
        if (t.scale != 1.0f) poseStack.scale(t.scale, t.scale, t.scale);
    }

    /** {@code attack} is the swing progress (0 = at rest); pinning it to 0 removes the swing arc/rotation. */
    @ModifyVariable(method = "renderArmWithItem", at = @At("HEAD"), ordinal = 2, argsOnly = true, require = 0)
    private float killer560smod$heldItemNoSwing(float attack) {
        if (!HeldItemConfig.isActive()) return attack;
        return HeldItemConfig.getInstance().isNoSwing() ? 0f : attack;
    }

    @Inject(method = "shouldInstantlyReplaceVisibleItem", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$heldItemInstantReplace(ItemStack currentlyVisibleItem, ItemStack expectedItem,
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (!HeldItemConfig.isActive()) return;
        if (HeldItemConfig.getInstance().isNoEquip()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void killer560smod$heldItemNoEquip(CallbackInfo ci) {
        if (!HeldItemConfig.isActive()) return;
        if (!HeldItemConfig.getInstance().isNoEquip()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        // Vanilla drops the hands out of view while riding etc. (isHandsBusy) - keep that behavior.
        if (player == null || player.isHandsBusy()) return;
        mainHandHeight = 1f;
        oMainHandHeight = 1f;
        offHandHeight = 1f;
        oOffHandHeight = 1f;
    }

    /** The two {@code Axis.rotationDegrees} calls in renderHandsWithItems are the look-around sway
     *  ({@code (xRot - xBob) * 0.1F} and the yaw equivalent); zeroing their angle keeps the item still. */
    @ModifyArg(method = "renderHandsWithItems",
            at = @At(value = "INVOKE", target = "Lcom/mojang/math/Axis;rotationDegrees(F)Lorg/joml/Quaternionf;"),
            index = 0, require = 0)
    private float killer560smod$heldItemNoSway(float degrees) {
        if (!HeldItemConfig.isActive()) return degrees;
        return HeldItemConfig.getInstance().isNoHandSway() ? 0f : degrees;
    }

    private static float killer560smod$armSign(AbstractClientPlayer player, InteractionHand hand) {
        HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        return arm == HumanoidArm.RIGHT ? 1f : -1f;
    }
}

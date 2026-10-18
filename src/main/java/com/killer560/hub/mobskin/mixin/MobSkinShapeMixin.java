package com.killer560.hub.mobskin.mixin;

import com.killer560.hub.mobskin.MobSkinRender;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Your Width/Height/Thickness on your mob (Play as Mob). javap, 26.1.2 and 26.2 alike: {@code LivingEntityRenderer
 * .submit(LivingEntityRenderState, PoseStack, SubmitNodeCollector, CameraRenderState)} applies {@code state.scale},
 * {@code setupRotations}, the {@code (-1, -1, 1)} flip and then {@code this.scale(state, poseStack)} before the model
 * is drawn - right after that call is the same point {@code CosmeticsModelShapeMixin} stretches the player model at (the
 * TAIL of {@code AvatarRenderer.scale}). Only a state {@code MobSkinRender} marked carries a stretch.
 * {@code require = 0}: a wrong target only leaves your mob unstretched.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class MobSkinShapeMixin {

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
            + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;"
                    + "scale(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;)V", shift = At.Shift.AFTER),
            require = 0)
    private void killer560smod$mobShape(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                                        CameraRenderState camera, CallbackInfo ci) {
        MobSkinRender.applyShape(state, poseStack);
    }
}

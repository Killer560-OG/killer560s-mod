package com.killer560.hub.supporters.mixin;

import com.killer560.hub.supporters.CosmeticsStateMarker;
import com.killer560.hub.supporters.SupportersConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Width/Height/Thickness cosmetics (Cosmetics tab) - killer560: "allow me to change my players scale, width,
 * height, and thickness." {@code state.scale} (already multiplied by {@link SupportersScaleMixin} for the
 * relay-shared supporter scale) is a SINGLE float applied uniformly on every axis -
 * {@code AvatarRenderer.scale(AvatarRenderState, PoseStack)} is the pose-stack transform {@link
 * SupportersScaleMixin}'s own class doc already names as the method that reads {@code state.scale} back out
 * and calls vanilla's own {@code poseStack.scale(f, f, f)} - so an EXTRA, separate, non-uniform {@code
 * poseStack.scale(width, height, thickness)} right after that same TAIL is the only way to stretch one axis
 * without touching the others, since a single float can't express "wider but not taller."
 * <p>
 * Local player only. {@code scale(AvatarRenderState, PoseStack)} is never handed the entity itself (only the
 * per-frame render state), so {@link CosmeticsStateMarker} (attached by {@link AvatarRenderStateMarkerMixin})
 * tags each state object as "local player or not" earlier in the SAME render pass, at {@code
 * extractRenderState} - the exact spot {@code SupportersScaleMixin}/{@code Ap3ThirdPersonMixin} already hook
 * for an identical reason, with the same javap-verified descriptor those two classes' own docs cite.
 * <p>
 * These sliders have NO relay equivalent (the supporters contract only ever carries a single {@code scale}
 * float) so they are NEVER shared - only you ever see your own width/height/thickness stretch, on your own
 * F5 model. Pure {@code PoseStack} scale, exactly like {@code state.scale}'s own vanilla application above -
 * never the entity's hitbox/dimensions/collision/reach, so this cannot be seen by the server or change
 * combat. {@code require = 0} on both injects: a wrong target here only leaves the sliders doing nothing,
 * never a crash.
 */
@Mixin(AvatarRenderer.class)
public abstract class CosmeticsModelShapeMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;"
            + "Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"), require = 0)
    private void killer560smod$tagLocalPlayer(Avatar entity, AvatarRenderState state, float partialTick,
                                              CallbackInfo ci) {
        if (state instanceof CosmeticsStateMarker marker) {
            marker.killer560smod$setLocalPlayer(entity != null && entity == Minecraft.getInstance().player);
        }
    }

    @Inject(method = "scale(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At("TAIL"), require = 0)
    private void killer560smod$applyShape(AvatarRenderState state, PoseStack poseStack, CallbackInfo ci) {
        if (!(state instanceof CosmeticsStateMarker marker) || !marker.killer560smod$isLocalPlayer()) {
            return;
        }
        SupportersConfig cfg = SupportersConfig.getInstance();
        float width = cfg.getModelWidth();
        float height = cfg.getModelHeight();
        float thickness = cfg.getModelThickness();
        if (width != 1.0f || height != 1.0f || thickness != 1.0f) {
            poseStack.scale(width, height, thickness);
        }
    }
}

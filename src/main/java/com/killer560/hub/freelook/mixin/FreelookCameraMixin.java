package com.killer560.hub.freelook.mixin;

import com.killer560.hub.freelook.FreelookFeature;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Freelook's pan: the camera's yaw and pitch, and nothing else. {@code private void alignWithEntity(float)} on
 * {@code net.minecraft.client.Camera} is identical on 26.1.2 and 26.2 (javap of both mapped jars, 2026-10-18): it calls
 * {@code setRotation(entity.getViewYRot(pt), entity.getViewXRot(pt))} (once on the minecart path, once otherwise), and only
 * then mirrors the rotation for the front view and pulls the camera back along it with the wall check. So answering those
 * two calls with the pan camera's angles makes vanilla orbit the camera around him, collision included.
 * <p>
 * Only the CALL inside the camera is answered: {@code LocalPlayer.getViewYRot} itself is unchanged, so the crosshair pick
 * ({@code getViewVector}), movement and every packet still use his real rotation. The original is still called, so any
 * other mixin on it runs as before. One copy serves both versions.
 */
@Mixin(Camera.class)
public abstract class FreelookCameraMixin {

    @WrapOperation(method = "alignWithEntity",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"))
    private float killer560smod$freelookYaw(Entity entity, float partialTick, Operation<Float> original) {
        float vanilla = original.call(entity, partialTick);
        if (FreelookFeature.isPanning() && entity == Minecraft.getInstance().player) {
            return FreelookFeature.cameraYaw();
        }
        return vanilla;
    }

    @WrapOperation(method = "alignWithEntity",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"))
    private float killer560smod$freelookPitch(Entity entity, float partialTick, Operation<Float> original) {
        float vanilla = original.call(entity, partialTick);
        if (FreelookFeature.isPanning() && entity == Minecraft.getInstance().player) {
            return FreelookFeature.cameraPitch();
        }
        return vanilla;
    }
}

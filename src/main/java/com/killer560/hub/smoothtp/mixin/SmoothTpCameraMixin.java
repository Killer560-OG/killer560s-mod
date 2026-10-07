package com.killer560.hub.smoothtp.mixin;

import com.killer560.hub.smoothtp.SmoothTeleport;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Smooth Teleport's only write: where the camera is drawn from, once a frame.
 *
 * <p>{@code private void alignWithEntity(float)} on {@code net.minecraft.client.Camera} is identical on 26.1.2 and 26.2
 * (javap of both mapped jars, 2026-10-07): {@code Camera.update(DeltaTracker)} calls it, then builds the FOV, the view
 * matrix and the cull frustum from the {@code position} field. So a position set at its TAIL is the one the whole
 * frame renders from (chunks, entities, culling, every mod renderer that reads the camera) - and nothing else: the
 * crosshair pick casts from the player's eye ({@code LocalPlayer.raycastHitResult} -> {@code Entity.getEyePosition}),
 * and rotation is left exactly as vanilla (and ViewFreeze) set it. {@code setPosition(Vec3)} and {@code position()}
 * are on both versions with these signatures. A throw here is logged and the vanilla position kept.
 */
@Mixin(Camera.class)
public abstract class SmoothTpCameraMixin {

    @Shadow
    public abstract Vec3 position();

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Shadow
    public abstract Entity entity();

    @Shadow
    public abstract boolean isDetached();

    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void killer560smod$smoothTeleport(float partialTicks, CallbackInfo ci) {
        try {
            Vec3 vanilla = position();
            Vec3 drawn = SmoothTeleport.cameraPosition(entity(), isDetached(), vanilla);
            if (drawn != vanilla) {
                setPosition(drawn);
            }
        } catch (RuntimeException e) {
            killer560smod$smoothTp$threw(e);
        }
    }

    @Unique
    private static void killer560smod$smoothTp$threw(RuntimeException e) {
        ModLog.get("killer560smod-smoothtp").error("[SmoothTeleport] camera hook threw - vanilla camera kept", e);
    }
}

package com.killer560.hub.zoom.mixin;

import com.killer560.hub.zoom.ZoomFeature;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Zoom's Smooth Glide: {@code MouseHandler.turnPlayer} runs the mouse deltas through vanilla's cinematic-camera smoother
 * ({@code SmoothDouble}, which keeps the view moving after the mouse stops and eases it out) when {@code Options.smoothCamera}
 * is true, and resets that smoother every frame it is false (javap 26.3, 2026-11-26). While zoom is held with Smooth Glide on
 * we make the read answer true, so the vanilla smoother is the only one; releasing zoom sends the read back to the real value
 * and vanilla resets the smoother on the next frame. The smoothed deltas still go into {@code Entity.turn} (pitch clamp, every
 * other mod's turn handling) unchanged.
 */
@Mixin(MouseHandler.class)
public abstract class ZoomGlideMixin {

    @WrapOperation(method = "turnPlayer",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/Options;smoothCamera:Z"), require = 0)
    private boolean killer560smod$glide(Options options, Operation<Boolean> original) {
        return ZoomFeature.glideActive() || original.call(options);
    }
}

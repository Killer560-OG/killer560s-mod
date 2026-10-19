package com.killer560.hub.zoom.mixin;

import com.killer560.hub.zoom.ZoomFeature;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Narrows the field of view Zoom asks for.
 *
 * <p>The hook is {@code Camera.calculateFov(F)F} (private, the same on 26.1.2 and 26.2, javap 2026-10-19). {@code Camera.update}
 * stores its result in the {@code fov} field and hands that field straight to {@code setupPerspective} (the level's projection)
 * and {@code createProjectionMatrixForCulling}. {@code Camera.getFov()} only reads the field back and is NOT on the render path,
 * which is why the first version (injecting into {@code getFov}) did nothing. Hooking only here also avoids a double zoom:
 * {@code getFov} now returns the already-zoomed value. {@code calculateHudFov} (the hand) is left alone on purpose.
 */
@Mixin(Camera.class)
public abstract class ZoomCameraMixin {

    @Inject(method = "calculateFov(F)F", at = @At("RETURN"), cancellable = true, require = 0)
    private void killer560smod$zoomFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
        // Panorama screenshots return a fixed 90 and must stay 90.
        if (((Camera) (Object) this).isPanoramicMode()) {
            return;
        }
        cir.setReturnValue(ZoomFeature.applyFov(cir.getReturnValue()));
    }
}

package com.killer560.hub.zoom.mixin;

import com.killer560.hub.zoom.ZoomFeature;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Narrows the field of view Zoom asks for. {@code Camera.getFov()} is public and returns a float on 26.1.2 and 26.2 (checked with javap). */
@Mixin(Camera.class)
public abstract class ZoomCameraMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true, require = 0)
    private void killer560smod$zoomFov(CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(ZoomFeature.applyFov(cir.getReturnValue()));
    }
}

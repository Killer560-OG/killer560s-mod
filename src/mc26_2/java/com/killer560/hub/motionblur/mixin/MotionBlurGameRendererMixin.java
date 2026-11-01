package com.killer560.hub.motionblur.mixin;

import com.killer560.hub.motionblur.MotionBlurRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Two hook points inside {@code GameRenderer.render(DeltaTracker, boolean)} (javap-verified 26.1.2, each
 *  target invoked exactly once in that method):
 *  <ul>
 *  <li>before {@code FogRenderer.endFrame()} - the world, hand, entity outlines and vanilla post effect are
 *      done, the GUI has not been drawn yet (Blur GUI off);</li>
 *  <li>before {@code CrossFrameResourcePool.endFrame()} - the GUI has been drawn too (Blur GUI on).</li>
 *  </ul>
 *  No shadows, so a missing target only means that hook never fires. */
@Mixin(GameRenderer.class)
public abstract class MotionBlurGameRendererMixin {

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"),
            require = 0)
    private void killer560smod$motionBlurBeforeGui(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        MotionBlurRenderer.onFrame(false, renderLevel);
    }

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/resource/CrossFrameResourcePool;endFrame()V"),
            require = 0)
    private void killer560smod$motionBlurAfterGui(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        MotionBlurRenderer.onFrame(true, renderLevel);
    }
}

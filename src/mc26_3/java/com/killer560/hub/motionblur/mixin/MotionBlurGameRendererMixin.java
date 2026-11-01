package com.killer560.hub.motionblur.mixin;

import com.killer560.hub.motionblur.MotionBlurRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 26.3 copy. 26.3's {@code GameRenderer.render()} takes no arguments (26.2: {@code render(DeltaTracker, boolean
 *  renderLevel)}); the flag it used to be passed is {@code gameRenderState().shouldRenderLevel}, which {@code render()}
 *  reads itself (javap, 26.3). The two hook points are the same, each invoked exactly once in {@code render()}:
 *  <ul>
 *  <li>before {@code FogRenderer.endFrame()} - the world, hand, entity outlines and vanilla post effect are
 *      done, the GUI has not been drawn yet (Blur GUI off);</li>
 *  <li>before {@code CrossFrameResourcePool.endFrame()} - the GUI has been drawn too (Blur GUI on).</li>
 *  </ul>
 *  No shadows, so a missing target only means that hook never fires. */
@Mixin(GameRenderer.class)
public abstract class MotionBlurGameRendererMixin {

    @Inject(method = "render()V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"),
            require = 0)
    private void killer560smod$motionBlurBeforeGui(CallbackInfo ci) {
        MotionBlurRenderer.onFrame(false, ((GameRenderer) (Object) this).gameRenderState().shouldRenderLevel);
    }

    @Inject(method = "render()V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/resource/CrossFrameResourcePool;endFrame()V"),
            require = 0)
    private void killer560smod$motionBlurAfterGui(CallbackInfo ci) {
        MotionBlurRenderer.onFrame(true, ((GameRenderer) (Object) this).gameRenderState().shouldRenderLevel);
    }
}

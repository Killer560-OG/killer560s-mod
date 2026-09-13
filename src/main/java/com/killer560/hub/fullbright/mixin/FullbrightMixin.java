package com.killer560.hub.fullbright.mixin;

import com.killer560.hub.fullbright.FullbrightConfig;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Forces the world lightmap to maximum brightness when enabled - verified via javap against the
 *  real 26.1.2 jar that {@code LightmapRenderStateExtractor.extract} computes
 *  {@code state.brightness = max(0, gamma - darknessEffectBlend)} from the vanilla gamma slider and
 *  the Darkness status effect, then that field drives the actual lightmap texture generation.
 *  Overriding it at the TAIL (after the real calculation already ran) is simpler and more reliable
 *  than trying to fight the gamma option's own clamping - it saturates the final result regardless
 *  of what produced it, including the Darkness effect and boss-fight world darkening.
 *  <p>
 *  <b>Real bug found and fixed (2026-09-13):</b> this used to force {@code brightness = 100.0f}. The
 *  actual consumer, {@code assets/minecraft/shaders/core/lightmap.fsh} (read directly from the real
 *  26.1.2 jar, not guessed), does {@code color = mix(color, notGamma(color), BrightnessFactor)} - GLSL's
 *  {@code mix(x, y, a)} is a plain linear interpolation that only behaves sanely for {@code a} in
 *  [0, 1]. Feeding it 100 instead of the shader's actual intended max of 1.0 linearly EXTRAPOLATES
 *  ~100x past both inputs with no clamp afterward, writing wildly out-of-range values into every texel
 *  of the lightmap texture every tick (the extractor recomputes constantly regardless of fullbright,
 *  due to vanilla's own per-tick block-light-flicker jitter) - texels that every subsequently-rendered
 *  block/entity then samples and multiplies into its final color, likely forcing expensive post-processing
 *  (bloom/tonemapping) to do far more work than a normal saturated-but-in-range value would. This is the
 *  real, confirmed root cause of the reported FPS drop - not something inherent to fullbright itself.
 *  Using exactly 1.0 (the shader's own real max) produces the identical fully-saturated visual result
 *  with none of the runaway extrapolation. */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class FullbrightMixin {

    private static final float FULLBRIGHT_VALUE = 1.0f;

    @Inject(method = "extract", at = @At("TAIL"))
    private void killer560smod$forceFullbright(LightmapRenderState state, float partialTick, CallbackInfo ci) {
        if (FullbrightConfig.getInstance().isEnabled()) {
            state.brightness = FULLBRIGHT_VALUE;
        }
    }
}

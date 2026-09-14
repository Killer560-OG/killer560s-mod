package com.killer560.hub.fullbright.mixin;

import com.killer560.hub.fullbright.FullbrightConfig;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Forces the world lightmap to maximum brightness when enabled - verified via javap against the real
 *  26.1.2 jar that {@code LightmapRenderStateExtractor.extract} populates a {@link LightmapRenderState}
 *  that the real shader ({@code assets/minecraft/shaders/core/lightmap.fsh}, read directly from the
 *  shipped jar, not guessed) turns into the final lightmap texture.
 *  <p>
 *  <b>Round 1 fix (2026-09-13):</b> this used to force {@code state.brightness = 100.0f}. The shader
 *  does {@code color = mix(color, notGamma(color), BrightnessFactor)} - GLSL's {@code mix(x, y, a)} is
 *  a plain lerp only sane for {@code a} in [0,1]; 100 extrapolated ~100x past both inputs with no clamp
 *  after, writing wild values into the lightmap texture every tick and very likely forcing expensive
 *  post-processing (bloom/tonemapping) to do far more work - the real cause of the reported FPS drop.
 *  Dropping it to 1.0 (the shader's own real max) fixed the FPS drop, confirmed by killer560's report.
 *  <p>
 *  <b>Round 2 fix (2026-09-13, same day) - the actual fullbright effect was gone too:</b> killer560's
 *  live test showed 1.0 barely brightened anything - "a hair brighter than not having it on." Re-reading
 *  the shader explains why: {@code color} starts as {@code max(AmbientColor, nightVisionColor)}, and in
 *  genuine darkness (a real dungeon corridor, no torches) that's near-black BEFORE the brightness mix
 *  ever runs. {@code notGamma(black)} is degenerate (divides by a ~zero max component), so no in-range
 *  {@code BrightnessFactor} can brighten true darkness through that path at all - the old brightness=100
 *  "worked" only by accident, via the exact runaway extrapolation that caused the FPS drop, brute-forcing
 *  a near-zero value up to visible levels by sheer multiplication.
 *  <p>
 *  The real, safe fix: brighten the actual INPUT to that formula instead of trying to force the output
 *  through a curve that can't do it. Overriding {@code state.ambientColor} to solid white gives every
 *  pixel a real, non-zero light floor before the brightness curve (and the Darkness effect's own
 *  subtraction, and boss-fight world darkening) ever apply - {@code notGamma} then pushes that
 *  comfortably back toward white on its own, genuinely lighting up total darkness with brightness left
 *  at a safe 1.0, no extrapolation involved anywhere. */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class FullbrightMixin {

    private static final float FULLBRIGHT_VALUE = 1.0f;
    private static final Vector3f FULLBRIGHT_AMBIENT = new Vector3f(1.0f, 1.0f, 1.0f);

    @Inject(method = "extract", at = @At("TAIL"))
    private void killer560smod$forceFullbright(LightmapRenderState state, float partialTick, CallbackInfo ci) {
        if (FullbrightConfig.getInstance().isEnabled()) {
            state.brightness = FULLBRIGHT_VALUE;
            state.ambientColor = FULLBRIGHT_AMBIENT;
        }
    }
}

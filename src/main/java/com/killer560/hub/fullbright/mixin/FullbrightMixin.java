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
 *  of what produced it, including the Darkness effect and boss-fight world darkening. */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class FullbrightMixin {

    private static final float FULLBRIGHT_VALUE = 100.0f;

    @Inject(method = "extract", at = @At("TAIL"))
    private void killer560smod$forceFullbright(LightmapRenderState state, float partialTick, CallbackInfo ci) {
        if (FullbrightConfig.getInstance().isEnabled()) {
            state.brightness = FULLBRIGHT_VALUE;
        }
    }
}

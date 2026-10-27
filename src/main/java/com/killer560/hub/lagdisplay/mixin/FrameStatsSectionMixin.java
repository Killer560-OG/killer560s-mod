package com.killer560.hub.lagdisplay.mixin;

import com.killer560.hub.lagdisplay.FrameStats;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A section rebuilt synchronously on the render thread ({@code RenderSection.compileSync}, both versions, argument type
 *  differs: {@code RenderRegionCache} / {@code RenderSectionRegion}), for {@link FrameStats} (stutter-1026). Calls on
 *  other threads are ignored by FrameStats. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class FrameStatsSectionMixin {

    @Inject(method = "compileSync", at = @At("HEAD"), require = 0)
    private void killer560smod$syncBegin(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.begin(FrameStats.CHUNK_SYNC);
        }
    }

    @Inject(method = "compileSync", at = @At("RETURN"), require = 0)
    private void killer560smod$syncEnd(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.end(FrameStats.CHUNK_SYNC);
        }
    }
}

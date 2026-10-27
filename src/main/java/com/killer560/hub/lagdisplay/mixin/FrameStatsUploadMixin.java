package com.killer560.hub.lagdisplay.mixin;

import com.killer560.hub.lagdisplay.FrameStats;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Terrain buffers uploaded to the GPU on the render thread: {@code uploadGlobalGeomBuffersToGPU()} on 26.1.2,
 *  {@code uploadTerrainBuffersToGpu()} on 26.2 (javap), for {@link FrameStats} (stutter-1026). */
@Mixin(SectionRenderDispatcher.class)
public abstract class FrameStatsUploadMixin {

    @Inject(method = {"uploadGlobalGeomBuffersToGPU", "uploadTerrainBuffersToGpu"}, at = @At("HEAD"), require = 0)
    private void killer560smod$uploadBegin(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.begin(FrameStats.CHUNK_UPLOAD);
        }
    }

    @Inject(method = {"uploadGlobalGeomBuffersToGPU", "uploadTerrainBuffersToGpu"}, at = @At("RETURN"), require = 0)
    private void killer560smod$uploadEnd(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.end(FrameStats.CHUNK_UPLOAD);
        }
    }
}

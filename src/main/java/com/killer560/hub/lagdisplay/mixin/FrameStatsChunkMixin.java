package com.killer560.hub.lagdisplay.mixin;

import com.killer560.hub.lagdisplay.FrameStats;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chunk-section scheduling on the render thread, for {@link FrameStats} (stutter-1026). {@code private void
 * compileSections(...)} is on both versions (javap: {@code Camera} on 26.1.2, {@code CameraRenderState} on 26.2); the
 * handler takes only the CallbackInfo so one mixin fits both. It holds the synchronous rebuilds (nearby sections, or any
 * with Chunk Builder "Fully Blocking"). Another renderer (Sodium) replaces it: the stutter line then says "n/a".
 */
@Mixin(LevelRenderer.class)
public abstract class FrameStatsChunkMixin {

    @Inject(method = "compileSections", at = @At("HEAD"), require = 0)
    private void killer560smod$scheduleBegin(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.begin(FrameStats.CHUNK_SCHEDULE);
        }
    }

    @Inject(method = "compileSections", at = @At("RETURN"), require = 0)
    private void killer560smod$scheduleEnd(CallbackInfo ci) {
        if (FrameStats.active) {
            FrameStats.end(FrameStats.CHUNK_SCHEDULE);
        }
    }
}

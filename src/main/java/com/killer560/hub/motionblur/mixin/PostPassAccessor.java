package com.killer560.hub.motionblur.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** javap-verified 26.1.2: {@code private final Map<String, GpuBuffer> customUniforms} (a mutable HashMap,
 *  filled in the constructor with USAGE_UNIFORM-only buffers) and {@code private final RenderPipeline pipeline}. */
@Mixin(PostPass.class)
public interface PostPassAccessor {

    @Accessor("customUniforms")
    Map<String, GpuBuffer> killer560smod$getCustomUniforms();

    @Accessor("pipeline")
    RenderPipeline killer560smod$getPipeline();
}

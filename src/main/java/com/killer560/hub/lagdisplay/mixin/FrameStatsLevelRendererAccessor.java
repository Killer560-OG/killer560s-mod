package com.killer560.hub.lagdisplay.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** {@code private SectionRenderDispatcher sectionRenderDispatcher} (same field on 26.1.2 and 26.2, javap; the getter's name
 *  differs), read only on a stutter frame for the compile queue's length. */
@Mixin(LevelRenderer.class)
public interface FrameStatsLevelRendererAccessor {

    @Accessor("sectionRenderDispatcher")
    SectionRenderDispatcher killer560smod$sectionRenderDispatcher();
}

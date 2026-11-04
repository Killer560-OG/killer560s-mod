package com.killer560.hub.miningqol.mixin;

import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Draws every sprite's current images into the atlas again (the load-time step), without a resource reload. */
@Mixin(TextureAtlas.class)
public interface TextureAtlasRedrawInvoker {

    @Invoker("uploadInitialContents")
    void killer560smod$redraw();
}

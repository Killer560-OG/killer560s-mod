package com.killer560.hub.miningqol.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Dark Stone swaps a sprite's mip images for darkened copies (and back). Same field name on 26.2 and 26.3. */
@Mixin(SpriteContents.class)
public interface SpriteContentsMipsAccessor {

    @Accessor("byMipLevel")
    NativeImage[] killer560smod$getMips();

    @Accessor("byMipLevel")
    void killer560smod$setMips(NativeImage[] mips);
}

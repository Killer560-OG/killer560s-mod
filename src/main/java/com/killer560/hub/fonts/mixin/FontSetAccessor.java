package com.killer560.hub.fonts.mixin;

import com.mojang.blaze3d.font.GlyphProvider;
import net.minecraft.client.gui.font.FontSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(FontSet.class)
public interface FontSetAccessor {
    @Accessor("allProviders")
    List<GlyphProvider.Conditional> killer560smod$allProviders();
}

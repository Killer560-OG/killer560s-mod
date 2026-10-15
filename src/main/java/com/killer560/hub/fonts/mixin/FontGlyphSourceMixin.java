package com.killer560.hub.fonts.mixin;

import com.killer560.hub.fonts.CustomFonts;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.network.chat.FontDescription;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The Font setting's one hook (see {@link CustomFonts}). {@code Font.getGlyphSource(FontDescription)} is private and is
 * where every glyph a Font measures or prepares is looked up - widths through the StringSplitter's width lambda,
 * drawing through {@code getGlyph(int, Style)} (javap, identical in 26.1.2 and 26.2). A ModifyReturnValue keeps the
 * per-glyph path allocation-free: no CallbackInfo, one static read when no font is chosen.
 */
@Mixin(Font.class)
public abstract class FontGlyphSourceMixin {

    @ModifyReturnValue(method = "getGlyphSource(Lnet/minecraft/network/chat/FontDescription;)Lnet/minecraft/client/gui/GlyphSource;",
            at = @At("RETURN"), require = 0)
    private GlyphSource killer560smod$customFont(GlyphSource original,
                                                @Local(argsOnly = true) FontDescription description) {
        return CustomFonts.replace((Font) (Object) this, original, description);
    }
}

package com.killer560.hub.fonts.mixin;

import com.killer560.hub.fonts.CustomFonts;
import net.minecraft.client.gui.font.FontManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The custom font set borrows the default font's providers as its fallback, and {@code FontManager.apply} closes them
 * on every resource reload, so the set is dropped first and rebuilt on the next tick from the new ones. A font option
 * change (Force Unicode Font, Japanese glyph variants) only marks it for a rebuild. javap: both methods identical in
 * 26.1.2 and 26.2.
 */
@Mixin(FontManager.class)
public abstract class FontManagerReloadMixin {

    @Inject(method = "apply", at = @At("HEAD"), require = 0)
    private void killer560smod$dropCustomFont(CallbackInfo ci) {
        CustomFonts.dropForReload();
    }

    @Inject(method = "updateOptions", at = @At("TAIL"), require = 0)
    private void killer560smod$fontOptions(CallbackInfo ci) {
        CustomFonts.markDirty();
    }
}

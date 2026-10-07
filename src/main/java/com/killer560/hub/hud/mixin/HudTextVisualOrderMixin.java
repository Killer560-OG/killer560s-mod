package com.killer560.hub.hud.mixin;

import com.killer560.hub.hud.HudTextCache;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inside the mod's own HUD layers only ({@link HudTextCache#active()}), draws a String with its cached visual order
 * instead of recomputing it. The method body is, in 26.1.2 and 26.2 alike (javap):
 * {@code if (s == null) return; text(font, Language.getInstance().getVisualOrder(FormattedText.of(s)), x, y, color,
 * shadow);} - this does the same with the sequence {@link HudTextCache} keeps for the same text and language.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class HudTextVisualOrderMixin {

    @Inject(method = "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V", at = @At("HEAD"), cancellable = true)
    private void killer560smod$cachedVisualOrder(Font font, String text, int x, int y, int color, boolean shadow,
                                                  CallbackInfo ci) {
        if (text == null || !HudTextCache.active()) {
            return;
        }
        ((GuiGraphicsExtractor) (Object) this).text(font, HudTextCache.visualOrder(text), x, y, color, shadow);
        ci.cancel();
    }
}

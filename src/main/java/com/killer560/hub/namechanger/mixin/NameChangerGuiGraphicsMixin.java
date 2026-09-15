package com.killer560.hub.namechanger.mixin;

import com.killer560.hub.namechanger.NameReplacer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * GUI text is only turned into glyphs later ({@code GuiRenderer.prepareText}), after the widget pass, so an edit box's
 * text is tagged at submission time instead. Verified with javap: {@code text(Font,String,IIIZ)} and
 * {@code text(Font,Component,IIIZ)} both delegate to {@code text(Font,FormattedCharSequence,IIIZ)}, which builds the
 * {@code GuiTextRenderState} - and it is the only text overload EditBox uses.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class NameChangerGuiGraphicsMixin {

    @ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private FormattedCharSequence killer560smod$nameChangerMarkEditBoxText(FormattedCharSequence text) {
        return NameReplacer.markIfSuppressed(text);
    }
}

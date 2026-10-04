package com.killer560.hub.namechanger.mixin;

import com.killer560.hub.namechanger.NameReplacer;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Widget labels (buttons, string widgets) do not go through {@code GuiGraphicsExtractor.text}: they are
 * submitted by {@code GuiGraphicsExtractor$RenderingTextCollector.accept(TextAlignment,int,int,Parameters,
 * FormattedCharSequence)}, the only other place a {@code GuiTextRenderState} is built (javap, 26.1.2 and 26.2).
 * Marking here too is what lets {@link NameReplacer#beginScreen} cover a menu's buttons as well as its text.
 */
@Mixin(targets = "net.minecraft.client.gui.GuiGraphicsExtractor$RenderingTextCollector")
public abstract class NameChangerTextCollectorMixin {

    @ModifyVariable(method = "accept(Lnet/minecraft/client/gui/TextAlignment;IILnet/minecraft/client/gui/ActiveTextCollector$Parameters;Lnet/minecraft/util/FormattedCharSequence;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private FormattedCharSequence killer560smod$nameChangerMarkWidgetText(FormattedCharSequence text) {
        return NameReplacer.markIfSuppressed(text);
    }
}

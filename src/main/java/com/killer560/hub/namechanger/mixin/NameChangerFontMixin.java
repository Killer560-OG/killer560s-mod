package com.killer560.hub.namechanger.mixin;

import com.killer560.hub.namechanger.NameReplacer;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Name Changer's central hooks - the same two chokepoints quoi's {@code FontMixin} uses. Verified with javap against
 * the 26.1.2 merged jar: all GUI text (chat, tab list, scoreboard, lore/tooltips, titles) is prepared through
 * {@code GuiTextRenderState.ensurePrepared -> Font.prepareText(FormattedCharSequence,FFIZZI)}, world text (name tags)
 * through {@code Font.drawInBatch -> prepareText(...)}, and centering/backgrounds use {@code Font.width(...)}.
 * Every injection is {@code require = 0}: a wrong target only disables the feature, it never crashes the game.
 */
@Mixin(Font.class)
public abstract class NameChangerFontMixin {

    @ModifyVariable(
            method = "prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private FormattedCharSequence killer560smod$nameChangerPrepareSeq(FormattedCharSequence text) {
        return NameReplacer.replace(text);
    }

    @ModifyVariable(
            method = "prepareText(Ljava/lang/String;FFIZI)Lnet/minecraft/client/gui/Font$PreparedText;",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private String killer560smod$nameChangerPrepareString(String text) {
        return NameReplacer.replace(text);
    }

    @ModifyVariable(method = "width(Lnet/minecraft/util/FormattedCharSequence;)I",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private FormattedCharSequence killer560smod$nameChangerWidthSeq(FormattedCharSequence text) {
        return NameReplacer.replace(text);
    }

    @ModifyVariable(method = "width(Ljava/lang/String;)I",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private String killer560smod$nameChangerWidthString(String text) {
        return NameReplacer.replace(text);
    }

    @Inject(method = "width(Lnet/minecraft/network/chat/FormattedText;)I",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$nameChangerWidthText(FormattedText text, CallbackInfoReturnable<Integer> cir) {
        FormattedCharSequence replaced = NameReplacer.replaceForWidth(text);
        if (replaced != null) {
            cir.setReturnValue(((Font) (Object) this).width(replaced));
        }
    }
}

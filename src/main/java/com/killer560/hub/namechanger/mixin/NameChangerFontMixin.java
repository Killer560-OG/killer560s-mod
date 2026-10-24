package com.killer560.hub.namechanger.mixin;

import com.killer560.hub.namechanger.NameReplacer;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSink;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
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
        // Then animated names (Wave/Pulse) recolored for this frame - see NameAnimation. Drawing only, never width.
        return com.killer560.hub.namechanger.NameAnimation.animate(NameReplacer.replace(text));
    }

    @ModifyVariable(
            method = "prepareText(Ljava/lang/String;FFIZI)Lnet/minecraft/client/gui/Font$PreparedText;",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private String killer560smod$nameChangerPrepareString(String text) {
        return NameReplacer.replace(text);
    }

    /**
     * {@code prepareText(String, ...)} decodes its string with {@code StringDecomposer.iterateFormatted(String, Style,
     * FormattedCharSink)} (javap, 26.1.2 and 26.2), which misreads the {@code §x§R§R§G§G§B§B} escape a name fade is
     * written in (see {@code NameColor.buildFade}): a faded name drawn through a String path came out in random legacy
     * colors. {@code NameColor.iterateFormatted} is the same decoder plus that escape; for any other string it is
     * glyph-for-glyph what vanilla produces. {@code NameAnimation.iterate} is that decoder with animated names recolored.
     */
    @Redirect(
            method = "prepareText(Ljava/lang/String;FFIZI)Lnet/minecraft/client/gui/Font$PreparedText;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/StringDecomposer;iterateFormatted("
                    + "Ljava/lang/String;Lnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z"),
            require = 0)
    private boolean killer560smod$hexAwareDecode(String text, Style style, FormattedCharSink sink) {
        return com.killer560.hub.namechanger.NameAnimation.iterate(text, style, sink);
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

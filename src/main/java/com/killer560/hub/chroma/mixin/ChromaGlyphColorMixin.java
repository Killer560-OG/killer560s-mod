package com.killer560.hub.chroma.mixin;

import com.killer560.hub.util.ChromaWave;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.network.chat.TextColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Per-glyph chroma: when a glyph's style carries a {@link ChromaWave.Spec}'s color, the font gets the color of the
 * waterfall at that glyph's own position instead.
 *
 * <p>{@code Font$PreparedTextBuilder.accept(int, Style, BakedGlyph)} reads {@code style.getColor()} once per glyph,
 * turns it into the glyph color with {@code getTextColor} (keeping the text's alpha) and derives the shadow from that,
 * while {@code x}/{@code y} still hold the glyph's own origin (javap, identical in 26.1.2 and 26.2). GUI text is
 * prepared there at its GUI coordinates every frame ({@code GuiTextRenderState.ensurePrepared}), so the color
 * animates without any component being rebuilt, and the lines of one tooltip share one coordinate space.
 *
 * <p>A {@code @ModifyExpressionValue} on the {@code getColor()} result rather than a cancellable {@code @Inject}: this
 * runs for every glyph the game draws, and a callback object per glyph is the allocation storm the chroma rewrite was
 * asked to avoid. It was a {@code @Redirect} until 2026-11-01; a modify-value composes with other mods hooking the same
 * call (a second redirect on one call fails, and a require-1 one in another mod crashes the game). The non-chroma cost
 * is one identity-map check that returns at once while no spec exists.
 */
@Mixin(targets = "net.minecraft.client.gui.Font$PreparedTextBuilder")
public abstract class ChromaGlyphColorMixin {

    @Shadow
    private float x;

    @Shadow
    private float y;

    @ModifyExpressionValue(method = "accept(ILnet/minecraft/network/chat/Style;Lnet/minecraft/client/gui/font/glyphs/BakedGlyph;)Z",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/chat/Style;getColor()Lnet/minecraft/network/chat/TextColor;"),
            require = 0)
    private TextColor killer560smod$chromaGlyph(TextColor color) {
        ChromaWave.Spec spec = ChromaWave.specFor(color);
        return spec == null ? color : spec.textColorAt(this.x, this.y);
    }
}

package com.killer560.hub.gui.mixin;

import com.killer560.hub.gui.MenuAnim;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The mod menu's fade (opening, closing, a tooltip appearing): while {@link MenuAnim#fade} is below 1, every solid fill,
 * gradient and line of text drawn has its alpha multiplied by it. There is no global colour multiplier in 26.1.2
 * (docs/LESSONS-GUI.md), and the menu's rows, cards and vanilla widgets all end in these three calls ({@code fill(IIIII)}
 * and {@code outline} go through the pipeline {@code fill}; every {@code text} overload ends in the
 * {@code FormattedCharSequence} one; javap 26.1.2 and 26.2, same descriptors). Outside a fade it is one float compare.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class MenuFadeMixin {

    @ModifyVariable(method = "fill(Lcom/mojang/blaze3d/pipeline/RenderPipeline;IIIII)V", at = @At("HEAD"), argsOnly = true,
            ordinal = 4)
    private int killer560smod$fadeFill(int color) {
        return MenuAnim.fade >= 1f ? color : MenuAnim.faded(color);
    }

    @ModifyVariable(method = "fillGradient(IIIIII)V", at = @At("HEAD"), argsOnly = true, ordinal = 4)
    private int killer560smod$fadeGradientTop(int color) {
        return MenuAnim.fade >= 1f ? color : MenuAnim.faded(color);
    }

    @ModifyVariable(method = "fillGradient(IIIIII)V", at = @At("HEAD"), argsOnly = true, ordinal = 5)
    private int killer560smod$fadeGradientBottom(int color) {
        return MenuAnim.fade >= 1f ? color : MenuAnim.faded(color);
    }

    @ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 2)
    private int killer560smod$fadeText(int color) {
        return MenuAnim.fade >= 1f ? color : MenuAnim.faded(color);
    }
}

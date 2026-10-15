package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.mainmenu.MainMenuLook;
import com.killer560.hub.mainmenu.MainMenuTheme;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * No drop shadow under the title screen's widget text on a light main menu theme. Every button label (and the splash
 * text) goes through {@code RenderingTextCollector.accept}, which builds its GuiTextRenderState with
 * {@code dropShadow = true} (javap, 26.1.2 and 26.2), and a dark shadow under dark text smears it on a light panel - the
 * reason the mod menu's own Light theme draws without shadows (docs/LESSONS-GUI.md).
 */
@Mixin(targets = "net.minecraft.client.gui.GuiGraphicsExtractor$RenderingTextCollector")
public abstract class MainMenuLabelShadowMixin {

    @ModifyArg(method = "accept(Lnet/minecraft/client/gui/TextAlignment;IILnet/minecraft/client/gui/ActiveTextCollector$Parameters;Lnet/minecraft/util/FormattedCharSequence;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/state/gui/GuiTextRenderState;<init>(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;Lorg/joml/Matrix3x2fc;IIIIZZLnet/minecraft/client/gui/navigation/ScreenRectangle;)V"),
            index = 7, require = 0)
    private boolean killer560smod$noShadowOnLightMainMenu(boolean dropShadow) {
        if (!dropShadow) {
            return false;
        }
        try {
            if (MainMenuTheme.activeOnTitleScreen()
                    && MenuStyle.luminance(MainMenuLook.current().panelColour()) > 0.5f) {
                return false;
            }
        } catch (Throwable t) {
            MainMenuTheme.fail("label shadow", t);
        }
        return dropShadow;
    }
}

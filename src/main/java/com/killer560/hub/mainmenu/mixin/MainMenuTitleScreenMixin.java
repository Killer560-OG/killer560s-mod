package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.LogoRenderer;
import net.minecraft.client.gui.screens.Screen;
import com.killer560.hub.mainmenu.MainMenuTitleLayout;
import net.minecraft.client.gui.screens.TitleScreen;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;


/** The title screen's version line ("Minecraft 26.1.2 (71 Mods)") is not drawn while the themed main menu is on
 *  (killer560, 2026-10-15: "Remove the my mod version thing and the minecraft version and the copyright message in the
 *  bottom right"). It is the only {@code text(Font,String,III)} call in TitleScreen.extractRenderState (javap, 26.1.2 and
 *  26.2); with the theme off it is drawn exactly as vanilla draws it. */
@Mixin(TitleScreen.class)
public abstract class MainMenuTitleScreenMixin {

    @WrapOperation(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"),
            require = 0)
    private void killer560smod$hideVersionLine(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color,
            Operation<Void> original) {
        if (MainMenuTheme.active()) return;
        original.call(graphics, font, text, x, y, color);
    }

    /** On a window too short for the themed column (854x480 at GUI scale 2), the logo is drawn smaller from its top
     *  center so it stays above the panel ({@link MainMenuTitleLayout#logoScale}); otherwise exactly as vanilla. */
    @WrapOperation(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/LogoRenderer;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IF)V"),
            require = 0)
    private void killer560smod$fitLogo(LogoRenderer logo, GuiGraphicsExtractor graphics, int width, float alpha,
            Operation<Void> original) {
        float s = MainMenuTitleLayout.logoScale((Screen) (Object) this);
        if (s >= 1f) {
            original.call(logo, graphics, width, alpha);
            return;
        }
        graphics.pose().pushMatrix();
        graphics.pose().translate(width / 2f, 0f);
        graphics.pose().scale(s, s);
        graphics.pose().translate(-width / 2f, 0f);
        original.call(logo, graphics, width, alpha);
        graphics.pose().popMatrix();
    }
}

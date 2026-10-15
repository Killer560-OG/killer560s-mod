package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;


/** The title screen's version line ("Minecraft 26.1.2 (71 Mods)") is not drawn while the themed main menu is on
 *  (killer560, 2026-10-15: "Remove the my mod version thing and the minecraft version and the copyright message in the
 *  bottom right"). It is the only {@code text(Font,String,III)} call in TitleScreen.extractRenderState (javap, 26.1.2 and
 *  26.2); with the theme off it is drawn exactly as vanilla draws it. */
@Mixin(TitleScreen.class)
public abstract class MainMenuTitleScreenMixin {

    @Redirect(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"),
            require = 0)
    private void killer560smod$hideVersionLine(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color) {
        if (MainMenuTheme.active()) {
            return;
        }
        graphics.text(font, text, x, y, color);
    }
}

package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.PlainTextButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** The bottom-right "Copyright Mojang AB. Do not distribute!" link is a PlainTextButton drawn in plain
 *  white; on the title screen it becomes dim grey, light orange while hovered. Clicking still opens the
 *  credits exactly as before (only the colour argument changes). */
@Mixin(PlainTextButton.class)
public abstract class MainMenuCopyrightMixin {

    @ModifyArg(method = "extractContents(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"),
            index = 4, require = 0)
    private int killer560smod$themedCopyrightColor(int color) {
        if (!MainMenuTheme.activeOnTitleScreen()) {
            return color;
        }
        try {
            boolean hovered = ((AbstractWidget) (Object) this).isHoveredOrFocused();
            return MainMenuTheme.argb(hovered ? MainMenuTheme.LIGHT_ORANGE : MainMenuTheme.DIM, (color >>> 24) & 0xFF);
        } catch (Throwable t) {
            MainMenuTheme.fail("copyright", t);
            return color;
        }
    }
}

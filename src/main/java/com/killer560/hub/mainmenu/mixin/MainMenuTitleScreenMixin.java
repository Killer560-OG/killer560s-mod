package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Title screen footer text: the bottom-left version line ("Minecraft 26.1.2/Fabric (Modded)") goes from
 *  white to the dim theme grey, and a "Killer560's Mod [version]" line in orange is drawn just above it.
 *  The only {@code text(Font,String,III)} call in TitleScreen.extractRenderState is that version line
 *  (javap, 26.1.2); its colour arg carries the fade-in alpha, which is reused for the extra line. */
@Mixin(TitleScreen.class)
public abstract class MainMenuTitleScreenMixin {

    @Unique
    private int killer560smod$footerAlpha = 255;

    @ModifyArg(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"),
            index = 4, require = 0)
    private int killer560smod$themedVersionColor(int color) {
        if (!MainMenuTheme.active()) {
            return color;
        }
        killer560smod$footerAlpha = (color >>> 24) & 0xFF;
        return MainMenuTheme.argb(MainMenuTheme.DIM, killer560smod$footerAlpha);
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V", shift = At.Shift.AFTER),
            require = 0)
    private void killer560smod$modTag(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!MainMenuTheme.active()) {
            return;
        }
        try {
            int alpha = killer560smod$footerAlpha;
            if (alpha <= 4) {
                return;
            }
            TitleScreen self = (TitleScreen) (Object) this;
            graphics.text(Minecraft.getInstance().font, MainMenuTheme.modTag(), 2, self.height - 20,
                    MainMenuTheme.argb(MainMenuTheme.ORANGE, alpha));
        } catch (Throwable t) {
            MainMenuTheme.fail("mod tag", t);
        }
    }
}

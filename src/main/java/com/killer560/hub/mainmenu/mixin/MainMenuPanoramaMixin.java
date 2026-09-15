package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Swaps the rotating panorama for the themed background, on the title screen only. TitleScreen doesn't
 *  override {@code extractPanorama} (javap, 26.1.2) - it calls Screen's from {@code extractRenderState} -
 *  so the hook sits on Screen with an instanceof check. Other menus' panorama (Options, world select...)
 *  is untouched. */
@Mixin(Screen.class)
public abstract class MainMenuPanoramaMixin {

    @Inject(method = "extractPanorama(Lnet/minecraft/client/gui/GuiGraphicsExtractor;F)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$themedTitleBackground(GuiGraphicsExtractor graphics, float partialTick, CallbackInfo ci) {
        Object self = this;
        if (!(self instanceof TitleScreen screen) || !MainMenuTheme.active()) {
            return;
        }
        try {
            MainMenuTheme.drawBackground(graphics, screen.width, screen.height);
            ci.cancel();
        } catch (Throwable t) {
            MainMenuTheme.fail("background", t);
        }
    }
}

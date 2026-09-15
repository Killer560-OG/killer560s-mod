package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import com.killer560.hub.mainmenu.MenuChrome;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Swaps the rotating panorama for the themed background. TitleScreen doesn't override {@code extractPanorama}
 *  (javap, 26.1.2) - it calls Screen's from {@code extractRenderState} - so the hook sits on Screen.
 *  <ul>
 *  <li>TitleScreen: unchanged behaviour - full {@link MainMenuTheme#drawBackground} whenever the theme is
 *  active.</li>
 *  <li>Every other screen: only while {@link MainMenuTheme#activeOnMenuBackground()} (Themed Other Menus on,
 *  not a container screen, no world loaded) - the toned-down {@link MenuChrome#drawMenuBackground}.
 *  Callers in 26.1.2: Screen.extractBackground (only when level == null), GenericMessageScreen and
 *  LevelLoadingScreen.extractBackground (which call it unconditionally - the level check here keeps
 *  in-world uses vanilla).</li>
 *  </ul> */
@Mixin(Screen.class)
public abstract class MainMenuPanoramaMixin {

    @Inject(method = "extractPanorama(Lnet/minecraft/client/gui/GuiGraphicsExtractor;F)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$themedTitleBackground(GuiGraphicsExtractor graphics, float partialTick, CallbackInfo ci) {
        Object self = this;
        if (self instanceof TitleScreen screen) {
            if (!MainMenuTheme.active()) {
                return;
            }
            try {
                MainMenuTheme.drawBackground(graphics, screen.width, screen.height);
                ci.cancel();
            } catch (Throwable t) {
                MainMenuTheme.fail("background", t);
            }
            return;
        }
        // activeOnMenus() rather than activeOnMenuBackground(): the panorama is only ever drawn where no world
        // view is shown (Screen.extractBackground gates on level == null itself), so the loading/message screens
        // that call it after the level exists (joining a server, loading a world) stay themed instead of
        // flipping back to the vanilla panorama mid-load.
        if (!MainMenuTheme.activeOnMenus()) {
            return;
        }
        try {
            Screen screen = (Screen) self;
            MenuChrome.drawMenuBackground(graphics, screen.width, screen.height);
            ci.cancel();
        } catch (Throwable t) {
            MainMenuTheme.fail("menu background", t);
        }
    }
}

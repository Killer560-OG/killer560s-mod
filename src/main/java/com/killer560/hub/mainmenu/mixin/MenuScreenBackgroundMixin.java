package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import com.killer560.hub.mainmenu.MenuChrome;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The two layers vanilla stacks on top of the panorama for every out-of-world non-title menu
 * (Screen.extractBackground, 26.1.2: extractPanorama -> extractBlurredBackground -> extractMenuBackground;
 * GenericMessageScreen / LevelLoadingScreen call the same three). Both only change while
 * {@link MainMenuTheme#activeOnMenuBackground()} - in a world the blur and inworld tint stay vanilla.
 * <ul>
 * <li>{@code extractBlurredBackground(G)} just calls {@code GuiGraphicsExtractor.blurBeforeThisStratum()},
 * which blurs strata BEFORE the background stratum - i.e. the 3D panorama pass. The themed background is
 * flat quads in the background stratum itself (never blurred), and with the panorama replaced there is
 * nothing underneath worth blurring, so the full-screen blur pass is skipped outright.</li>
 * <li>{@code extractMenuBackground(G,IIII)} tiles menu_background.png (a dark tint) - replaced by a
 * translucent warm-dark fill. Also reached from CreateWorldScreen/StatsScreen's
 * {@code extractMenuBackground(G)} (below their tab header). WinScreen and TabButton override the 5-arg
 * method without calling super, so credits and tab buttons are untouched here.</li>
 * </ul>
 */
@Mixin(Screen.class)
public abstract class MenuScreenBackgroundMixin {

    @Inject(method = "extractBlurredBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$skipBlurWhenThemed(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (MainMenuTheme.activeOnMenuBackground()) {
            ci.cancel();
        }
    }

    @Inject(method = "extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIII)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$themedMenuTint(GuiGraphicsExtractor graphics, int x, int y, int width, int height, CallbackInfo ci) {
        if (!MainMenuTheme.activeOnMenuBackground()) {
            return;
        }
        try {
            MenuChrome.drawMenuTint(graphics, x, y, width, height);
            ci.cancel();
        } catch (Throwable t) {
            MainMenuTheme.fail("menu tint", t);
        }
    }
}

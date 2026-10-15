package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Splash text (vanilla yellow via SplashManager.DEFAULT_STYLE) in light orange. The splash component is
 *  fixed per SplashRenderer instance, so the recoloured copy is cached instead of rebuilt every frame. */
@Mixin(SplashRenderer.class)
public abstract class MainMenuSplashMixin {

    @Unique
    private Component killer560smod$source;
    @Unique
    private Component killer560smod$themed;
    @Unique
    private int killer560smod$themedRgb = -1;

    /** The main menu's Custom style can turn the splash text off (2026-10-15). */
    @org.spongepowered.asm.mixin.injection.Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true,
            require = 0)
    private void killer560smod$hideSplash(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        try {
            if (MainMenuTheme.activeOnTitleScreen() && !com.killer560.hub.mainmenu.MainMenuLook.current().splash()) {
                ci.cancel();
            }
        } catch (Throwable t) {
            MainMenuTheme.fail("splash toggle", t);
        }
    }

    @ModifyArg(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;ILnet/minecraft/client/gui/Font;F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/ActiveTextCollector;accept(Lnet/minecraft/client/gui/TextAlignment;IILnet/minecraft/client/gui/ActiveTextCollector$Parameters;Lnet/minecraft/network/chat/Component;)V"),
            index = 4, require = 0)
    private Component killer560smod$themedSplash(Component splash) {
        if (splash == null || !MainMenuTheme.activeOnTitleScreen()) {
            return splash;
        }
        try {
            int rgb = com.killer560.hub.mainmenu.MainMenuChrome.accentLightColour();
            if (killer560smod$source != splash || killer560smod$themed == null || killer560smod$themedRgb != rgb) {
                killer560smod$source = splash;
                killer560smod$themedRgb = rgb;
                // Override the root colour (the splash's own style IS the yellow), keep any other styling.
                killer560smod$themed = splash.copy().withStyle((Style s) -> s.withColor(TextColor.fromRgb(rgb)));
            }
            return killer560smod$themed;
        } catch (Throwable t) {
            MainMenuTheme.fail("splash", t);
            return splash;
        }
    }
}

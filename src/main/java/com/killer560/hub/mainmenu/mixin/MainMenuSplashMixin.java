package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import net.minecraft.client.gui.components.SplashRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No splash text (the yellow quip beside the logo) on the mod's themed main menu, in every theme and style (killer560,
 * 2026-10-15: "on all custom main menus ... Just don't have those show"). With the main menu theming off the title
 * screen is vanilla's and keeps its splash. The handler takes only {@link CallbackInfo}, so it matches the method on both
 * Minecraft versions whatever its arguments.
 */
@Mixin(SplashRenderer.class)
public abstract class MainMenuSplashMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideSplash(CallbackInfo ci) {
        try {
            if (MainMenuTheme.activeOnTitleScreen()) {
                ci.cancel();
            }
        } catch (Throwable t) {
            MainMenuTheme.fail("splash", t);
        }
    }
}

package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuFramerate;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lifts vanilla's 60 fps cap on out-of-world menus while the themed main menu draws its animated scene (killer560,
 * 2026-10-16: "make that one super high quality and 120fps"). {@code FramerateLimitTracker.getFramerateLimit()} returns 60
 * for {@code OUT_OF_LEVEL_MENU} (no level, a screen or an overlay open) and the user's Max Framerate otherwise; the class and
 * both methods are the same in 26.1.2 and 26.2 (javap; 26.2 reads the screen through {@code Gui.screen()}, which is inside
 * {@code getThrottleReason}, untouched). Only that one reason is changed: an iconified window and AFK keep their caps, and
 * {@code Minecraft.renderFrame} still treats 260 as unlimited and leaves vsync to the window.
 */
@Mixin(FramerateLimitTracker.class)
public abstract class MainMenuFramerateMixin {

    @Shadow
    private int framerateLimit;

    @Shadow
    public abstract FramerateLimitTracker.FramerateThrottleReason getThrottleReason();

    @Inject(method = "getFramerateLimit()I", at = @At("HEAD"), cancellable = true)
    private void killer560smod$themedMenuFramerate(CallbackInfoReturnable<Integer> cir) {
        if (getThrottleReason() == FramerateLimitTracker.FramerateThrottleReason.OUT_OF_LEVEL_MENU) {
            int raised = MainMenuFramerate.menuLimit(framerateLimit);
            if (raised > 0) {
                cir.setReturnValue(raised);
            }
        }
    }
}

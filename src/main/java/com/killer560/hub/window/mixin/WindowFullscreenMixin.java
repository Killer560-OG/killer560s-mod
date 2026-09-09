package com.killer560.hub.window.mixin;

import com.killer560.hub.window.WindowModeFeature;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Per killer560's "if i press f11 it should go to windowed and if i press it again it should go back
 *  to being borderless... currently f11 still toggles true fullscreen when it shouldnt be able to"
 *  request (2026-09-09) - {@code Options.keyFullscreen} (real F11, confirmed via javap against the real
 *  26.1.2 jar) calls {@code Window.toggleFullScreen()} directly from {@code KeyboardHandler.keyPress},
 *  so cancelling that one method redirects every real path that can trigger true/exclusive fullscreen
 *  (just this one in practice) over to {@link WindowModeFeature#toggle()}'s own windowed/borderless
 *  cycle instead - true fullscreen becomes unreachable via F11 entirely, only still reachable through
 *  the vanilla Video Settings screen's own separate Fullscreen option if killer560 ever wants it. */
@Mixin(Window.class)
public abstract class WindowFullscreenMixin {

    @Inject(method = "toggleFullScreen", at = @At("HEAD"), cancellable = true)
    private void killer560smod$replaceWithBorderlessToggle(CallbackInfo ci) {
        WindowModeFeature.toggle();
        ci.cancel();
    }
}

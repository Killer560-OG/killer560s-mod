package com.killer560.hub.window.mixin;

import com.killer560.hub.window.WindowModeFeature;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 26.3 copy of the F11 redirect (the 26.2 copy hooks {@code Window.toggleFullScreen}, which 26.3 removed). On 26.3 the
 *  fullscreen key runs {@code Minecraft.toggleFullscreen()V} (private; javap 26.3: {@code Minecraft} matches
 *  {@code Options.keyFullscreen} and calls it, and it flips {@code options.fullscreen()}, whose listener calls
 *  {@code Window.setFullscreen}). Canceling it sends F11 to {@link WindowModeFeature#toggle()}'s windowed/borderless
 *  cycle, as on 26.2; vanilla fullscreen stays reachable from Video Settings. */
@Mixin(Minecraft.class)
public abstract class WindowFullscreenMixin {

    @Inject(method = "toggleFullscreen", at = @At("HEAD"), cancellable = true)
    private void killer560smod$replaceWithBorderlessToggle(CallbackInfo ci) {
        WindowModeFeature.toggle();
        ci.cancel();
    }
}

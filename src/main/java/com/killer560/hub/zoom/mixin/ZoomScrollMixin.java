package com.killer560.hub.zoom.mixin;

import com.killer560.hub.zoom.ZoomFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While Zoom is held, the wheel changes the zoom and not the hotbar. {@code MouseHandler.onScroll(long, double, double)} is private, the same on 26.1.2 and 26.2. */
@Mixin(MouseHandler.class)
public abstract class ZoomScrollMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$zoomScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (client.getWindow() != null && window == client.getWindow().handle() && ZoomFeature.onScroll(yOffset)) {
            ci.cancel();
        }
    }
}

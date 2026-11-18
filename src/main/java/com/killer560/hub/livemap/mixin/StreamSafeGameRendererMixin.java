package com.killer560.hub.livemap.mixin;

import com.killer560.hub.livemap.StreamSafeMap;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stream Safe Funny Map: once the frame's GUI is drawn ({@code GuiRenderer.endFrame}), the overlay's own GUI renderer
 * may draw the Funny Map offscreen. {@code render()} on 26.3, {@code render(DeltaTracker, boolean)} on 26.2 (javap): the
 * handler takes only {@link CallbackInfo}, which fits both.
 */
@Mixin(GameRenderer.class)
public abstract class StreamSafeGameRendererMixin {

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;endFrame()V", shift = At.Shift.AFTER))
    private void killer560smod$streamSafeAfterGui(CallbackInfo ci) {
        StreamSafeMap.afterGui();
    }
}

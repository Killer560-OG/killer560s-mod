package com.killer560.hub.storageoverlay.mixin;

import com.killer560.hub.storageoverlay.StorageOverlayFeature;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the Storage Overlay during the normal gameplay HUD pass (not tied to any container screen
 *  being open, unlike the RNG Meter overlay) - same injection point {@code ModOverlayMessage} uses. */
@Mixin(Gui.class)
public abstract class StorageOverlayGuiMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void killer560smod$drawStorageOverlay(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        StorageOverlayFeature.renderIfHoldingKnownStorage(graphics);
    }
}

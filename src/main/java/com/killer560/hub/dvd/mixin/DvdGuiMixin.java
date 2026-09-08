package com.killer560.hub.dvd.mixin;

import com.killer560.hub.dvd.DvdFeature;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws every active bouncing DVD box at the same point in the HUD render pass the other overlays use. */
@Mixin(Gui.class)
public abstract class DvdGuiMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void killer560smod$drawDvd(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        DvdFeature.renderOverlay(graphics);
    }
}

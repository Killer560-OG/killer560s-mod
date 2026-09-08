package com.killer560.hub.experiments.mixin;

import com.killer560.hub.experiments.ExperimentsFeature;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the "Auto Experiment: ..." status text at the same point in the HUD render pass the other overlays use. */
@Mixin(Gui.class)
public abstract class ExperimentsGuiMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void killer560smod$drawExperimentsStatus(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        ExperimentsFeature.renderOverlay(graphics);
    }
}

package com.killer560.hub.experiments.mixin;

import com.killer560.hub.experiments.ExperimentsFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the "Start ETable" overlay button AFTER the container screen has rendered itself (real bug
 * found and fixed 2026-09-06: drawing it from the earlier {@code Gui}-level HUD render pass instead
 * meant the container screen's own darkened background drew over it afterward, making a fully
 * functional button look greyed-out/disabled).
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ExperimentsContainerRenderMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void killer560smod$drawStartButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        ExperimentsFeature.renderStartButtonOverContainer(graphics);
    }
}

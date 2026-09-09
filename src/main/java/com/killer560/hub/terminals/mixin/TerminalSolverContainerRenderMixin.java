package com.killer560.hub.terminals.mixin;

import com.killer560.hub.terminals.TerminalSolverFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the Terminal Solver's highlight overlay AFTER the container screen has rendered itself, same
 *  z-order fix as {@code ExperimentsContainerRenderMixin} - drawing from the earlier HUD render pass
 *  would get covered by the container's own darkened background. */
@Mixin(AbstractContainerScreen.class)
public abstract class TerminalSolverContainerRenderMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void killer560smod$drawTerminalHighlights(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        TerminalSolverFeature.renderHighlights(graphics);
    }
}

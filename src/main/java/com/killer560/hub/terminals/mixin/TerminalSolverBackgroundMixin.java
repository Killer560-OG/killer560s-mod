package com.killer560.hub.terminals.mixin;

import com.killer560.hub.terminals.TerminalSolverFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Real bug found and fixed (2026-09-09), per killer560's screenshot showing the real vanilla chest
 *  texture (the light-gray slot grid) still fully visible behind the Custom GUI panel: confirmed via
 *  javap that the actual background image draw is a completely separate method,
 *  {@code ContainerScreen.extractBackground} (on the concrete chest-style screen class, not the
 *  {@code AbstractContainerScreen} base {@link TerminalSolverSlotMixin} already hooks) - hiding item
 *  icons/tooltips/labels there never touched this at all. Cancelling it here is the last piece needed
 *  for "the buttons i have to press are the only things I see in the gui". */
@Mixin(ContainerScreen.class)
public abstract class TerminalSolverBackgroundMixin {

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void killer560smod$hideTerminalBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (TerminalSolverFeature.isCustomGuiActive()) {
            ci.cancel();
        }
    }
}

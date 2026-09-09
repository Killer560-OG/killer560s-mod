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
 *  for "the buttons i have to press are the only things I see in the gui".
 *  <p>
 *  Real bug found and fixed (2026-09-09, round 2), per killer560's report of a real, brief flash of the
 *  raw terminal on open before Custom GUI kicks in: confirmed via javap that
 *  {@code Screen.extractRenderStateWithTooltipAndSubtitles} (the true top-level entry point a screen's
 *  render actually starts from) calls {@code extractBackground} FIRST, before {@code extractRenderState}
 *  even begins - meaning {@link TerminalSolverFeature#refreshState()} used to run too late in the frame
 *  (it was hooked off {@code extractRenderState} itself) to affect that same frame's background call.
 *  Moved the refresh to run right here instead, at the very front of the whole chain, so every hook
 *  downstream of this one (this one included) sees fresh state on every single frame, including the
 *  very first one right after the screen opens. */
@Mixin(ContainerScreen.class)
public abstract class TerminalSolverBackgroundMixin {

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void killer560smod$hideTerminalBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        TerminalSolverFeature.refreshState();
        if (TerminalSolverFeature.isCustomGuiActive()) {
            ci.cancel();
        }
    }
}

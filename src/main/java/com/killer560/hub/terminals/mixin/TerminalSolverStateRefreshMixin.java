package com.killer560.hub.terminals.mixin;

import com.killer560.hub.terminals.TerminalSolverFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Real bug found and fixed (2026-09-09), per killer560's screenshot showing the Melody accent border
 *  leaking onto a totally unrelated real Dispenser GUI: {@link TerminalSolverBackgroundMixin}'s own
 *  refresh only ran from {@code ContainerScreen.extractBackground} - the right hook for every REAL
 *  terminal (they're all chest-style {@code ContainerScreen}s), but confirmed via javap that a
 *  Dispenser opens a different concrete screen class that never calls that method at all. Since that
 *  hook was the ONLY place {@link TerminalSolverFeature#refreshState()} ran, closing a terminal and
 *  opening literally any other kind of container left {@code currentType} stuck at its stale previous
 *  value forever - which {@code TerminalSolverContainerRenderMixin}'s own render hook (correctly
 *  targeting the much broader {@code AbstractContainerScreen}) then dutifully drew on top of.
 *  <p>
 *  Fixed by refreshing from here too - the real base {@code Screen.extractBackground}, confirmed via
 *  javap to actually exist as its own method there (not just assumed inherited), so every subclass's
 *  own override chain runs through it eventually, covering every kind of screen in the game, not just
 *  chest-style ones. Purely a safety net (no cancelling here, unlike the more specific mixin) - just
 *  guarantees state is never more than one frame stale for anything this mod doesn't actually cover. */
@Mixin(Screen.class)
public abstract class TerminalSolverStateRefreshMixin {

    @Inject(method = "extractBackground", at = @At("HEAD"))
    private void killer560smod$refreshTerminalStateEverywhere(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        TerminalSolverFeature.refreshState();
    }
}

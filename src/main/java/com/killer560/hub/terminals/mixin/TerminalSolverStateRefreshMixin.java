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
 *  chest-style ones. Purely a safety net (no canceling here, unlike the more specific mixin) - just
 *  guarantees state is never more than one frame stale for anything this mod doesn't actually cover.
 *  <p>
 *  Moved to the HEAD of {@code Screen.extractRenderStateWithTooltipAndSubtitles} (2026-11-01): the one refresh used to
 *  run from {@code ContainerScreen.extractBackground}, and any other mod or feature that cancels that method at HEAD
 *  first stopped every terminal from ever being tracked. Inventory Theme does exactly that, so with it on (his Untested
 *  Cheat instance) the solver, Custom GUI and Auto Terminals never showed on any terminal (testkit
 *  1101-menu-term-instcfg reproduced it with that instance's config). This method is final, calls extractBackground
 *  itself first thing after a stratum (javap 26.3 and 26.2), and nothing cancels it, so the state is fresh before any
 *  background hook runs, on every screen, every frame. */
@Mixin(Screen.class)
public abstract class TerminalSolverStateRefreshMixin {

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"))
    private void killer560smod$refreshTerminalStateEverywhere(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        TerminalSolverFeature.refreshState();
    }
}

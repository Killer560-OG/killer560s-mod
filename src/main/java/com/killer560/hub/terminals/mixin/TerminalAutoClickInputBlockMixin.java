package com.killer560.hub.terminals.mixin;

import com.killer560.hub.terminals.TerminalSolverFeature;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Swallows killer560's own mouse/keyboard input to a terminal screen while
 *  {@link TerminalSolverFeature#shouldBlockInput()} says so - per killer560's explicit "add a toggle
 *  on by default" request (2026-09-09), same real protection {@code ExperimentsInputBlockMixin} already
 *  gives Auto ETable (a stray manual click/keypress mid-auto-click could otherwise fight the bot - a
 *  manual Rubix click in the wrong direction, for instance, would actively undo an auto-click's own
 *  progress). Only ever intercepts REAL input events; Auto Terminals' own synthetic clicks go through
 *  {@code SlotClickInvoker}/{@code MultiPlayerGameMode.handleContainerInput} directly and never pass
 *  through these methods. */
@Mixin(AbstractContainerScreen.class)
public abstract class TerminalAutoClickInputBlockMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void killer560smod$blockMouseClicked(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (TerminalSolverFeature.shouldBlockInput()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void killer560smod$blockMouseReleased(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (TerminalSolverFeature.shouldBlockInput()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void killer560smod$blockMouseDragged(MouseButtonEvent event, double dragX, double dragY, CallbackInfoReturnable<Boolean> cir) {
        if (TerminalSolverFeature.shouldBlockInput()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void killer560smod$blockMouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY, CallbackInfoReturnable<Boolean> cir) {
        if (TerminalSolverFeature.shouldBlockInput()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void killer560smod$blockKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        // Per killer560's explicit "I should still be able to press escape while in terminals even if
        // it blocks my other keys" (2026-09-09) - matches NoammAddons' own real precedent too (its
        // AutoTerminal exempts both Escape and the player's own Inventory keybind from this exact same
        // kind of input block), so closing/backing out of the menu is never trapped behind Auto
        // Terminals' input block.
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            return;
        }
        if (TerminalSolverFeature.shouldBlockInput()) {
            cir.setReturnValue(true);
        }
    }
}

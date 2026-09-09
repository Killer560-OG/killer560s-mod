package com.killer560.hub.terminals.mixin;

import com.killer560.hub.terminals.TerminalSolverFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Draws the Terminal Solver's highlight overlay (vanilla-overlay mode or Custom GUI mode, see
 *  {@link TerminalSolverFeature#renderOverlay}) AFTER the container screen has rendered itself, same
 *  z-order fix as {@code ExperimentsContainerRenderMixin} - drawing from the earlier HUD render pass
 *  would get covered by the container's own darkened background. Also intercepts clicks/drags while
 *  Custom GUI mode is showing, the same "hide the real slots, redirect clicks to them by index" trick
 *  {@code StorageOverlayContainerMixin} already uses. */
@Mixin(AbstractContainerScreen.class)
public abstract class TerminalSolverContainerRenderMixin extends Screen {

    protected TerminalSolverContainerRenderMixin(Component title) {
        super(title);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void killer560smod$drawTerminalHighlights(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        // State itself is refreshed earlier, from TerminalSolverBackgroundMixin - see that class's own
        // doc for why THIS method is already too late in the frame for that.
        TerminalSolverFeature.renderOverlay(graphics, mouseX, mouseY);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void killer560smod$clickTerminalCustomGui(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (TerminalSolverFeature.handleCustomGuiClick(self, event.x(), event.y(), event.button())) {
            cir.setReturnValue(true);
        }
    }

    // Same real bug category StorageOverlay already hit and fixed (2026-09-08): a real click's
    // continuation (drag/release) must be blocked too, not just the initial press, or an item picked
    // up via the redirect above can still get dropped by vanilla's own unblocked drag/release handling.
    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void killer560smod$blockTerminalCustomGuiDrag(MouseButtonEvent event, double dragX, double dragY, CallbackInfoReturnable<Boolean> cir) {
        if (TerminalSolverFeature.isCustomGuiActive()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void killer560smod$blockTerminalCustomGuiRelease(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (TerminalSolverFeature.isCustomGuiActive()) {
            cir.setReturnValue(true);
        }
    }
}

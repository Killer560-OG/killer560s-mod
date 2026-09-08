package com.killer560.hub.experiments.mixin;

import com.killer560.hub.experiments.ExperimentsFeature;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Swallows killer560's own mouse/keyboard input to any container screen while
 * {@link ExperimentsFeature#shouldBlockInput()} says so - targeting {@code AbstractContainerScreen}
 * directly (rather than the base {@code Screen}, plus a runtime instanceof check) means this mod's
 * own menu screens are never in scope at all, since none of them are container screens. Only ever
 * intercepts REAL input events; the solver/navigator's own synthetic clicks go straight through
 * {@code MultiPlayerGameMode.handleContainerInput(...)} and never pass through these methods, so
 * they're completely unaffected by this mixin either way.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ExperimentsInputBlockMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void killer560smod$blockMouseClicked(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        // Checked first, even ahead of the general input block below - the Start ETable button must
        // stay clickable regardless of Block Input's own setting, since nothing is armed yet for it
        // to protect against interfering with.
        if (ExperimentsFeature.tryClickStartButton(event.x(), event.y())) {
            cir.setReturnValue(true);
            return;
        }
        if (ExperimentsFeature.shouldBlockInput()) {
            cir.setReturnValue(true);
            return;
        }
        if (ExperimentsFeature.shouldBlockManualMisclick(event)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void killer560smod$blockMouseReleased(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (ExperimentsFeature.shouldBlockInput()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void killer560smod$blockMouseDragged(MouseButtonEvent event, double dragX, double dragY, CallbackInfoReturnable<Boolean> cir) {
        if (ExperimentsFeature.shouldBlockInput()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void killer560smod$blockMouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY, CallbackInfoReturnable<Boolean> cir) {
        if (ExperimentsFeature.shouldBlockInput()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void killer560smod$blockKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (ExperimentsFeature.shouldBlockInput()) {
            cir.setReturnValue(true);
        }
    }
}

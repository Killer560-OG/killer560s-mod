package com.killer560.hub.terminals.mixin;

import com.killer560.hub.terminals.TerminalSolverFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the vanilla item/tooltip/label rendering for every real slot while Custom GUI mode is
 *  showing - {@link TerminalSolverFeature#renderOverlay} draws its own replacement panel instead, so
 *  the real (still fully functional, just invisible) slots would otherwise be redundant clutter behind
 *  it. Same three injection points {@code StorageOverlaySlotMixin} already uses for the exact same
 *  reason. */
@Mixin(AbstractContainerScreen.class)
public abstract class TerminalSolverSlotMixin {

    @Shadow
    protected Slot hoveredSlot;

    @Inject(method = "extractSlot", at = @At("HEAD"), cancellable = true)
    private void killer560smod$hideTerminalSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        if (TerminalSolverFeature.isCustomGuiActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true)
    private void killer560smod$hideTerminalTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (hoveredSlot != null && TerminalSolverFeature.isCustomGuiActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "extractLabels", at = @At("HEAD"), cancellable = true)
    private void killer560smod$hideTerminalLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (TerminalSolverFeature.isCustomGuiActive()) {
            ci.cancel();
        }
    }
}

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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hides the vanilla item/tooltip/label rendering for real slots while Custom GUI mode is showing -
 *  {@link TerminalSolverFeature#renderOverlay} draws its own replacement panel instead, so the real
 *  (still fully functional, just invisible) slots would otherwise be redundant clutter behind it. Same
 *  three injection points {@code StorageOverlaySlotMixin} already uses for the exact same reason.
 *  Applies to every type uniformly, Melody included since round 6 (2026-09-09) folded it into the same
 *  full hide-and-redraw treatment as everything else.
 *  <p>
 *  Hidden slots are not hoverable either (killer560, 2026-10-15: "if you can see my mouse it still lights up squares
 *  from the old gui. Those old squares should be unclickable and not show up when hovered"). Vanilla's private
 *  {@code getHoveredSlot(double, double)} is the one place {@code hoveredSlot} comes from ({@code extractContents}
 *  assigns it every frame; javap 26.1.2 and 26.2), and the slot highlight, the tooltip, the hotbar-number / drop /
 *  pick-block keys, the scroll actions and every other feature's hover read that field. Answering null there while the
 *  Custom GUI is up turns all of them off for the hidden slots at once; the panel's own squares do their own hit-test
 *  ({@code TerminalSolverFeature.customGuiSlotAt}) and are untouched. */
@Mixin(AbstractContainerScreen.class)
public abstract class TerminalSolverSlotMixin {

    @Shadow
    protected Slot hoveredSlot;

    @Inject(method = "getHoveredSlot", at = @At("HEAD"), cancellable = true)
    private void killer560smod$noHoverOnHiddenSlots(double mouseX, double mouseY, CallbackInfoReturnable<Slot> cir) {
        if (TerminalSolverFeature.isCustomGuiActive()) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "extractSlot", at = @At("HEAD"), cancellable = true)
    private void killer560smod$hideTerminalSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        if (TerminalSolverFeature.shouldHideSlot(slot.index)) {
            ci.cancel();
        }
    }

    @Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true)
    private void killer560smod$hideTerminalTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (hoveredSlot != null && TerminalSolverFeature.shouldHideSlot(hoveredSlot.index)) {
            ci.cancel();
        }
    }

    @Inject(method = "extractLabels", at = @At("HEAD"), cancellable = true)
    private void killer560smod$hideTerminalLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (TerminalSolverFeature.shouldHideBackgroundAndLabels()) {
            ci.cancel();
        }
    }
}

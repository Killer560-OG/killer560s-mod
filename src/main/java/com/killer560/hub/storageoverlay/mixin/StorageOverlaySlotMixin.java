package com.killer560.hub.storageoverlay.mixin;

import com.killer560.hub.storageoverlay.StorageOverlayConfig;
import com.killer560.hub.storageoverlay.StorageOverlayFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the vanilla item rendering for the TOP (storage) slots only on a tracked Ender Chest/
 *  Backpack screen - the 3-column grid already shows the same items in its own "active page" panel,
 *  so leaving vanilla's copy visible too just looked cluttered. The player's own 36 inventory slots
 *  are deliberately left alone (their index is always {@code >= menu.slots.size() - 36}) so nothing
 *  about interacting with your own inventory changes. Only the DRAW call is cancelled here, never
 *  click handling (that hit-tests a {@code Slot}'s own x/y directly, not what was drawn), so the real
 *  slots underneath stay fully clickable even though nothing shows there any more. */
@Mixin(AbstractContainerScreen.class)
public abstract class StorageOverlaySlotMixin {

    @Shadow
    protected Slot hoveredSlot;

    @Inject(method = "extractSlot", at = @At("HEAD"), cancellable = true)
    private void killer560smod$hideStorageSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (!StorageOverlayConfig.getInstance().isEnabled()) {
            return;
        }
        if (!StorageOverlayFeature.shouldHideVanilla(self.getTitle().getString())) {
            return;
        }
        int containerSlotCount = Math.max(0, self.getMenu().slots.size() - 36);
        if (slot.index < containerSlotCount) {
            ci.cancel();
        }
    }

    /** Real bug found and fixed (2026-09-08), per killer560's report of a real Hypixel "Backpack Slot
     *  6" tooltip still popping up over the grid, confusingly unrelated to whatever panel he was
     *  actually looking at - the real (now invisible) slot underneath was still fully hover-active.
     *  Suppresses the real tooltip for a hidden top slot on the overview screen specifically, where
     *  every panel is now fully handled by the grid's own click routing instead. */
    @Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true)
    private void killer560smod$hideOverviewTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (!StorageOverlayConfig.getInstance().isEnabled()) {
            return;
        }
        if (!StorageOverlayFeature.isOverviewTitle(self.getTitle().getString())) {
            return;
        }
        int containerSlotCount = Math.max(0, self.getMenu().slots.size() - 36);
        if (hoveredSlot != null && hoveredSlot.index < containerSlotCount) {
            ci.cancel();
        }
    }

    /** Real bug found and fixed (2026-09-08), per killer560's screenshot showing real leftover grey
     *  text (e.g. "Greater Backpack (Slot #9)") sitting right above our own grid panels - the vanilla
     *  title/"Inventory" labels are drawn by a separate method from the background/slots already
     *  hidden above, so they were never actually suppressed. Cancels both for any tracked screen. */
    @Inject(method = "extractLabels", at = @At("HEAD"), cancellable = true)
    private void killer560smod$hideStorageLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (!StorageOverlayConfig.getInstance().isEnabled()) {
            return;
        }
        if (StorageOverlayFeature.shouldHideVanilla(self.getTitle().getString())) {
            ci.cancel();
        }
    }
}

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

/** Hides the vanilla item rendering for EVERY slot - both the top (storage) slots and, since
 *  killer560's "move the inventory portion all the way to the bottom" request (2026-09-08), the
 *  player's own 36 inventory slots too - on a tracked Ender Chest/Backpack screen. The grid draws its
 *  own copy of every storage's contents, and {@link StorageOverlayFeature#renderInventoryPanel} draws
 *  a full replacement for the real inventory elsewhere on screen, so leaving vanilla's own copies
 *  visible in their original (fixed, unmovable - {@code Slot.x}/{@code y} are {@code final}) positions
 *  would just be redundant clutter. Only the DRAW call is cancelled here, never click handling (that
 *  hit-tests a {@code Slot}'s own x/y directly, not what was drawn) - the real slots stay fully
 *  functional at their original position, just invisible; the relocated Inventory panel instead
 *  redirects clicks/hover to them by index (see {@code SlotClickInvoker}). */
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
        if (StorageOverlayFeature.shouldHideVanilla(self.getTitle().getString())) {
            ci.cancel();
        }
    }

    /** Real bug found and fixed (2026-09-08), per killer560's report of a real Hypixel "Backpack Slot
     *  6" tooltip still popping up over the grid, confusingly unrelated to whatever panel he was
     *  actually looking at - the real (now invisible) slot underneath was still fully hover-active.
     *  Suppresses the real tooltip for EVERY hidden slot on any tracked screen - both the top (storage)
     *  slots (including the active page's own, since killer560's "can't drag between inventory and
     *  storage" report showed those need their own manually-triggered tooltip too now, same as every
     *  other panel - the real one still only tracks their old, invisible, unmovable position) and the
     *  player's own inventory slots, which {@link StorageOverlayFeature#renderInventoryPanel} always
     *  relocates and re-shows their real tooltip itself instead. */
    @Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true)
    private void killer560smod$hideRelocatedTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (!StorageOverlayConfig.getInstance().isEnabled() || hoveredSlot == null) {
            return;
        }
        if (StorageOverlayFeature.shouldHideVanilla(self.getTitle().getString())) {
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

package com.killer560.hub.storageoverlay.mixin;

import com.killer560.hub.storageoverlay.StorageOverlayConfig;
import com.killer560.hub.storageoverlay.StorageOverlayFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
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

    @Inject(method = "extractSlot", at = @At("HEAD"), cancellable = true)
    private void killer560smod$hideStorageSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (!StorageOverlayConfig.getInstance().isEnabled()) {
            return;
        }
        if (StorageOverlayFeature.storageKeyForTitle(self.getTitle().getString()) == null) {
            return;
        }
        int containerSlotCount = Math.max(0, self.getMenu().slots.size() - 36);
        if (slot.index < containerSlotCount) {
            ci.cancel();
        }
    }
}

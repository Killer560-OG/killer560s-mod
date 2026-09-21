package com.killer560.hub.inventorytheme.mixin;

import com.killer560.hub.inventorytheme.InventoryThemeFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces vanilla's white hover-slot highlight with a themed Amber one. Vanilla splits this into two
 *  private methods (verified via javap: {@code extractSlotHighlightBack(GuiGraphicsExtractor)} runs
 *  before the slot items draw, {@code extractSlotHighlightFront(GuiGraphicsExtractor)} after) - same
 *  targets {@code SpiritLeapHideMixin} already cancels successfully for its own hide-everything case.
 *  This mixin draws the one themed highlight box in the "Back" pass (so it still sits behind the item,
 *  matching vanilla's own layering) and only cancels vanilla's "Front" pass outright - no second themed
 *  draw needed there. {@code require = 0} on both, same safety margin as every other injection here. */
@Mixin(AbstractContainerScreen.class)
public abstract class InventoryThemeHighlightMixin {

    @Shadow
    protected Slot hoveredSlot;

    @Inject(method = "extractSlotHighlightBack", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$themeHighlightBack(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (!InventoryThemeFeature.shouldTheme(self) || InventoryThemeFeature.isOwnedByStorageOverlay(self.getTitle().getString())) {
            return;
        }
        InventoryThemeFeature.drawSlotHighlight(graphics, self, hoveredSlot);
        ci.cancel();
    }

    @Inject(method = "extractSlotHighlightFront", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$themeHighlightFront(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (!InventoryThemeFeature.shouldTheme(self) || InventoryThemeFeature.isOwnedByStorageOverlay(self.getTitle().getString())) {
            return;
        }
        // Our own highlight was already drawn in the "Back" pass above - just suppress vanilla's
        // second (white) pass rather than draw anything twice.
        ci.cancel();
    }
}

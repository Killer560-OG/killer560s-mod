package com.killer560.hub.inventorytheme.mixin;

import com.killer560.hub.inventorytheme.InventoryThemeFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws a themed slot backdrop right before vanilla's own item render inside
 *  {@code AbstractContainerScreen#extractSlot} - same exact injection point (verified via javap against
 *  minecraft-merged-043a8b3edf-26.1.2.jar, already used by the shipped {@code ItemRaritySlotMixin}:
 *  {@code extractSlot(GuiGraphicsExtractor, Slot, int, int)} contains exactly one
 *  {@code invokevirtual net/minecraft/world/inventory/Slot.isFake:()Z}) and for the same reason its own
 *  doc gives: injecting here rather than at {@code HEAD} means any OTHER feature's {@code HEAD} cancel on
 *  this same method (Storage Overlay / Spirit Leap / Terminal Solver hiding a slot, or vanilla's own
 *  early-return while a stack is mid-split) skips this too - no orphan backdrop square left behind where
 *  no item ever draws. {@code require = 0} so a future MC update that removes/changes that call just
 *  disables this square instead of crashing. */
@Mixin(AbstractContainerScreen.class)
public abstract class InventoryThemeSlotMixin {

    @Inject(
            method = "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;isFake()Z"),
            require = 0
    )
    private void killer560smod$drawSlotBackdrop(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (!InventoryThemeFeature.shouldTheme(self) || InventoryThemeFeature.isOwnedByStorageOverlay(self.getTitle().getString())) {
            return;
        }
        InventoryThemeFeature.drawSlotBackdrop(graphics, self, slot);
    }
}

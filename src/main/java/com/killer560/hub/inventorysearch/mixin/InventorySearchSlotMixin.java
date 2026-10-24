package com.killer560.hub.inventorysearch.mixin;

import com.killer560.hub.inventorysearch.InventorySearchFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inventory Search's match highlight: a full fill of the slot drawn right before vanilla draws the slot's item, at the
 * one {@code Slot.isFake()} call in {@code AbstractContainerScreen#extractSlot(GuiGraphicsExtractor, Slot, int, int)}
 * (javap 26.1.2 and 26.2; the same point {@code InventoryThemeSlotMixin} uses). So the item sits on top of the color,
 * the tooltip covers both, and a slot another mixin hides with a HEAD cancel gets no orphan fill. Coordinates are
 * slot-local (docs/LESSONS-GUI.md).
 */
@Mixin(AbstractContainerScreen.class)
public abstract class InventorySearchSlotMixin {

    @Inject(method = "extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;isFake()Z"), require = 0)
    private void killer560smod$drawSearchMatch(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY,
                                               CallbackInfo ci) {
        InventorySearchFeature.drawSlotHighlight(graphics, (AbstractContainerScreen<?>) (Object) this, slot);
    }
}

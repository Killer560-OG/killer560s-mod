package com.killer560.hub.inventorysearch.mixin;

import com.killer560.hub.inventorysearch.InventorySearchFeature;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A chest moved up clear of the Inventory Search bar ({@link InventorySearchScreenShiftMixin} lowers {@code topPos}) must
 * draw its background there too: {@code ContainerScreen#extractBackground} does not read {@code topPos}, it centers the
 * texture again from {@code height} (javap 26.2 and 26.3: one {@code getfield ContainerScreen.height} at offset 23), so the
 * slots, title and items moved while the gray panel stayed put (testkit 1102-ui-storagesearch-screens picture, 2026-11-02).
 * The height read there is answered so the centering lands on the moved {@code topPos}; nothing else reads it.
 */
@Mixin(ContainerScreen.class)
public abstract class InventorySearchChestBackgroundMixin {

    @ModifyExpressionValue(method = "extractBackground", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/gui/screens/inventory/ContainerScreen;height:I"))
    private int killer560smod$backgroundAtMovedTop(int height) {
        return InventorySearchFeature.backgroundHeight((AbstractContainerScreen<?>) (Object) this, height);
    }
}

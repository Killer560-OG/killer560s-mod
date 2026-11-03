package com.killer560.hub.inventorysearch.mixin;

import com.killer560.hub.inventorysearch.InventorySearchFeature;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Inventory Search bar has one fixed spot at the bottom middle and menus lay out around it (killer560, 2026-11-02:
 * "no i want it always at the bottom. adjust other menus around it instead"). A menu too tall to sit centered above the
 * bar (a double chest at a big GUI scale) is moved up here, at the end of {@code AbstractContainerScreen#init()} (javap
 * 26.2 and 26.3: {@code protected void init()}), before a subclass's own init places its widgets from {@code topPos}.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class InventorySearchScreenShiftMixin {

    @Inject(method = "init()V", at = @At("TAIL"))
    private void killer560smod$clearSearchBar(CallbackInfo ci) {
        InventorySearchFeature.shiftClearOfBar((AbstractContainerScreen<?>) (Object) this);
    }
}

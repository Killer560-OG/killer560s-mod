package com.killer560.hub.inventorytheme.mixin;

import com.killer560.hub.inventorytheme.InventoryThemeFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Same idea as {@link InventoryThemeContainerLabelsMixin}, for {@code InventoryScreen}'s own separate
 *  {@code extractLabels} override (verified via javap - it does not use
 *  {@code AbstractContainerScreen}'s generic one). */
@Mixin(InventoryScreen.class)
public abstract class InventoryThemeInventoryLabelsMixin {

    @Inject(method = "extractLabels", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$themeInventoryLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (!InventoryThemeFeature.shouldTheme(self)) {
            return;
        }
        InventoryThemeFeature.drawLabels(graphics, self);
        ci.cancel();
    }
}

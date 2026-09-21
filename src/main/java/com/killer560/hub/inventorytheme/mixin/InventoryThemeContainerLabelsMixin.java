package com.killer560.hub.inventorytheme.mixin;

import com.killer560.hub.inventorytheme.InventoryThemeFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Restyles the title/"Inventory" label text to the mod's Amber accent - covers every screen that uses
 *  {@code AbstractContainerScreen}'s own generic {@code extractLabels} (verified via javap: this is the
 *  ONE place that method is actually declared; {@code ContainerScreen} itself has no override, so this
 *  single mixin covers every generic chest-style menu). {@code InventoryScreen} declares its own
 *  separate override and is handled by {@code InventoryThemeInventoryLabelsMixin} instead. Cancel-and-
 *  redraw rather than recoloring vanilla's own draw call in place, matching the already-shipped
 *  {@code StorageOverlaySlotMixin#killer560smod$hideStorageLabels}/{@code SpiritLeapHideMixin}'s exact
 *  same technique for this exact method - safer than an {@code @ModifyArg} into a two-call method where
 *  picking the wrong ordinal recolors the wrong line. */
@Mixin(AbstractContainerScreen.class)
public abstract class InventoryThemeContainerLabelsMixin {

    @Inject(method = "extractLabels", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$themeLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (!InventoryThemeFeature.shouldTheme(self) || InventoryThemeFeature.isOwnedByStorageOverlay(self.getTitle().getString())) {
            return;
        }
        InventoryThemeFeature.drawLabels(graphics, self);
        ci.cancel();
    }
}

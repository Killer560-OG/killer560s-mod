package com.killer560.hub.storageoverlay.mixin;

import com.killer560.hub.storageoverlay.StorageOverlayConfig;
import com.killer560.hub.storageoverlay.StorageOverlayFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the vanilla chest texture behind a tracked Ender Chest/Backpack screen - per killer560's
 *  "the mod needs to hide the old enderchest menu and whatnot" (2026-09-08), since the 3-column grid
 *  already shows the same contents and having both up looked redundant/cluttered. Only the container
 *  screen's own background is touched here - the player's own inventory (drawn separately, not part
 *  of this method) is deliberately left alone. */
@Mixin(ContainerScreen.class)
public abstract class StorageOverlayBackgroundMixin {

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void killer560smod$hideStorageBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                       float partialTick, CallbackInfo ci) {
        ContainerScreen self = (ContainerScreen) (Object) this;
        if (StorageOverlayConfig.getInstance().isEnabled()
                && StorageOverlayFeature.storageKeyForTitle(self.getTitle().getString()) != null) {
            ci.cancel();
        }
    }
}

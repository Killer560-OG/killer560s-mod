package com.killer560.hub.storageoverlay.mixin;

import com.killer560.hub.storageoverlay.StorageOverlayFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the Storage Overlay directly inside every container screen's own render pass, same
 *  injection point (and same reasoning - {@code ScreenEvents.afterExtract} produced no visible
 *  output here) as {@code RngMeterOverlay}'s own {@code AbstractContainerScreenMixin}. */
@Mixin(AbstractContainerScreen.class)
public abstract class StorageOverlayContainerMixin extends Screen {

    protected StorageOverlayContainerMixin(Component title) {
        super(title);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void killer560smod$renderStorageOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                      float partialTick, CallbackInfo ci) {
        StorageOverlayFeature.onContainerScreenRender((AbstractContainerScreen<?>) (Object) this, graphics);
    }
}

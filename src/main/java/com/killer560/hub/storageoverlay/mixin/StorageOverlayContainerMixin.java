package com.killer560.hub.storageoverlay.mixin;

import com.killer560.hub.storageoverlay.StorageOverlayFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Draws the Storage Overlay directly inside every container screen's own render pass, same
 *  injection point (and same reasoning - {@code ScreenEvents.afterExtract} produced no visible
 *  output here) as {@code RngMeterOverlay}'s own {@code AbstractContainerScreenMixin}. Also
 *  intercepts clicks on a non-active grid page to open it, per killer560's "add ... click non
 *  active [page] to open it" request (2026-09-08). */
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

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void killer560smod$clickStorageOverlay(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        // Real bug found and fixed (2026-09-08), per killer560's report that he couldn't click the
        // grid at all on the "Storage" overview screen: this used to gate on activeKey != null, but
        // storageKeyForTitle("Storage") is always null (only numbered pages match it) - meaning
        // handleClick was NEVER even called while browsing the overview, the one screen where every
        // panel in the grid is clickable (there's no "active" page to skip). Gate on shouldHideVanilla
        // instead, which covers both cases the same way onContainerScreenRender already does.
        String title = this.getTitle().getString();
        if (!StorageOverlayFeature.shouldHideVanilla(title)) {
            return;
        }
        String activeKey = StorageOverlayFeature.storageKeyForTitle(title);
        if (StorageOverlayFeature.handleClick(event.x(), event.y(), activeKey)) {
            cir.setReturnValue(true);
        }
    }

    /** Per killer560's "I need to be able to scroll on this page" request (2026-09-08) - lets the
     *  mouse wheel scroll the grid while the cursor is over it. */
    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void killer560smod$scrollStorageOverlay(double mouseX, double mouseY, double scrollX, double scrollY,
                                                       CallbackInfoReturnable<Boolean> cir) {
        String title = this.getTitle().getString();
        if (!StorageOverlayFeature.shouldHideVanilla(title)) {
            return;
        }
        if (StorageOverlayFeature.handleScroll(mouseX, mouseY, scrollY)) {
            cir.setReturnValue(true);
        }
    }
}

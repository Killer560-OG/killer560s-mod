package com.killer560.hub.itembrowser.mixin;

import com.killer560.hub.itembrowser.ItemBrowserFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the Item Browser panel (and its craft / tier popup) inside the container screen's own render pass, at the
 * TAIL of {@code AbstractContainerScreen#extractRenderState} - the same point {@code StorageOverlayContainerMixin}
 * uses - so its {@code setTooltipForNextFrame} is set before the frame's deferred tooltip pass (killer560,
 * 2026-10-11: "make it so hovering something there shows its tooltip"; it was drawn from
 * {@code ScreenEvents.afterExtract} before). Recipe-book screens need {@code ItemBrowserRecipeBookRenderMixin}. Also skips the vanilla slot tooltip while the craft popup is open, so a slot behind the popup can't
 * put its tooltip over it.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ItemBrowserRenderMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
    private void killer560smod$renderItemBrowser(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
                                                 CallbackInfo ci) {
        ItemBrowserFeature.renderFromMixin((AbstractContainerScreen<?>) (Object) this, graphics, mouseX, mouseY);
    }

    @Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideTooltipUnderPopup(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (ItemBrowserFeature.suppressVanillaTooltip()) {
            ci.cancel();
        }
    }
}

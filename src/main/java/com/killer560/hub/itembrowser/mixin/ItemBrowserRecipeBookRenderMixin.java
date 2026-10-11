package com.killer560.hub.itembrowser.mixin;

import com.killer560.hub.itembrowser.ItemBrowserFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The same panel draw as {@code ItemBrowserRenderMixin}, for the screens with a recipe book (the player inventory,
 * crafting table, furnaces). {@code AbstractRecipeBookScreen#extractRenderState} replaces
 * {@code AbstractContainerScreen#extractRenderState} outright - it calls {@code extractContents}, the carried item
 * and {@code extractTooltip} itself and never the super method (javap 26.1.2 and 26.2) - so the container TAIL never
 * runs there and the panel was not drawn in the E inventory at all (testkit 709/710, 2026-10-11).
 */
@Mixin(AbstractRecipeBookScreen.class)
public abstract class ItemBrowserRecipeBookRenderMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
    private void killer560smod$renderItemBrowserRecipeBook(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                           float partialTick, CallbackInfo ci) {
        ItemBrowserFeature.renderFromMixin((AbstractContainerScreen<?>) (Object) this, graphics, mouseX, mouseY);
    }
}

package com.killer560.hub.gui.mixin;

import com.killer560.hub.gui.BelowTooltip;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The same for the recipe-book screens (player inventory, crafting table, furnaces), whose draw never calls the super one. */
@Mixin(AbstractRecipeBookScreen.class)
public abstract class BelowTooltipRecipeBookMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
    private void killer560smod$belowTooltipRecipeBook(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                      float partialTick, CallbackInfo ci) {
        BelowTooltip.fire((AbstractContainerScreen<?>) (Object) this, graphics, mouseX, mouseY, partialTick);
    }
}

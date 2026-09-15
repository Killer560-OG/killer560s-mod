package com.killer560.hub.spiritleap.mixin;

import com.killer560.hub.spiritleap.SpiritLeapOverlayFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips the chest texture (and inventory panel) behind the custom leap menu - same hook the terminal Custom GUI
 *  uses ({@code TerminalSolverBackgroundMixin}). */
@Mixin(ContainerScreen.class)
public abstract class SpiritLeapBackgroundMixin {

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideLeapBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (SpiritLeapOverlayFeature.isHiding(this)) {
            ci.cancel();
        }
    }
}

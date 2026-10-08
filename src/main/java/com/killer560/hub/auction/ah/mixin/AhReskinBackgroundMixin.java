package com.killer560.hub.auction.ah.mixin;

import com.killer560.hub.auction.ah.AhReskin;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips the chest texture behind a reskinned Auction House menu - the Bazaar reskin's hook,
 *  {@code ContainerScreen.extractBackground(GuiGraphicsExtractor, int, int, float)} (javap, 26.1.2 and 26.2). */
@Mixin(ContainerScreen.class)
public abstract class AhReskinBackgroundMixin {

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideAhBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
            CallbackInfo ci) {
        if (AhReskin.isHiding(this)) {
            ci.cancel();
        }
    }
}

package com.killer560.hub.bazaar.mixin;

import com.killer560.hub.bazaar.BazaarReskin;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips the chest texture (and inventory panel) behind a reskinned Bazaar menu - the same hook as
 *  {@code SpiritLeapBackgroundMixin}; {@code ContainerScreen.extractBackground(GuiGraphicsExtractor, int, int, float)}
 *  checked with javap on 26.1.2 and 26.2. */
@Mixin(ContainerScreen.class)
public abstract class BazaarReskinBackgroundMixin {

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideBazaarBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
            CallbackInfo ci) {
        if (BazaarReskin.isHiding(this)) {
            ci.cancel();
        } else if (com.killer560.hub.bazaar.BazaarHud.recording()) {
            com.killer560.hub.bazaar.BazaarHud.recordFrame("vanilla '"
                    + ((net.minecraft.client.gui.screens.Screen) (Object) this).getTitle().getString() + "'");
        }
    }
}

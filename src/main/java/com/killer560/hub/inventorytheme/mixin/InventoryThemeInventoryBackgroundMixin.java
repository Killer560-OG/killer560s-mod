package com.killer560.hub.inventorytheme.mixin;

import com.killer560.hub.inventorytheme.InventoryThemeFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Same idea as {@link InventoryThemeContainerBackgroundMixin}, but for the player's own survival
 *  inventory screen ('e') - verified via javap that {@code InventoryScreen} declares its own
 *  {@code extractBackground} override (it does NOT fall back to {@code AbstractContainerScreen}'s,
 *  which has none of its own - every concrete container screen draws its own texture), so this needs its
 *  own separate mixin rather than being covered by the {@code ContainerScreen} one above. */
@Mixin(InventoryScreen.class)
public abstract class InventoryThemeInventoryBackgroundMixin {

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$themeInventoryBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                          float partialTick, CallbackInfo ci) {
        InventoryScreen self = (InventoryScreen) (Object) this;
        if (!InventoryThemeFeature.shouldTheme(self)) {
            return;
        }
        InventoryThemeFeature.drawBackground(graphics, self);
        ci.cancel();
    }
}

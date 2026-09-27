package com.killer560.hub.inventorytheme.mixin;

import com.killer560.hub.inventorytheme.InventoryThemeConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.EffectsInInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** killer560: "Add a setting to hide the effects in your inventory" - vanilla draws active potion
 *  effects as a panel beside the inventory/chest screen through {@link EffectsInInventory}, a small
 *  helper class {@code InventoryScreen}/{@code CreativeModeInventoryScreen} each own one instance of
 *  (javap-verified: both have a private {@code effects} field of this exact type), so a single mixin
 *  here covers every screen that can show one. Same HEAD-cancel-the-one-extract-method shape every other
 *  mixin in this package uses - {@code EffectsInInventory#extractRenderState} is the one place that
 *  draws the panel; cancelling it is equivalent to there being no active effects at all, purely visual.
 *  <p>
 *  Deliberately independent of {@link InventoryThemeConfig#isEnabled()} (the reskin's own on/off) -
 *  killer560 asked for this as its own setting, so it keeps working with the reskin off. OFF by default
 *  (see {@link InventoryThemeConfig#isHidePotionEffects()}) so nothing changes for anyone until they
 *  turn it on. {@code require = 0} so a future MC update that restructures this just leaves the panel
 *  showing instead of crashing. */
@Mixin(EffectsInInventory.class)
public abstract class InventoryThemeHideEffectsMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideEffects(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (InventoryThemeConfig.getInstance().isHidePotionEffects()) {
            ci.cancel();
        }
    }
}

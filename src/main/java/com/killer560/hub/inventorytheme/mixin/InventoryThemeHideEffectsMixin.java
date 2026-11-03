package com.killer560.hub.inventorytheme.mixin;

import com.killer560.hub.hideeffects.HideEffectsConfig;
import com.killer560.hub.hideeffects.HideEffectsFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.EffectsInInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** killer560: "Add a setting to hide the effects in your inventory" - vanilla draws active potion
 *  effects as a panel beside the inventory screen through {@link EffectsInInventory}, a small
 *  helper class {@code InventoryScreen}/{@code CreativeModeInventoryScreen} each own one instance of
 *  (javap-verified on 26.3 and 26.2, 2026-11-02: those two are the only classes that reference it, so chests, the
 *  Auction House and the Storage Overlay never draw the panel at all). A single mixin here covers every screen that can
 *  show one. HEAD-cancel of {@code EffectsInInventory#extractRenderState(GuiGraphicsExtractor, int, int)} (the same
 *  descriptor on both versions), the one place that draws the panel; canceling it is purely visual.
 *  <p>
 *  Since 2026-11-02 it follows Hide Potion Effects ({@link HideEffectsConfig}, its own card, ON by default) and nothing
 *  else: from 2026-10-18 until then it was hidden only while the Inventory Theme was on, which left no way to hide it
 *  without the theme. The class keeps its old name (it is listed in the Inventory Theme's mixin config). {@code require = 0}
 *  so a future MC update that restructures this just leaves the panel showing instead of crashing. */
@Mixin(EffectsInInventory.class)
public abstract class InventoryThemeHideEffectsMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideEffects(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (HideEffectsConfig.getInstance().isHidePotionEffects()) {
            HideEffectsFeature.panelHiddenFrames++;
            ci.cancel();
        } else {
            HideEffectsFeature.panelShownFrames++;
        }
    }
}

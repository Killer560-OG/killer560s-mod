package com.killer560.hub.hideeffects;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

/**
 * Hide Effect Icons (HUD): wraps Fabric's {@code VanillaHudElements.MOB_EFFECTS} layer (vanilla's top-right effect icons;
 * the field exists in fabric-rendering-v1 25.3.3 for 26.2 and 27.2.0 for 26.3, javap 2026-11-02). The decision is made
 * inside the wrapper every frame, the same shape as {@code PlayerStatsFeature}'s Held Item Name hide, so a toggle acts at
 * once. No mixin. The inventory's effects panel is {@code inventorytheme.mixin.InventoryThemeHideEffectsMixin}.
 */
public final class HideEffectsFeature {

    private HideEffectsFeature() {
    }

    public static void register() {
        HudElementRegistry.replaceElement(VanillaHudElements.MOB_EFFECTS, orig -> (graphics, deltaTracker) -> {
            boolean hide;
            try {
                hide = HideEffectsConfig.getInstance().isHideEffectIcons();
            } catch (RuntimeException e) {
                hide = false;
            }
            if (hide) {
                hudHiddenFrames++;
            } else {
                hudShownFrames++;
                orig.extractRenderState(graphics, deltaTracker);
            }
        });
    }

    /** Frames the effect-icons layer ran hidden / handed to vanilla, and inventory effect panels canceled / drawn: testkit
     *  evidence of which path ran. */
    public static volatile long hudHiddenFrames;
    public static volatile long hudShownFrames;
    public static volatile long panelHiddenFrames;
    public static volatile long panelShownFrames;
}

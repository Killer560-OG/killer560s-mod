package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.hideeffects.HideEffectsConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/**
 * Hide Potion Effects (killer560, 2026-11-02, a screenshot of his inventory with vanilla's effects panel beside it: "find
 * if there is a way to hide these effects as well"). The panel had been hidden only while the Inventory Theme was on since
 * 2026-10-18, so with the theme off he had no way to hide it and the menu search found nothing. Its own card in Items,
 * beside Inventory Theme, with two independent switches: the inventory panel (ON by default) and the HUD's top-right
 * effect icons (OFF by default). The card's switch stands for both ({@link #masterToggles}).
 */
public class HidePotionEffectsTab extends BaseTab {

    public HidePotionEffectsTab() {
        super("Hide Potion Effects");
    }

    @Override
    public List<SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "Hide Potion Effects", "Hide Effect Icons (HUD)");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        HideEffectsConfig cfg = HideEffectsConfig.getInstance();
        int y = contentY;
        widgets.add(onOffButton("Hide Potion Effects", cfg::isHidePotionEffectsRaw, cfg::setHidePotionEffects, cfg::save,
                null, contentX, y, contentWidth, 20));
        y += 24;
        widgets.add(onOffButton("Hide Effect Icons (HUD)", cfg::isHideEffectIconsRaw, cfg::setHideEffectIcons, cfg::save,
                null, contentX, y, contentWidth, 20));
        return widgets;
    }
}

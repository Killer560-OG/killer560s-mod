package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.inventorysearch.InventorySearchConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Inventory Search settings - see
 *  {@link com.killer560.hub.inventorysearch.InventorySearchFeature}'s class doc for the real
 *  Noamm-ported Ctrl+F search-and-highlight this is built on. Explanatory text moved into tooltips
 *  per the mod-wide "no in-panel paragraphs" rule - hover each control. */
public class InventorySearchTab extends BaseTab {

    public InventorySearchTab() {
        super("Inventory Search");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        InventorySearchConfig cfg = InventorySearchConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Inventory Search", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Search Lore", cfg.isSearchLore()), btn -> {
                    cfg.setSearchLore(!cfg.isSearchLore());
                    cfg.save();
                    btn.setMessage(onOff("Search Lore", cfg.isSearchLore()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Ignore Case", cfg.isIgnoreCase()), btn -> {
                    cfg.setIgnoreCase(!cfg.isIgnoreCase());
                    cfg.save();
                    btn.setMessage(onOff("Ignore Case", cfg.isIgnoreCase()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 26;

        double scaleNormalized = (cfg.getBoxScale() - InventorySearchConfig.MIN_BOX_SCALE)
                / (InventorySearchConfig.MAX_BOX_SCALE - InventorySearchConfig.MIN_BOX_SCALE);
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 20, scaleText(cfg), scaleNormalized) {
            @Override
            protected void updateMessage() {
                setMessage(scaleText(InventorySearchConfig.getInstance()));
            }

            @Override
            protected void applyValue() {
                InventorySearchConfig c = InventorySearchConfig.getInstance();
                float newScale = (float) (InventorySearchConfig.MIN_BOX_SCALE
                        + this.value * (InventorySearchConfig.MAX_BOX_SCALE - InventorySearchConfig.MIN_BOX_SCALE));
                c.setBoxScale(newScale);
                c.save();
            }
        });

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private static Component scaleText(InventorySearchConfig cfg) {
        return Component.literal(String.format("Search Bar Scale: %.0f%%", cfg.getBoxScale() * 100));
    }
}

package com.killer560.hub.gui.tab;

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

        widgets.add(onOffButton("Inventory Search", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(onOffButton("Search Lore", cfg::isSearchLore, cfg::setSearchLore, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 22;

        widgets.add(onOffButton("Ignore Case", cfg::isIgnoreCase, cfg::setIgnoreCase, cfg::save,
                null, contentX, y, contentWidth, 18));
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
                saveOnRelease(() -> c.save());
            }
        });

        return widgets;
    }

    private static Component scaleText(InventorySearchConfig cfg) {
        return Component.literal(String.format("Search Bar Scale: %.0f%%", cfg.getBoxScale() * 100));
    }
}

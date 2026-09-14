package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.inventorysearch.InventorySearchConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Inventory Search settings - see
 *  {@link com.killer560.hub.inventorysearch.InventorySearchFeature}'s class doc for the real
 *  Noamm-ported Ctrl+F search-and-highlight this is built on. */
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

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Ctrl+F in any inventory-type screen to search - matching items"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7get a highlight box. Esc or Enter stops typing."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.itembrowser.ItemBrowserConfig;
import com.killer560.hub.itembrowser.SkyblockItemRepository;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Item Browser (NEU-style panel) settings - see
 *  {@link com.killer560.hub.itembrowser.ItemBrowserFeature}'s class doc for the real Not Enough
 *  Updates-referenced item panel this is built on, and how it shares its search box with
 *  {@link com.killer560.hub.inventorysearch.InventorySearchFeature}. */
public class ItemBrowserTab extends BaseTab {

    public ItemBrowserTab() {
        super("Item Browser");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        ItemBrowserConfig cfg = ItemBrowserConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Item Browser", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    if (cfg.isEnabled()) {
                        SkyblockItemRepository.refreshAsync();
                    }
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(countText("Columns", cfg.getColumns()), btn -> {
                    cfg.setColumns(cfg.getColumns() >= 9 ? 3 : cfg.getColumns() + 1);
                    cfg.save();
                    btn.setMessage(countText("Columns", cfg.getColumns()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(countText("Rows", cfg.getRows()), btn -> {
                    cfg.setRows(cfg.getRows() >= 10 ? 3 : cfg.getRows() + 1);
                    cfg.save();
                    btn.setMessage(countText("Rows", cfg.getRows()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Refresh Item Catalog"), btn -> {
                    SkyblockItemRepository.refreshAsync();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 26;

        int itemCount = SkyblockItemRepository.getItems().size();
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Real item catalog: " + (itemCount > 0 ? itemCount + " items loaded" : "not loaded yet")),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7NEU-style panel on the right of any inventory screen. Shares its"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7search box with Inventory Search - Ctrl+F to type, scroll to page."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private static Component countText(String label, int value) {
        return Component.literal(label + ": " + value);
    }
}

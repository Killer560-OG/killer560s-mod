package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
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
 *  {@link com.killer560.hub.inventorysearch.InventorySearchFeature}. Explanatory text moved into
 *  tooltips per the mod-wide "no in-panel paragraphs" rule - hover each control. */
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

        int columnRange = ItemBrowserConfig.MAX_COLUMNS - ItemBrowserConfig.MIN_COLUMNS;
        double columnsNormalized = (cfg.getColumns() - ItemBrowserConfig.MIN_COLUMNS) / (double) columnRange;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 20, columnsText(cfg), columnsNormalized) {
            @Override
            protected void updateMessage() {
                setMessage(columnsText(ItemBrowserConfig.getInstance()));
            }

            @Override
            protected void applyValue() {
                ItemBrowserConfig c = ItemBrowserConfig.getInstance();
                int snapped = ItemBrowserConfig.MIN_COLUMNS + (int) Math.round(this.value * columnRange);
                c.setColumns(snapped);
                c.save();
                this.value = (snapped - ItemBrowserConfig.MIN_COLUMNS) / (double) columnRange;
            }
        });
        y += 24;

        double scaleNormalized = (cfg.getScale() - ItemBrowserConfig.MIN_SCALE)
                / (ItemBrowserConfig.MAX_SCALE - ItemBrowserConfig.MIN_SCALE);
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 20, scaleText(cfg), scaleNormalized) {
            @Override
            protected void updateMessage() {
                setMessage(scaleText(ItemBrowserConfig.getInstance()));
            }

            @Override
            protected void applyValue() {
                ItemBrowserConfig c = ItemBrowserConfig.getInstance();
                float newScale = (float) (ItemBrowserConfig.MIN_SCALE
                        + this.value * (ItemBrowserConfig.MAX_SCALE - ItemBrowserConfig.MIN_SCALE));
                c.setScale(newScale);
                c.save();
            }
        });
        y += 26;

        widgets.add(SettingsButtonWidget.builder(orientationText(cfg), btn -> {
                    cfg.setHorizontal(!cfg.isHorizontal());
                    cfg.save();
                    btn.setMessage(orientationText(cfg));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(alignText(cfg), btn -> {
                    cfg.setAlign(cfg.getAlign().next());
                    cfg.save();
                    btn.setMessage(alignText(cfg));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Refresh Item Catalog"), btn -> {
                    SkyblockItemRepository.refreshAsync();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 26;

        int itemCount = SkyblockItemRepository.getItems().size();
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Real item catalog: " + (itemCount > 0 ? itemCount + " items loaded" : "not loaded yet")
                        + (SkyblockItemRepository.isRefreshing() ? " (refreshing...)" : "")),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private static Component columnsText(ItemBrowserConfig cfg) {
        return Component.literal("Item Width (Columns): " + cfg.getColumns());
    }

    private static Component scaleText(ItemBrowserConfig cfg) {
        return Component.literal(String.format("Scale: %.0f%%", cfg.getScale() * 100));
    }

    private static Component orientationText(ItemBrowserConfig cfg) {
        return Component.literal("Orientation: " + (cfg.isHorizontal() ? "Horizontal" : "Vertical"));
    }

    private static Component alignText(ItemBrowserConfig cfg) {
        return Component.literal("Centered: " + cfg.getAlign().label());
    }
}

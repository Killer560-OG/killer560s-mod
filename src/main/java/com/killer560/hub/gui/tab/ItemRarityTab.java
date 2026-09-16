package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.itemrarity.ItemRarityConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Item Rarity Backgrounds settings - see {@link com.killer560.hub.itemrarity.ItemRarityFeature}. Controls only:
 *  the two grey explainer lines (what it colours, Hypixel/p3sim-only) were removed 2026-09-16 - killer560 called
 *  them clutter. The tooltips still carry that information on hover. */
public class ItemRarityTab extends BaseTab {

    public ItemRarityTab() {
        super("Item Rarity Backgrounds");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        ItemRarityConfig cfg = ItemRarityConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Item Rarity Backgrounds", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(styleLabel(cfg), btn -> {
                    cfg.setStyle(cfg.getStyle().next());
                    cfg.save();
                    btn.setMessage(styleLabel(cfg));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        double opacityNorm = (cfg.getOpacity() - ItemRarityConfig.MIN_OPACITY)
                / (double) (ItemRarityConfig.MAX_OPACITY - ItemRarityConfig.MIN_OPACITY);
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18,
                Component.literal("Opacity: " + cfg.getOpacity() + "%"), opacityNorm) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal("Opacity: " + cfg.getOpacity() + "%"));
            }

            @Override
            protected void applyValue() {
                int range = ItemRarityConfig.MAX_OPACITY - ItemRarityConfig.MIN_OPACITY;
                cfg.setOpacity(ItemRarityConfig.MIN_OPACITY + (int) Math.round(this.value * range));
                cfg.save();
            }
        });
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Show in Hotbar", cfg.isShowInHotbar()), btn -> {
                    cfg.setShowInHotbar(!cfg.isShowInHotbar());
                    cfg.save();
                    btn.setMessage(onOff("Show in Hotbar", cfg.isShowInHotbar()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Skyblock Only", cfg.isSkyblockOnly()), btn -> {
                    cfg.setSkyblockOnly(!cfg.isSkyblockOnly());
                    cfg.save();
                    btn.setMessage(onOff("Skyblock Only", cfg.isSkyblockOnly()));
                }).bounds(contentX, y, contentWidth, 18).build());

        return widgets;
    }

    private static Component styleLabel(ItemRarityConfig cfg) {
        return Component.literal("Style: " + cfg.getStyle().label);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

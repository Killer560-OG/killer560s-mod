package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.itemrarity.ItemRarityConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Item Rarity Backgrounds settings - see {@link com.killer560.hub.itemrarity.ItemRarityFeature}. Controls only:
 *  the two gray explainer lines (what it colors, Hypixel/p3sim-only) were removed 2026-09-16 - killer560 called
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

        widgets.add(onOffButton("Item Rarity Backgrounds", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.cycling(styleLabel(cfg), (btn, dir) -> {
                    cfg.setStyle(dir > 0 ? cfg.getStyle().next() : cfg.getStyle().previous());
                    cfg.save();
                    requestRebuild.run(); // Outline Width only shows for the Outline style
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        if (cfg.getStyle() == ItemRarityConfig.Style.OUTLINE) {
            double span = ItemRarityConfig.MAX_OUTLINE_WIDTH - ItemRarityConfig.MIN_OUTLINE_WIDTH;
            widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, outlineWidthLabel(cfg),
                    (cfg.getOutlineWidth() - ItemRarityConfig.MIN_OUTLINE_WIDTH) / span) {
                @Override
                protected void updateMessage() {
                    setMessage(outlineWidthLabel(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setOutlineWidth(ItemRarityConfig.MIN_OUTLINE_WIDTH + (int) Math.round(this.value * span));
                    saveOnRelease(() -> cfg.save());
                }
            });
            y += 22;
        }

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
                saveOnRelease(() -> cfg.save());
            }
        });
        y += 22;

        widgets.add(onOffButton("Show in Hotbar", cfg::isShowInHotbar, cfg::setShowInHotbar, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 22;

        widgets.add(onOffButton("Skyblock Only", cfg::isSkyblockOnly, cfg::setSkyblockOnly, cfg::save,
                null, contentX, y, contentWidth, 18));

        return widgets;
    }

    private static Component outlineWidthLabel(ItemRarityConfig cfg) {
        return Component.literal("Outline Width: " + cfg.getOutlineWidth() + "px");
    }

    private static Component styleLabel(ItemRarityConfig cfg) {
        return Component.literal("Style: " + cfg.getStyle().label);
    }
}

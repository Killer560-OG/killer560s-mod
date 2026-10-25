package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.inventoryhud.InventoryHudConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Inventory HUD settings - see {@link com.killer560.hub.inventoryhud.InventoryHudFeature}. */
public class InventoryHudTab extends BaseTab implements KeyCaptureTab {

    private boolean capturingKey = false;

    public InventoryHudTab() {
        super("Inventory HUD");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        InventoryHudConfig cfg = InventoryHudConfig.getInstance();

        widgets.add(onOffButton("Inventory HUD", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 28;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colBX = contentX + colW + gap;

        widgets.add(onOffButton("Vertical", cfg::isVertical, cfg::setVertical, cfg::save, null, contentX, y, colW, 18));
        widgets.add(onOffButton("Mini Mode", cfg::isMiniMode, cfg::setMiniMode, cfg::save, null, colBX, y, colW, 18));
        y += 22;

        widgets.add(SettingsButtonWidget.cycling(backgroundText(cfg), (btn, dir) -> {
                    cfg.setBackground(dir > 0 ? cfg.getBackground().next() : cfg.getBackground().previous());
                    cfg.save();
                    btn.setMessage(backgroundText(cfg));
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(backgroundOpacitySlider(colBX, y, colW, cfg));
        y += 22;

        widgets.add(itemOpacitySlider(contentX, y, contentWidth, cfg));
        y += 22;

        widgets.add(com.killer560.hub.gui.OverlayThemes.button(com.killer560.hub.gui.OverlayThemes.INVENTORY_HUD,
                contentX, y, colW, 18));
        y += 22;

        widgets.add(onOffButton("Item Counts", cfg::isShowCounts, cfg::setShowCounts, cfg::save,
                null, contentX, y, colW, 18));
        widgets.add(onOffButton("Durability Bars", cfg::isShowDurability, cfg::setShowDurability, cfg::save,
                null, colBX, y, colW, 18));
        y += 22;

        widgets.add(onOffButton("Pickup Animation", cfg::isPickupAnimation, cfg::setPickupAnimation, cfg::save,
                null, contentX, y, colW, 18));
        widgets.add(onOffButton("Hide When Empty", cfg::isHideWhenEmpty, cfg::setHideWhenEmpty, cfg::save,
                null, colBX, y, colW, 18));
        y += 22;

        widgets.add(onOffButton("Hide in Screens", cfg::isHideInScreens, cfg::setHideInScreens, cfg::save,
                null, contentX, y, colW, 18));
        widgets.add(SettingsButtonWidget.cycling(visibilityText(cfg), (btn, dir) -> {
                    cfg.setVisibility(dir > 0 ? cfg.getVisibility().next() : cfg.getVisibility().previous());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(colBX, y, colW, 18).build());
        y += 22;

        if (cfg.getVisibility() != InventoryHudConfig.Visibility.ALWAYS) {
            Component keyLabel = capturingKey ? Component.literal("Press any key...") : keyText(cfg);
            widgets.add(SettingsButtonWidget.builder(keyLabel, btn -> {
                        capturingKey = true;
                        btn.setMessage(Component.literal("Press any key..."));
                    }).bounds(contentX, y, contentWidth, 18).build());
        }

        return widgets;
    }

    /** The panel's fill, outline and slot backdrops (killer560, 2026-10-18: split from Item Opacity). */
    private static ThemedSliderButton backgroundOpacitySlider(int x, int y, int width, InventoryHudConfig cfg) {
        return new ThemedSliderButton(x, y, width, 18, backgroundOpacityText(cfg), cfg.getBackgroundOpacity() / 100.0) {
            @Override
            protected void updateMessage() {
                setMessage(backgroundOpacityText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setBackgroundOpacity((int) Math.round(this.value * 100));
                saveOnRelease(() -> cfg.save());
            }
        };
    }

    /** The veil over each item (see InventoryHudFeature#itemVeil). 100% draws no veil. */
    private static ThemedSliderButton itemOpacitySlider(int x, int y, int width, InventoryHudConfig cfg) {
        return new ThemedSliderButton(x, y, width, 18, itemOpacityText(cfg), cfg.getItemOpacity() / 100.0) {
            @Override
            protected void updateMessage() {
                setMessage(itemOpacityText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setItemOpacity((int) Math.round(this.value * 100));
                saveOnRelease(() -> cfg.save());
            }
        };
    }

    private static Component backgroundOpacityText(InventoryHudConfig cfg) {
        return Component.literal("Background Opacity: " + cfg.getBackgroundOpacity() + "%");
    }

    private static Component itemOpacityText(InventoryHudConfig cfg) {
        return Component.literal("Item Opacity: " + cfg.getItemOpacity() + "%");
    }

    private static Component backgroundText(InventoryHudConfig cfg) {
        return Component.literal("Background: §6" + cfg.getBackground().label);
    }

    private static Component visibilityText(InventoryHudConfig cfg) {
        return Component.literal("Show: §6" + cfg.getVisibility().label);
    }

    private static Component keyText(InventoryHudConfig cfg) {
        String name = cfg.getKeyCode() < 0 ? "Not Set"
                : InputConstants.Type.KEYSYM.getOrCreate(cfg.getKeyCode()).getDisplayName().getString();
        return Component.literal("Key: §b" + name);
    }

    @Override
    public boolean isListeningForKey() {
        return capturingKey;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        InventoryHudConfig cfg = InventoryHudConfig.getInstance();
        cfg.setKeyCode(keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
        capturingKey = false;
        cfg.save();
    }
}

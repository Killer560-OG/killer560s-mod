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

        widgets.add(SettingsButtonWidget.builder(onOff("Inventory HUD", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 28;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colBX = contentX + colW + gap;

        widgets.add(SettingsButtonWidget.builder(onOff("Vertical", cfg.isVertical()), btn -> {
                    cfg.setVertical(!cfg.isVertical());
                    cfg.save();
                    btn.setMessage(onOff("Vertical", cfg.isVertical()));
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Mini Mode", cfg.isMiniMode()), btn -> {
                    cfg.setMiniMode(!cfg.isMiniMode());
                    cfg.save();
                    btn.setMessage(onOff("Mini Mode", cfg.isMiniMode()));
                }).bounds(colBX, y, colW, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(backgroundText(cfg), btn -> {
                    cfg.setBackground(cfg.getBackground().next());
                    cfg.save();
                    btn.setMessage(backgroundText(cfg));
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(opacitySlider(colBX, y, colW, cfg));
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Item Counts", cfg.isShowCounts()), btn -> {
                    cfg.setShowCounts(!cfg.isShowCounts());
                    cfg.save();
                    btn.setMessage(onOff("Item Counts", cfg.isShowCounts()));
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Durability Bars", cfg.isShowDurability()), btn -> {
                    cfg.setShowDurability(!cfg.isShowDurability());
                    cfg.save();
                    btn.setMessage(onOff("Durability Bars", cfg.isShowDurability()));
                }).bounds(colBX, y, colW, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Pickup Animation", cfg.isPickupAnimation()), btn -> {
                    cfg.setPickupAnimation(!cfg.isPickupAnimation());
                    cfg.save();
                    btn.setMessage(onOff("Pickup Animation", cfg.isPickupAnimation()));
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Hide When Empty", cfg.isHideWhenEmpty()), btn -> {
                    cfg.setHideWhenEmpty(!cfg.isHideWhenEmpty());
                    cfg.save();
                    btn.setMessage(onOff("Hide When Empty", cfg.isHideWhenEmpty()));
                }).bounds(colBX, y, colW, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Hide in Screens", cfg.isHideInScreens()), btn -> {
                    cfg.setHideInScreens(!cfg.isHideInScreens());
                    cfg.save();
                    btn.setMessage(onOff("Hide in Screens", cfg.isHideInScreens()));
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(visibilityText(cfg), btn -> {
                    cfg.setVisibility(cfg.getVisibility().next());
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

    private static ThemedSliderButton opacitySlider(int x, int y, int width, InventoryHudConfig cfg) {
        return new ThemedSliderButton(x, y, width, 18, opacityText(cfg), cfg.getBackgroundOpacity() / 100.0) {
            @Override
            protected void updateMessage() {
                setMessage(opacityText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setBackgroundOpacity((int) Math.round(this.value * 100));
                cfg.save();
            }
        };
    }

    private static Component opacityText(InventoryHudConfig cfg) {
        return Component.literal("Opacity: " + cfg.getBackgroundOpacity() + "%");
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

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
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

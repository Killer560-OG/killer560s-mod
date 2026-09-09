package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.storageoverlay.StorageOverlayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Storage Overlay settings: the main on/off toggle, dark/light background, and a scale slider - per
 *  killer560's "add a scale bar... this should rescale everything while still keeping my inventory at
 *  the bottom and it centered at the top" request (2026-09-08), one explicit control that resizes both
 *  the grid and the relocated Inventory panel together without moving either one's anchor point (grid
 *  stays pinned near the top, Inventory panel stays pinned to the bottom, both horizontally centered).
 *  Screen POSITION for the grid is still handled by the shared HUD editor (drag to move) like every
 *  other HUD element - only scale moved here. Renaming a storage is done inline in the grid itself
 *  (double-click its title) - per killer560's explicit "that shouldn't be done through /killer560...
 *  double click the actual text and edit it there" request (2026-09-08), this tab has no renaming UI
 *  of its own at all. */
public class StorageOverlayTab extends BaseTab {

    public StorageOverlayTab() {
        super("Storage Overlay");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(enabledText(), btn -> {
                    StorageOverlayConfig cfg = StorageOverlayConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(enabledText());
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(darkModeText(), btn -> {
                    StorageOverlayConfig cfg = StorageOverlayConfig.getInstance();
                    cfg.setDarkMode(!cfg.isDarkMode());
                    cfg.save();
                    btn.setMessage(darkModeText());
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        double scaleNormalized = (StorageOverlayConfig.getInstance().getScale() - StorageOverlayConfig.MIN_SCALE)
                / (StorageOverlayConfig.MAX_SCALE - StorageOverlayConfig.MIN_SCALE);
        widgets.add(new ThemedSliderButton(contentX, y, 220, 20, scaleText(), scaleNormalized) {
            @Override
            protected void updateMessage() {
                setMessage(scaleText());
            }

            @Override
            protected void applyValue() {
                StorageOverlayConfig c = StorageOverlayConfig.getInstance();
                float newScale = (float) (StorageOverlayConfig.MIN_SCALE
                        + this.value * (StorageOverlayConfig.MAX_SCALE - StorageOverlayConfig.MIN_SCALE));
                c.setScale(newScale);
                c.save();
            }
        });
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Opening an Ender Chest page or Backpack logs it and shows every "
                        + "known one in a 3-column grid alongside the menu. Drag it in Edit HUD "
                        + "Positions to move. Double-click a storage's title in the grid to rename it "
                        + "right there."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component enabledText() {
        return Component.literal("Storage Overlay Enabled: "
                + (StorageOverlayConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component darkModeText() {
        return Component.literal("Overlay Theme: "
                + (StorageOverlayConfig.getInstance().isDarkMode() ? "§8Dark" : "§fLight"));
    }

    private static Component scaleText() {
        return Component.literal(String.format("Scale: %.0f%%", StorageOverlayConfig.getInstance().getScale() * 100));
    }
}

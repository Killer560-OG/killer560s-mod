package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.storageoverlay.StorageOverlayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Storage Overlay settings: just the main on/off toggle and dark/light background. Scale and screen
 *  position are handled by the shared HUD editor (scroll to resize, drag to move), like every other
 *  HUD element, rather than duplicated here. Renaming a storage is done inline in the grid itself
 *  (double-click its title) - per killer560's explicit "that shouldn't be done through /killer560...
 *  double click the actual text and edit it there" request (2026-09-08), this tab no longer has any
 *  renaming UI of its own at all. */
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

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Opening an Ender Chest page or Backpack logs it and shows every "
                        + "known one in a 3-column grid alongside the menu. Scroll/drag it in Edit "
                        + "HUD Positions to resize/move. Double-click a storage's title in the grid to "
                        + "rename it right there."),
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
}

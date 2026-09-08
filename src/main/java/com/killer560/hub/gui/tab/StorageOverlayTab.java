package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.notify.ModOverlayMessage;
import com.killer560.hub.storageoverlay.StorageOverlayCache;
import com.killer560.hub.storageoverlay.StorageOverlayConfig;
import com.killer560.hub.storageoverlay.StorageOverlayFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Storage Overlay settings: main on/off toggle, dark/light background, and renaming each known
 *  storage unit for the CURRENT account/SkyBlock profile - per killer560's explicit requirements
 *  (2026-09-08). Scale and screen position are handled by the shared HUD editor (scroll to resize,
 *  drag to move), like every other HUD element, rather than duplicated here. */
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
                        + "HUD Positions to resize/move. Known storages (rename below):"),
                Minecraft.getInstance().font));
        y += 20;

        String prefix = StorageOverlayFeature.accountProfilePrefix();
        List<String> keys = StorageOverlayCache.getInstance().knownKeysFor(prefix);
        if (keys.isEmpty()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7None yet - open an Ender Chest or Backpack to start tracking it."),
                    Minecraft.getInstance().font));
            y += 16;
        }
        for (String key : keys) {
            String realTitle = StorageOverlayFeature.defaultLabelFor(key, prefix);
            StorageOverlayConfig cfg = StorageOverlayConfig.getInstance();
            String current = cfg.getCustomName(key);
            boolean opened = StorageOverlayCache.getInstance().hasContents(key);

            if (!opened) {
                widgets.add(new StringWidget(contentX, y, contentWidth, 10,
                        Component.literal("§7" + realTitle + " - not opened yet"), Minecraft.getInstance().font));
                y += 12;
            }

            EditBox nameField = new EditBox(Minecraft.getInstance().font, contentX, y, contentWidth - 90, 20,
                    Component.literal(realTitle));
            nameField.setMaxLength(48);
            nameField.setValue(current != null ? current : realTitle);
            widgets.add(nameField);

            widgets.add(SettingsButtonWidget.builder(Component.literal("Set"), btn -> {
                        String value = nameField.getValue().trim();
                        cfg.setCustomName(key, value.equals(realTitle) ? null : value);
                        cfg.save();
                        ModOverlayMessage.show("§b[Killer560's Mod] Renamed storage to §e" + nameField.getValue(), 2000);
                    }).bounds(contentX + contentWidth - 84, y, 84, 20).build());
            y += 24;
        }

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

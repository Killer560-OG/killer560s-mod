package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.loadoutkeybinds.LoadoutKeybindsConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Loadout Keybinds settings - see {@link com.killer560.hub.loadoutkeybinds.LoadoutKeybindsFeature}'s
 *  class doc for the real Odin-ported loadout-navigation this is built on. Real "(N/M) Loadout" screen
 *  only - unrelated to any other menu. {@code capturingIndex}: -1 none, 0-11 slot keys, 12 next page,
 *  13 previous page. */
public class LoadoutKeybindsTab extends BaseTab implements KeyCaptureTab {

    private int capturingIndex = -1;

    public LoadoutKeybindsTab() {
        super("Loadout Keybinds");
    }

    @Override
    public boolean isListeningForKey() {
        return capturingIndex != -1;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        LoadoutKeybindsConfig cfg = LoadoutKeybindsConfig.getInstance();
        int key = keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode;
        if (capturingIndex == 12) {
            cfg.setNextPageKey(key);
        } else if (capturingIndex == 13) {
            cfg.setPreviousPageKey(key);
        } else if (capturingIndex >= 0) {
            cfg.setSlotKey(capturingIndex, key);
        }
        capturingIndex = -1;
        cfg.save();
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        LoadoutKeybindsConfig cfg = LoadoutKeybindsConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Loadout Keybinds", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2aX = contentX;
        int col2bX = contentX + col2W + gap;

        widgets.add(SettingsButtonWidget.builder(keyText("Next Page", cfg.getNextPageKey(), 12), btn -> {
                    capturingIndex = 12;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(col2aX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(keyText("Previous Page", cfg.getPreviousPageKey(), 13), btn -> {
                    capturingIndex = 13;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 24;

        for (int i = 0; i < 12; i += 2) {
            int left = i;
            int right = i + 1;
            widgets.add(SettingsButtonWidget.builder(keyText("Slot " + (left + 1), cfg.getSlotKey(left), left), btn -> {
                        capturingIndex = left;
                        btn.setMessage(Component.literal("Press any key..."));
                    }).bounds(col2aX, y, col2W, 18).build());
            widgets.add(SettingsButtonWidget.builder(keyText("Slot " + (right + 1), cfg.getSlotKey(right), right), btn -> {
                        capturingIndex = right;
                        btn.setMessage(Component.literal("Press any key..."));
                    }).bounds(col2bX, y, col2W, 18).build());
            y += 20;
        }
        y += 6;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Only active on the real \"(N/M) Loadout\" screen. Esc clears a bind."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private Component keyText(String label, int key, int index) {
        if (capturingIndex == index) {
            return Component.literal("Press any key...");
        }
        String name = key == -1 ? "Not Set" : InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
        return Component.literal(label + ": " + name);
    }
}

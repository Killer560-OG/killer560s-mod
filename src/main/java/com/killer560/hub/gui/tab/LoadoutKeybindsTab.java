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
 *  13 previous page, 14 the Open Loadouts key. */
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
        applyCapture(keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
    }

    /** killer560 (2026-10-07): "for loadout keybinds I cannot use my special mouse buttons like side mouse buttons".
     *  Stored the same way every other mouse-capable bind is ({@code KeyUtil.codeForMouseButton}). */
    @Override
    public boolean supportsMouseCapture() {
        return true;
    }

    @Override
    public void onMouseCaptured(int button) {
        applyCapture(com.killer560.hub.util.KeyUtil.codeForMouseButton(button));
    }

    private void applyCapture(int key) {
        LoadoutKeybindsConfig cfg = LoadoutKeybindsConfig.getInstance();
        if (capturingIndex == 14) {
            cfg.setOpenKey(key);
        } else if (capturingIndex == 12) {
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

        // killer560, 2026-10-13: "a button to open loadouts (the button should run /loadouts)". Hypixel's command, sent
        // below the client dispatcher (util/ServerCommands), so nothing client-side can catch it; the menu Hypixel opens
        // replaces this screen. Shown whether or not the keybinds are on.
        widgets.add(SettingsButtonWidget.builder(Component.literal(OPEN_LOADOUTS_LABEL), btn -> openLoadouts())
                .bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2aX = contentX;
        int col2bX = contentX + col2W + gap;

        // killer560, 2026-10-18: "a bind to open the loadouts page" - the same /loadouts as the button above.
        widgets.add(SettingsButtonWidget.builder(keyText("Open Loadouts Key", cfg.getOpenKey(), 14), btn -> {
                    capturingIndex = 14;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(keyText("Next Page", cfg.getNextPageKey(), 12), btn -> {
                    capturingIndex = 12;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(col2aX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(keyText("Previous Page", cfg.getPreviousPageKey(), 13), btn -> {
                    capturingIndex = 13;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 24;

        // killer560, 2026-10-13: "a toggle per keybind to have that keybind open the menu and swap then close it or just
        // only swap if you are in the menu. This should be for each keybind." One row per slot: its key, then its mode.
        for (int i = 0; i < 12; i++) {
            int index = i;
            widgets.add(SettingsButtonWidget.builder(keyText("Slot " + (index + 1), cfg.getSlotKey(index), index), btn -> {
                        capturingIndex = index;
                        btn.setMessage(Component.literal("Press any key..."));
                    }).bounds(col2aX, y, col2W, 18).build());
            widgets.add(SettingsButtonWidget.cycling(modeText(cfg, index), (btn, dir) -> {
                        cfg.setSlotMode(index, SettingsButtonWidget.step(cfg.getSlotMode(index), dir));
                        cfg.save();
                        btn.setMessage(modeText(cfg, index));
                    }).bounds(col2bX, y, col2W, 18).build());
            y += 20;
        }
        y += 6;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Only In Menu keys need the Loadout menu open. Esc clears a bind."),
                Minecraft.getInstance().font));

        return widgets;
    }

    /** The Open Loadouts button's label (the testkit presses it by this text). */
    public static final String OPEN_LOADOUTS_LABEL = "Open Loadouts";

    /** Sends {@code /loadouts} to the server; true if it went (false with no connection). */
    public static boolean openLoadouts() {
        return com.killer560.hub.util.ServerCommands.toServer("loadouts");
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    /** A slot's mode button's label; the testkit finds it by "Slot N Mode". */
    public static Component modeText(LoadoutKeybindsConfig cfg, int index) {
        return Component.literal("Slot " + (index + 1) + " Mode: " + cfg.getSlotMode(index).label());
    }

    private Component keyText(String label, int key, int index) {
        if (capturingIndex == index) {
            return Component.literal("Press any key...");
        }
        String name = key == -1 ? "Not Set" : com.killer560.hub.util.KeyUtil.bindDisplayName(key);
        return Component.literal(label + ": " + name);
    }
}

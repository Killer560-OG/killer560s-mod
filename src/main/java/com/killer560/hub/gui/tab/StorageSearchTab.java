package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.storagesearch.IslandChestCache;
import com.killer560.hub.storagesearch.StorageSearchBind;
import com.killer560.hub.storagesearch.StorageSearchConfig;
import com.killer560.hub.storagesearch.StorageSearchEsp;
import com.killer560.hub.storagesearch.StorageSearchExtraCache;
import com.killer560.hub.storagesearch.StorageSearchScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Storage Item Search settings - see {@link com.killer560.hub.storagesearch.StorageSearchFeature}. */
public class StorageSearchTab extends BaseTab implements KeyCaptureTab {

    private static final int NOT_CAPTURING = -1;
    private static final int CAPTURING_NEW = -2;

    /** Which bind row is currently waiting for a key: a real index, {@link #CAPTURING_NEW}, or
     *  {@link #NOT_CAPTURING}. */
    private int capturingIndex = NOT_CAPTURING;
    /** Modifiers pressed so far in the current capture - {@code ModScreen} hands over one key code at a time with
     *  no modifier state, so a combination is built up by staying in capture mode while only modifier keys have
     *  been pressed and committing on the first real key (killer560, 2026-09-21: "multiple keybinds in the sense
     *  of ctrl + f"). */
    private int capturingMods = 0;

    public StorageSearchTab() {
        super("Storage Search");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        StorageSearchConfig cfg = StorageSearchConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Storage Search", cfg.isEnabled()), btn -> {
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

        List<StorageSearchBind> binds = cfg.getBinds();
        for (int i = 0; i < binds.size(); i++) {
            final int index = i;
            Component label = capturingIndex == index
                    ? Component.literal("Press a key" + modsSuffix())
                    : Component.literal("Open Bind " + (i + 1) + ": §b" + binds.get(i).display());
            widgets.add(SettingsButtonWidget.builder(label, btn -> {
                        capturingIndex = index;
                        capturingMods = 0;
                        requestRebuild.run();
                    }).bounds(contentX, y, contentWidth - 24, 18).build());
            widgets.add(SettingsButtonWidget.builder(Component.literal("§cX"), btn -> {
                        cfg.removeBind(index);
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(contentX + contentWidth - 20, y, 20, 18).build());
            y += 22;
        }
        if (binds.size() < StorageSearchConfig.MAX_BINDS) {
            Component label = capturingIndex == CAPTURING_NEW
                    ? Component.literal("Press a key" + modsSuffix())
                    : Component.literal("+ Add Open Bind");
            widgets.add(SettingsButtonWidget.builder(label, btn -> {
                        capturingIndex = CAPTURING_NEW;
                        capturingMods = 0;
                        requestRebuild.run();
                    }).bounds(contentX, y, colW, 18).build());
        }
        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Search"), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new StorageSearchScreen(client.screen, ""));
                }).bounds(colBX, y, colW, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Search Lore", cfg.isSearchLore()), btn -> {
                    cfg.setSearchLore(!cfg.isSearchLore());
                    cfg.save();
                    btn.setMessage(onOff("Search Lore", cfg.isSearchLore()));
                }).bounds(contentX, y, colW, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Include Inventory", cfg.isIncludeInventory()), btn -> {
                    cfg.setIncludeInventory(!cfg.isIncludeInventory());
                    cfg.save();
                    btn.setMessage(onOff("Include Inventory", cfg.isIncludeInventory()));
                }).bounds(colBX, y, colW, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Open on Click", cfg.isOpenOnClick()), btn -> {
                    cfg.setOpenOnClick(!cfg.isOpenOnClick());
                    cfg.save();
                    btn.setMessage(onOff("Open on Click", cfg.isOpenOnClick()));
                }).bounds(contentX, y, colW, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Focus One Storage", cfg.isFocusSingleStorage()), btn -> {
                    cfg.setFocusSingleStorage(!cfg.isFocusSingleStorage());
                    cfg.save();
                    btn.setMessage(onOff("Focus One Storage", cfg.isFocusSingleStorage()));
                }).bounds(colBX, y, colW, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Search Wardrobe & Pets", cfg.isSearchExtras()), btn -> {
                    cfg.setSearchExtras(!cfg.isSearchExtras());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());

        if (cfg.isSearchExtras()) {
            widgets.add(SettingsButtonWidget.builder(
                    Component.literal("Clear Menu Cache: §7" + StorageSearchExtraCache.getInstance().size()), btn -> {
                        StorageSearchExtraCache.getInstance().clear();
                        requestRebuild.run();
                    }).bounds(colBX, y, colW, 18).build());
        }
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Search Island Chests", cfg.isSearchChests()), btn -> {
                    cfg.setSearchChests(!cfg.isSearchChests());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());

        if (!cfg.isSearchChests()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(
                Component.literal("Clear Chest Cache: §7" + IslandChestCache.getInstance().size()), btn -> {
                    IslandChestCache.getInstance().clear();
                    StorageSearchEsp.clear();
                    requestRebuild.run();
                }).bounds(colBX, y, colW, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Chest ESP", cfg.isChestEsp()), btn -> {
                    cfg.setChestEsp(!cfg.isChestEsp());
                    cfg.save();
                    btn.setMessage(onOff("Chest ESP", cfg.isChestEsp()));
                }).bounds(contentX, y, colW, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("ESP Through Walls", cfg.isEspThroughWalls()), btn -> {
                    cfg.setEspThroughWalls(!cfg.isEspThroughWalls());
                    cfg.save();
                    btn.setMessage(onOff("ESP Through Walls", cfg.isEspThroughWalls()));
                }).bounds(colBX, y, colW, 18).build());
        y += 24;

        int radiusRange = StorageSearchConfig.MAX_CHEST_RADIUS - StorageSearchConfig.MIN_CHEST_RADIUS;
        double radiusNormalized = (cfg.getChestRadius() - StorageSearchConfig.MIN_CHEST_RADIUS) / (double) radiusRange;
        widgets.add(new ThemedSliderButton(contentX, y, colW, 20, radiusText(), radiusNormalized) {
            @Override
            protected void updateMessage() {
                setMessage(radiusText());
            }

            @Override
            protected void applyValue() {
                StorageSearchConfig c = StorageSearchConfig.getInstance();
                int snapped = StorageSearchConfig.MIN_CHEST_RADIUS + (int) Math.round(this.value * radiusRange);
                c.setChestRadius(snapped);
                c.save();
                this.value = (snapped - StorageSearchConfig.MIN_CHEST_RADIUS) / (double) radiusRange;
            }
        });

        int espRange = StorageSearchConfig.MAX_ESP_SECONDS - StorageSearchConfig.MIN_ESP_SECONDS;
        double espNormalized = (cfg.getEspSeconds() - StorageSearchConfig.MIN_ESP_SECONDS) / (double) espRange;
        widgets.add(new ThemedSliderButton(colBX, y, colW, 20, espText(), espNormalized) {
            @Override
            protected void updateMessage() {
                setMessage(espText());
            }

            @Override
            protected void applyValue() {
                StorageSearchConfig c = StorageSearchConfig.getInstance();
                int snapped = StorageSearchConfig.MIN_ESP_SECONDS + (int) Math.round(this.value * espRange);
                c.setEspSeconds(snapped);
                c.save();
                this.value = (snapped - StorageSearchConfig.MIN_ESP_SECONDS) / (double) espRange;
            }
        });

        return widgets;
    }

    private String modsSuffix() {
        if (capturingMods == 0) {
            return "...";
        }
        StringBuilder sb = new StringBuilder(" (");
        if ((capturingMods & StorageSearchBind.MOD_CTRL) != 0) {
            sb.append("Ctrl+");
        }
        if ((capturingMods & StorageSearchBind.MOD_SHIFT) != 0) {
            sb.append("Shift+");
        }
        if ((capturingMods & StorageSearchBind.MOD_ALT) != 0) {
            sb.append("Alt+");
        }
        return sb.append("...)").toString();
    }

    private static Component radiusText() {
        return Component.literal("Chest Scan: " + StorageSearchConfig.getInstance().getChestRadius() + " chunks");
    }

    private static Component espText() {
        return Component.literal("ESP Time: " + StorageSearchConfig.getInstance().getEspSeconds() + "s");
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    @Override
    public boolean isListeningForKey() {
        return capturingIndex != NOT_CAPTURING;
    }

    @Override
    public boolean supportsMouseCapture() {
        return true;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        if (keyCode == InputConstants.KEY_ESCAPE) {
            capturingIndex = NOT_CAPTURING;
            capturingMods = 0;
            return;
        }
        // A modifier on its own is never a whole bind - keep listening and fold it into the combination.
        int mod = StorageSearchBind.modBitFor(keyCode);
        if (mod != 0) {
            capturingMods |= mod;
            return;
        }
        commit(new StorageSearchBind(capturingMods, keyCode));
    }

    @Override
    public void onMouseCaptured(int button) {
        commit(new StorageSearchBind(capturingMods, com.killer560.hub.util.KeyUtil.codeForMouseButton(button)));
    }

    private void commit(StorageSearchBind bind) {
        StorageSearchConfig cfg = StorageSearchConfig.getInstance();
        if (capturingIndex == CAPTURING_NEW) {
            cfg.addBind(bind);
        } else if (capturingIndex >= 0) {
            cfg.setBind(capturingIndex, bind);
        }
        capturingIndex = NOT_CAPTURING;
        capturingMods = 0;
        cfg.save();
    }
}

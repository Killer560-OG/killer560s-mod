package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.storagesearch.StorageSearchConfig;
import com.killer560.hub.storagesearch.StorageSearchScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Storage Item Search settings - see {@link com.killer560.hub.storagesearch.StorageSearchFeature}. */
public class StorageSearchTab extends BaseTab implements KeyCaptureTab {

    private boolean capturingKey = false;

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

        Component keyLabel = capturingKey ? Component.literal("Press any key...") : keyText(cfg);
        widgets.add(SettingsButtonWidget.builder(keyLabel, btn -> {
                    capturingKey = true;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(contentX, y, colW, 18).build());

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
                }).bounds(contentX, y, contentWidth, 18).build());

        return widgets;
    }

    private static Component keyText(StorageSearchConfig cfg) {
        String name = cfg.getKeyCode() < 0 ? "Not Set"
                : InputConstants.Type.KEYSYM.getOrCreate(cfg.getKeyCode()).getDisplayName().getString();
        return Component.literal("Open Key: §b" + name);
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
        StorageSearchConfig cfg = StorageSearchConfig.getInstance();
        cfg.setKeyCode(keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
        capturingKey = false;
        cfg.save();
    }
}

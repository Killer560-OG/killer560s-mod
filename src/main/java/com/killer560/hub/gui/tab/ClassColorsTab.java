package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonalerts.DungeonAlertsConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Class Colors settings - split out of {@code DungeonAlertsTab} 2026-09-21 into its own tab per
 *  killer560's menu-structure request ("Terracotta, Spring Boots and Class Colors each get their own
 *  section"). Still backed by {@link DungeonAlertsConfig} - same JSON keys, unchanged - so this is a
 *  GUI-only move. See {@code com.killer560.hub.dungeonalerts.ClassColors} for the real tab-list/nametag
 *  logic. */
public class ClassColorsTab extends BaseTab {

    public ClassColorsTab() {
        super("Class Colors");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        int gap = 8;
        int half = (contentWidth - gap) / 2;
        int colB = contentX + half + gap;
        int y = contentY;

        w.add(SettingsButtonWidget.builder(onOff("Class Colors", cfg.classColorsEnabled), btn -> {
                    cfg.classColorsEnabled = !cfg.classColorsEnabled;
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (cfg.classColorsEnabled) {
            w.add(SettingsButtonWidget.builder(onOff("Tab List", cfg.classColorsTab), btn -> {
                        cfg.classColorsTab = !cfg.classColorsTab;
                        cfg.save();
                        btn.setMessage(onOff("Tab List", cfg.classColorsTab));
                    }).bounds(contentX, y, half, 18).build());
            w.add(SettingsButtonWidget.builder(onOff("Nametags", cfg.classColorsNametags), btn -> {
                        cfg.classColorsNametags = !cfg.classColorsNametags;
                        cfg.save();
                        btn.setMessage(onOff("Nametags", cfg.classColorsNametags));
                    }).bounds(colB, y, half, 18).build());
        }

        return w;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

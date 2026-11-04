package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonalerts.DungeonAlertsConfig;
import net.minecraft.client.gui.components.AbstractWidget;

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

        w.add(onOffButton("Class Colors", () -> cfg.classColorsEnabled, v -> cfg.classColorsEnabled = v, cfg::save, requestRebuild, contentX, y, contentWidth, 20));
        y += 24;

        if (cfg.classColorsEnabled) {
            w.add(onOffButton("Tab List", () -> cfg.classColorsTab, v -> cfg.classColorsTab = v, cfg::save, null, contentX, y, half, 18));
            w.add(onOffButton("Nametags", () -> cfg.classColorsNametags, v -> cfg.classColorsNametags = v, cfg::save, null, colB, y, half, 18));
        }

        return w;
    }
}

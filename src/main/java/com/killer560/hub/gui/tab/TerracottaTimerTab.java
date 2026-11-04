package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonalerts.DungeonAlertsConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Terracotta Timer settings - split out of {@code DungeonAlertsTab} 2026-09-21 into its own tab per
 *  killer560's menu-structure request ("Terracotta, Spring Boots and Class Colors each get their own
 *  section"). Still backed by {@link DungeonAlertsConfig} - same JSON keys, unchanged - so this is a
 *  GUI-only move. See {@code com.killer560.hub.dungeonalerts.TerracottaTimer} for the real F6/M6 boss
 *  countdown logic. */
public class TerracottaTimerTab extends BaseTab {

    public TerracottaTimerTab() {
        super("Terracotta Timer");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();

        w.add(onOffButton("Terracotta Timer", () -> cfg.terracottaEnabled, v -> cfg.terracottaEnabled = v, cfg::save, null, contentX, contentY, contentWidth, 20));

        return w;
    }
}

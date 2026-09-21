package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonalerts.DungeonAlertsConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.network.chat.Component;

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

        w.add(SettingsButtonWidget.builder(onOff("Terracotta Timer", cfg.terracottaEnabled), btn -> {
                    cfg.terracottaEnabled = !cfg.terracottaEnabled;
                    cfg.save();
                    btn.setMessage(onOff("Terracotta Timer", cfg.terracottaEnabled));
                }).bounds(contentX, contentY, contentWidth, 20).build());

        return w;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

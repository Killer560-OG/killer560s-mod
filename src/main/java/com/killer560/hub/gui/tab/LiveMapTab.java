package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.livemap.LiveMapConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Live Dungeon Map settings - see {@link com.killer560.hub.livemap.LiveMapFeature}'s class doc for
 *  the real, database-free room/door detection this is built on, and exactly what it can't show yet
 *  (room names/secrets/mimic detection - all need a room database this session doesn't have). */
public class LiveMapTab extends BaseTab {

    public LiveMapTab() {
        super("Live Map");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        LiveMapConfig cfg = LiveMapConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Live Map", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Show Teammates", cfg.isShowTeammates()), btn -> {
                    cfg.setShowTeammates(!cfg.isShowTeammates());
                    cfg.save();
                    btn.setMessage(onOff("Show Teammates", cfg.isShowTeammates()));
                }).bounds(contentX, y, 160, 18).build());
        y += 20;

        widgets.add(SettingsButtonWidget.builder(onOff("Recolor by Class", cfg.isClassRecolorTeammates()), btn -> {
                    cfg.setClassRecolorTeammates(!cfg.isClassRecolorTeammates());
                    cfg.save();
                    btn.setMessage(onOff("Recolor by Class", cfg.isClassRecolorTeammates()));
                }).bounds(contentX, y, 160, 18).build());
        y += 20;

        widgets.add(SettingsButtonWidget.builder(
                    Component.literal("Cell Size: " + cfg.getCellSize() + "px"), btn -> {
                        int next = cfg.getCellSize() + 2;
                        cfg.setCellSize(next > 16 ? 4 : next);
                        cfg.save();
                        btn.setMessage(Component.literal("Cell Size: " + cfg.getCellSize() + "px"));
                    }).bounds(contentX, y, 160, 18).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Shows real room/door shapes and live positions - no room names,"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7secrets, or mimic detection yet (needs a room database this session"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7doesn't have access to). Recolor by Class uses the Leap Menu's"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7class assignments."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

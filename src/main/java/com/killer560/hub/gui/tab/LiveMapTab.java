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
        y += 20;

        widgets.add(SettingsButtonWidget.builder(roomLabelsText(cfg), btn -> {
                    cfg.setRoomLabels((cfg.getRoomLabels() + 1) % LiveMapConfig.ROOM_LABEL_NAMES.length);
                    cfg.save();
                    btn.setMessage(roomLabelsText(cfg));
                }).bounds(contentX, y, 160, 18).build());
        y += 24;

        // Real bug found and fixed (2026-09-14, pre-testing bug-review pass): this text was written
        // before the 2026-09-13 update that wired real room-name lookups into LiveMapFeature via
        // RoomDatabase - it was stale/misleading (claiming names aren't shown at all, when they now are
        // once the database loads), confirmed against LiveMapFeature's own class doc and its HUD element
        // actually rendering current.name.
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Shows real room/door shapes, live positions, and room names once the"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7room database loads - no secrets or mimic detection yet. Recolor by"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Class uses the Leap Menu's class assignments."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component roomLabelsText(LiveMapConfig cfg) {
        return Component.literal("Room Labels: " + LiveMapConfig.ROOM_LABEL_NAMES[cfg.getRoomLabels()]);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

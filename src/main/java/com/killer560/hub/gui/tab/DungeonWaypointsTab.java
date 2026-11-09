package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonwaypoints.DungeonWaypointsConfig;
import com.killer560.hub.dungeonwaypoints.DungeonWaypointsConfig.Wp;
import com.killer560.hub.dungeonwaypoints.DungeonWaypointsFeature;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Dungeon Waypoints (Secrets folder): your own boxes and lines per room, see {@link DungeonWaypointsFeature}. */
public class DungeonWaypointsTab extends BaseTab {

    public DungeonWaypointsTab() {
        super("Dungeon Waypoints");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        DungeonWaypointsConfig cfg = DungeonWaypointsConfig.getInstance();
        int y = contentY;
        if (masterClosed(w, "Dungeon Waypoints", cfg::isEnabledRaw, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, y, contentWidth)) {
            return w;
        }
        y += 26;
        w.add(onOffButton("Through Walls", cfg::isThroughWalls, cfg::setThroughWalls, cfg::save, null, contentX, y,
                contentWidth, 18));
        y += 22;
        w.add(swatchRow(contentX, y, contentWidth, "New Waypoint Color", cfg.getColor(), DungeonWaypointsConfig.DEFAULT_COLOR,
                argb -> {
                    cfg.setColor(argb);
                    cfg.save();
                }));
        y += 22;
        w.add(SettingsButtonWidget.builder(Component.literal("Copy Pack (all rooms)"), btn -> msg(DungeonWaypointsFeature.export()))
                .bounds(contentX, y, contentWidth / 2 - 2, 18).build());
        w.add(SettingsButtonWidget.builder(Component.literal("Paste Pack"), btn -> {
            msg(DungeonWaypointsFeature.importClipboard());
            requestRebuild.run();
        }).bounds(contentX + contentWidth / 2 + 2, y, contentWidth / 2 - 2, 18).build());
        y += 22;
        w.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal(
                "§7Look at a block: /dwp add [box|line] [hex] | /dwp remove | clear | export | import"),
                Minecraft.getInstance().font));
        y += 16;
        List<Wp> here = new ArrayList<>(DungeonWaypointsFeature.here());
        if (here.isEmpty()) {
            w.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7No waypoints saved for the room you're standing in."), Minecraft.getInstance().font));
            return w;
        }
        w.add(SettingsButtonWidget.builder(Component.literal("§cClear This Room"), btn -> {
            msg(DungeonWaypointsFeature.clearRoom());
            requestRebuild.run();
        }).bounds(contentX, y, 220, 20).build());
        y += 26;
        for (Wp wp : here) {
            String name = (wp.line() ? "Line " : "Box ") + wp.x() + ", " + wp.y() + ", " + wp.z();
            w.add(swatchRow(contentX, y, contentWidth - 24, name, wp.color(), DungeonWaypointsConfig.DEFAULT_COLOR, argb -> {
                cfg.replace(wp, new Wp(wp.room(), wp.x(), wp.y(), wp.z(), argb, wp.line()));
                cfg.save();
            }));
            w.add(SettingsButtonWidget.builder(Component.literal("§cX"), btn -> {
                cfg.remove(wp);
                cfg.save();
                requestRebuild.run();
            }).bounds(contentX + contentWidth - 20, y, 20, 18).build());
            y += 22;
        }
        return w;
    }

    private static void msg(String text) {
        com.killer560.hub.util.ModChat.send("Dungeon Waypoints", Component.literal(text));
    }
}

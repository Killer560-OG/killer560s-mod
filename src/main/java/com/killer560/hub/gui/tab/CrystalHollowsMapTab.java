package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.mining.chmap.CrystalHollowsMapConfig;
import com.killer560.hub.mining.chmap.CrystalHollowsMapScreen;
import com.killer560.hub.mining.chmap.CrystalHollowsWaypoint;
import com.killer560.hub.pathfinding.IslandDetector;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Crystal Hollows Map + Interactive Map settings (killer560, verbatim: "interactive map for ch auto crystal
 * crystal hollows map") - see {@link CrystalHollowsMapScreen}'s class doc for exactly what the map does and
 * does not draw. Not cheat-gated: opening the map and adding waypoints never sends an interaction.
 */
public class CrystalHollowsMapTab extends BaseTab {

    public CrystalHollowsMapTab() {
        super("Crystal Hollows Map");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        var font = Minecraft.getInstance().font;
        CrystalHollowsMapConfig cfg = CrystalHollowsMapConfig.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colBX = contentX + colW + gap;
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Crystal Hollows Map", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (!cfg.isEnabledRaw()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 10,
                    Component.literal("§7A top-down map centred on the Crystal Nucleus's real fixed coordinate,"
                            + " your live position, your saved waypoints, and a click-to-set travel target."), font));
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Map"),
                        btn -> Minecraft.getInstance().setScreen(new CrystalHollowsMapScreen()))
                .bounds(contentX, y, colW, 18).build());
        widgets.add(new ThemedSliderButton(colBX, y, colW, 18, scaleText(cfg),
                (cfg.getMapScale() - CrystalHollowsMapConfig.MIN_SCALE)
                        / (CrystalHollowsMapConfig.MAX_SCALE - CrystalHollowsMapConfig.MIN_SCALE)) {
            @Override
            protected void updateMessage() {
                setMessage(scaleText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setMapScale((float) (CrystalHollowsMapConfig.MIN_SCALE
                        + this.value * (CrystalHollowsMapConfig.MAX_SCALE - CrystalHollowsMapConfig.MIN_SCALE)));
                cfg.save();
            }
        });
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Waypoints", false), font));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Add Waypoint Here"), btn -> {
                    addWaypoint(cfg);
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(
                        Component.literal("Remove Last: " + cfg.getWaypoints().size() + " saved"), btn -> {
                    if (cfg.removeLastWaypoint()) {
                        cfg.save();
                    }
                    requestRebuild.run();
                }).bounds(colBX, y, colW, 18).build());
        y += 22;

        if (!cfg.getWaypoints().isEmpty()) {
            for (CrystalHollowsWaypoint w : cfg.getWaypoints()) {
                widgets.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal("§6- §f" + w.name
                        + " §7(" + String.format(Locale.US, "%.0f, %.0f, %.0f", w.x, w.y, w.z) + ")"), font));
                y += 12;
            }
        }
        return widgets;
    }

    private static void addWaypoint(CrystalHollowsMapConfig cfg) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        int n = cfg.getWaypoints().size() + 1;
        String name = "Waypoint " + n;
        cfg.addWaypoint(new CrystalHollowsWaypoint(name, player.getX(), player.getY(), player.getZ()));
        cfg.save();
        String zone = IslandDetector.scoreboardArea();
        ModChat.send("Crystal Hollows Map", ModChat.text("Added "), ModChat.value(name),
                zone.isEmpty() ? ModChat.text("") : ModChat.dim(" (" + zone + ")"));
    }

    private static Component scaleText(CrystalHollowsMapConfig cfg) {
        return Component.literal(String.format(Locale.US, "Map Scale: %.1fx", cfg.getMapScale()));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

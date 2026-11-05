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
import com.killer560.hub.compat.McCompat;

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

        if (!cfg.isEnabledRaw() && !isSearchScan()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 10,
                    Component.literal("§7A top-down map centered on the Crystal Nucleus's real fixed coordinate,"
                            + " your live position, your saved waypoints, and a click-to-set travel target."), font));
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Map"),
                        btn -> McCompat.setScreen(Minecraft.getInstance(), new CrystalHollowsMapScreen()))
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
                // Rounded to the label's 0.1x, so the knob snaps to the stops the label shows.
                cfg.setMapScale(Math.round((CrystalHollowsMapConfig.MIN_SCALE + (float) this.value
                        * (CrystalHollowsMapConfig.MAX_SCALE - CrystalHollowsMapConfig.MIN_SCALE)) * 10f) / 10f);
                cfg.save();
            }
        });
        y += 22;
        // The map as a HUD element in the Crystal Hollows, like Skyblocker's Crystals HUD (moved and sized in Edit HUD).
        widgets.add(onOffButton("Map HUD", cfg::isHudEnabled, cfg::setHudEnabled, cfg::save, null,
                contentX, y, colW, 18));
        widgets.add(onOffButton("Show Zones", cfg::isShowZones, cfg::setShowZones, cfg::save, null,
                colBX, y, colW, 18));
        y += 22;
        widgets.add(onOffButton("Names On HUD", cfg::isHudNames, cfg::setHudNames, cfg::save, null,
                contentX, y, colW, 18));
        y += 24;

        // Structures he has found in this lobby, and what to draw for them.
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Structures", false), font));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(onOff("Show Borders", cfg.isShowBorders()), btn -> {
                    cfg.setShowBorders(!cfg.isShowBorders());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(
                onOff("Show Waypoints", cfg.isShowStructureWaypoints()), btn -> {
                    cfg.setShowStructureWaypoints(!cfg.isShowStructureWaypoints());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(colBX, y, colW, 18).build());
        y += 22;

        // Found so far, so he can see at a glance whether the map knows about somewhere yet - and whether it
        // learned it firsthand or was told.
        var finds = com.killer560.hub.mining.chmap.ChDiscovery.all();
        if (!finds.isEmpty()) {
            for (var f : finds) {
                // A scanned find is just its name (killer560, 2026-11-04: "remove the mines of divan (scanned) line").
                String how = switch (f.source) {
                    case VISITED -> " §7(§avisited§7)";
                    case SCANNED -> "";
                    case SHARED -> " §7(§bshared§7)";
                };
                widgets.add(new StringWidget(contentX, y, contentWidth, 10,
                        Component.literal("§6- §f" + f.structure.displayName + how), font));
                y += 12;
            }
            y += 2;
        }

        // Sharing, under the map, on its own switch - see CrystalHollowsMapConfig for why this is not the
        // mod-wide one.
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Sharing", false), font));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(onOff("Share My Finds", cfg.isShareWaypoints()), btn -> {
                    cfg.setShareWaypoints(!cfg.isShareWaypoints());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(
                onOff("Receive Others'", cfg.isReceiveWaypoints()), btn -> {
                    cfg.setReceiveWaypoints(!cfg.isReceiveWaypoints());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(colBX, y, colW, 18).build());
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

}

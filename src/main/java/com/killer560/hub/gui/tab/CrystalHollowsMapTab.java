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
        if (finds.isEmpty()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 10,
                    Component.literal("§7Nothing found in this lobby yet - walk into a structure and it "
                            + "appears here."), font));
            y += 14;
        } else {
            for (var f : finds) {
                String how = switch (f.source) {
                    case VISITED -> "§avisited";
                    case SCANNED -> "§escanned";
                    case SHARED -> "§bshared";
                };
                widgets.add(new StringWidget(contentX, y, contentWidth, 10,
                        Component.literal("§6- §f" + f.structure.displayName + " §7(" + how + "§7)"), font));
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
        y += 20;

        // Whether sharing can actually work right now. Without this the feature has three quiet failure
        // states that all look identical from here - not in the Crystal Hollows, relay down, or Hypixel not
        // having said which lobby this is yet - and "nobody else's finds appeared" would read as a bug.
        String share = com.killer560.hub.mining.chmap.ChShare.status();
        widgets.add(new StringWidget(contentX, y, contentWidth, 10,
                Component.literal((share.startsWith("connected") ? "§a" : "§e") + share), font));
        y += 14;

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

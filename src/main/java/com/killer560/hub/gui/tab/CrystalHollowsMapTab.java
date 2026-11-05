package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.CommitEditBox;
import com.killer560.hub.gui.ModuleKeybinds;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.mining.scanner.StructureScannerConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.mining.chmap.CrystalHollowsMapConfig;
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
 * crystal hollows map") - see {@link com.killer560.hub.mining.chmap.CrystalHollowsMapScreen}'s class doc for exactly what the map does and
 * does not draw. Not cheat-gated: opening the map and adding waypoints never sends an interaction.
 */
public class CrystalHollowsMapTab extends BaseTab {

    public CrystalHollowsMapTab() {
        super("Crystal Hollows Map");
    }

    /** The map's own switch, plus the cheat-only Structure Scanner's (an independent feature, built even while the map is off). */
    @Override
    public List<com.killer560.hub.gui.SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        List<com.killer560.hub.gui.SettingsButtonWidget> out = new ArrayList<>(super.masterToggles(built));
        out.addAll(switchRows(built, "Structure Scanner"));
        return out;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = mapWidgets(contentX, contentY, contentWidth, requestRebuild);
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            int y = contentY;
            for (AbstractWidget w : widgets) {
                y = Math.max(y, w.getY() + w.getHeight());
            }
            scannerWidgets(widgets, contentX, y + 6, contentWidth, requestRebuild);
        }
        return widgets;
    }

    /** Structure Scanner (cheat only; reads blocks, never acts): its own config and switch, at the bottom of the map's tab
     *  under a red Cheat header (killer560, 2026-11-05: "structure scanner should not be here it should be inside of the
     *  crystal hollows map at the bottom as a cheat setting"). Its toggle key keeps the old card's id. */
    private void scannerWidgets(List<AbstractWidget> w, int x, int y, int width, Runnable requestRebuild) {
        StructureScannerConfig cfg = StructureScannerConfig.getInstance();
        int half = (width - 8) / 2;
        int colB = x + half + 8;
        w.add(new StringWidget(x, y, width, 12, SectionHeaders.header("Cheat", true), Minecraft.getInstance().font));
        y += 16;
        w.add(onOffButton("Structure Scanner", cfg::isEnabled, cfg::setEnabled, cfg::save, requestRebuild, x, y, width, 18));
        y += 22;
        if (!cfg.isEnabled() && !isSearchScan()) {
            return;
        }
        w.add(onOffButton("Structure Waypoints", cfg::isWaypoints, cfg::setWaypoints, cfg::save, null, x, y, half, 18));
        w.add(onOffButton("Chat On Find", cfg::isChatOnFind, cfg::setChatOnFind, cfg::save, null, colB, y, half, 18));
        y += 22;
        w.add(onOffButton("Show On Map", cfg::isShowOnMap, cfg::setShowOnMap, cfg::save, null, x, y, half, 18));
        w.add(onOffButton("Fairy Grottos", cfg::isGrottos, cfg::setGrottos, cfg::save, null, colB, y, half, 18));
        y += 22;
        w.add(ModuleKeybinds.row(ModuleKeybinds.STRUCTURE_SCANNER_ID, x, y, width));
    }

    private List<AbstractWidget> mapWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
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

        widgets.add(onOffButton("Show Zones", cfg::isShowZones, cfg::setShowZones, cfg::save, null,
                contentX, y, colW, 18));
        widgets.add(onOffButton("Names On HUD", cfg::isHudNames, cfg::setHudNames, cfg::save, null,
                colBX, y, colW, 18));
        y += 22;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, nameSizeText(cfg),
                (cfg.getHudNameSize() - CrystalHollowsMapConfig.MIN_NAME_SIZE)
                        / (CrystalHollowsMapConfig.MAX_NAME_SIZE - CrystalHollowsMapConfig.MIN_NAME_SIZE)) {
            @Override
            protected void updateMessage() {
                setMessage(nameSizeText(cfg));
            }

            @Override
            protected void applyValue() {
                // Rounded to the label's 0.1x, so the knob snaps to the stops the label shows.
                cfg.setHudNameSize(Math.round((CrystalHollowsMapConfig.MIN_NAME_SIZE + (float) this.value
                        * (CrystalHollowsMapConfig.MAX_NAME_SIZE - CrystalHollowsMapConfig.MIN_NAME_SIZE)) * 10f) / 10f);
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
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;
        for (int i = 0; i < cfg.getWaypoints().size(); i++) {
            y = waypointRows(widgets, cfg, cfg.getWaypoints().get(i), i, contentX, y, contentWidth, requestRebuild);
        }
        return widgets;
    }

    /** One saved waypoint: name box + color + Remove, then X / Y / Z boxes with one Set. Enter in any box saves. */
    private static int waypointRows(List<AbstractWidget> widgets, CrystalHollowsMapConfig cfg, CrystalHollowsWaypoint w,
                                    int i, int x, int y, int width, Runnable rebuild) {
        var font = Minecraft.getInstance().font;
        int gap = 4;
        int btnW = Math.max(60, width / 5);
        int nameW = width - 2 * btnW - 2 * gap;
        CommitEditBox name = new CommitEditBox(font, x, y, nameW, 18, Component.literal("Waypoint name"));
        name.setMaxLength(32);
        name.setValue(w.name);
        name.onEnter(() -> {
            String v = name.getValue().trim();
            if (!v.isEmpty()) {
                w.name = v;
                cfg.save();
            }
        });
        widgets.add(name);
        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Color", w.drawColor(DEFAULT_WAYPOINT_COLOR)), btn ->
                McCompat.setScreen(Minecraft.getInstance(), new ColorPickerScreen(McCompat.screen(Minecraft.getInstance()),
                        "Waypoint Color", w.drawColor(DEFAULT_WAYPOINT_COLOR), 0, argb -> {
                    w.color = argb == 0 || argb == com.killer560.hub.gui.ThemeAccent.accent(DEFAULT_WAYPOINT_COLOR) ? 0 : argb;
                    cfg.save();
                }))).bounds(x + nameW + gap, y, btnW, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Remove"), btn -> {
                    cfg.removeWaypoint(i);
                    cfg.save();
                    rebuild.run();
                }).bounds(x + nameW + btnW + 2 * gap, y, btnW, 18).build());
        y += 22;
        int setW = 40;
        int boxW = (width - setW - 3 * gap) / 3;
        CommitEditBox[] c = new CommitEditBox[3];
        double[] v = {w.x, w.y, w.z};
        String[] axis = {"X", "Y", "Z"};
        Runnable commit = () -> {
            double[] n = new double[3];
            for (int k = 0; k < 3; k++) {
                try {
                    n[k] = Double.parseDouble(c[k].getValue().trim());
                } catch (NumberFormatException e) {
                    return;
                }
            }
            w.x = n[0];
            w.y = n[1];
            w.z = n[2];
            cfg.save();
        };
        for (int k = 0; k < 3; k++) {
            c[k] = new CommitEditBox(font, x + k * (boxW + gap), y, boxW, 18, Component.literal("Waypoint " + axis[k]));
            c[k].setValue(String.format(Locale.US, "%.0f", v[k]));
            c[k].onEnter(commit);
            widgets.add(c[k]);
        }
        widgets.add(SettingsButtonWidget.builder(Component.literal("Set"), btn -> commit.run())
                .bounds(x + width - setW, y, setW, 18).build());
        return y + 24;
    }

    private static final int DEFAULT_WAYPOINT_COLOR = 0xFFCC6600;

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

    private static Component nameSizeText(CrystalHollowsMapConfig cfg) {
        return Component.literal(String.format(Locale.US, "HUD Name Size: %.1fx", cfg.getHudNameSize()));
    }

}

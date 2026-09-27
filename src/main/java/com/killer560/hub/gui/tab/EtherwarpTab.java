package com.killer560.hub.gui.tab;

import com.killer560.hub.etherwarp.EtherwarpFeature;
import com.killer560.hub.etherwarp.EtherwarpWaypoint;
import com.killer560.hub.etherwarp.EtherwarpWaypointsConfig;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Etherwarp/secret-spot waypoint settings - see {@link EtherwarpFeature}'s class doc for the 2026-09-27
 *  room-relative rewrite (why these now persist across restarts, and why the list below only ever shows
 *  the room you're currently standing in). Use "/killer560 ew add &lt;name&gt;" in-game while looking at
 *  the spot you want to remember; this tab just shows/manages what's been added. Moved from a top-level
 *  {@code NewTab} entry into the "Secrets" folder 2026-09-21 (see {@link SecretsTab}) per killer560's
 *  menu-structure request - no behaviour change, just a different accordion home. */
public class EtherwarpTab extends BaseTab {

    public EtherwarpTab() {
        super("Etherwarp Waypoints");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        EtherwarpWaypointsConfig cfg = EtherwarpWaypointsConfig.getInstance();

        // The section toggle (killer560, 2026-09-21: "make a toggle for that section"); the old "Show HUD list"
        // option is gone with the HUD list itself.
        widgets.add(SettingsButtonWidget.builder(masterText(), btn -> {
                    cfg.setHighlightBlocks(!cfg.isHighlightBlocks());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;
        if (!cfg.isHighlightBlocks()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(styleText(cfg), btn -> {
                    cfg.setStyle(cfg.getStyle().next());
                    cfg.save();
                    btn.setMessage(styleText(cfg));
                }).secondaryPress(btn -> {
                    cfg.setStyle(cfg.getStyle().previous());
                    cfg.save();
                    btn.setMessage(styleText(cfg));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Through Walls", cfg.isThroughWalls()), btn -> {
                    cfg.setThroughWalls(!cfg.isThroughWalls());
                    cfg.save();
                    btn.setMessage(onOff("Through Walls", cfg.isThroughWalls()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        widgets.add(colorButton(contentX, y, contentWidth, "Color", cfg.getColor(),
                EtherwarpWaypointsConfig.DEFAULT_COLOR, cfg::setColor));
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7/ew waypoint add [name] | remove | undo | clear"), Minecraft.getInstance().font));
        y += 16;

        // killer560, 2026-09-27: "Make sure they dont save based off of location but off of location in a
        // room... If i do clear it should only clear the ones in the room I am in." So this list, and the
        // clear button below it, are scoped to the room you're standing in right now - never the whole
        // saved set (see EtherwarpFeature.waypointsHere()/clearCurrentRoom()).
        List<EtherwarpWaypoint> waypoints = EtherwarpFeature.waypointsHere();
        if (waypoints.isEmpty()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7No waypoints saved for the room you're standing in."),
                    Minecraft.getInstance().font));
            y += 16;
        } else {
            widgets.add(SettingsButtonWidget.builder(Component.literal("§cClear This Room"), btn -> {
                        EtherwarpFeature.clearCurrentRoom();
                        requestRebuild.run();
                    }).bounds(contentX, y, 220, 20).build());
            y += 26;

            for (EtherwarpWaypoint w : new ArrayList<>(waypoints)) {
                widgets.add(new StringWidget(contentX, y, 200, 12,
                        Component.literal("§b#" + w.order + " §f" + w.name),
                        Minecraft.getInstance().font));
                widgets.add(SettingsButtonWidget.builder(Component.literal("§cX"), btn -> {
                            EtherwarpFeature.remove(w.id);
                            requestRebuild.run();
                        }).bounds(contentX + 208, y - 2, 20, 16).build());
                y += 16;
            }
        }

        return widgets;
    }

    private static AbstractWidget colorButton(int x, int y, int width, String name, int current, int defaultColor,
                                              java.util.function.IntConsumer setter) {
        return SettingsButtonWidget.builder(ColorSwatch.label(name, current), btn -> {
            Minecraft client = Minecraft.getInstance();
            client.setScreen(new ColorPickerScreen(client.screen, name, current, defaultColor, argb -> {
                setter.accept(argb);
                EtherwarpWaypointsConfig.getInstance().save();
            }));
        }).bounds(x, y, width, 18).build();
    }

    private static Component styleText(EtherwarpWaypointsConfig cfg) {
        return Component.literal("Style: §b" + cfg.getStyle().label);
    }

    private static Component masterText() {
        return Component.literal("Etherwarp Waypoints: " + (EtherwarpWaypointsConfig.getInstance().isHighlightBlocks() ? "§aON" : "§cOFF"));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

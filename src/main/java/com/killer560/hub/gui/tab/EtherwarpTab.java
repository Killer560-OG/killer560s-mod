package com.killer560.hub.gui.tab;

import com.killer560.hub.etherwarp.EtherwarpFeature;
import com.killer560.hub.etherwarp.EtherwarpWaypoint;
import com.killer560.hub.etherwarp.EtherwarpWaypointsConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Etherwarp/secret-spot waypoint settings - see {@link EtherwarpFeature}'s class doc for why these are
 *  per-run only (never saved to disk, never sent anywhere). Use "/killer560 ew add &lt;name&gt;" in-game
 *  while looking at the spot you want to remember; this tab just shows/manages what's been added. Moved
 *  from a top-level {@code NewTab} entry into the "Secrets" folder 2026-09-21 (see {@link SecretsTab}) per
 *  killer560's menu-structure request - no behaviour change, just a different accordion home. The two
 *  in-panel instructional lines were dropped the same day (mod-wide in-panel-paragraph cleanup): the
 *  master toggle's "show hud list" tooltip already covers both. */
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
        y += 24;
        if (!cfg.isHighlightBlocks()) {
            return widgets;
        }
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("\u00a77/ew waypoint add [name] | remove | undo | clear"), Minecraft.getInstance().font));
        y += 16;

        List<EtherwarpWaypoint> waypoints = EtherwarpFeature.waypoints();
        if (waypoints.isEmpty()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7No waypoints added this run."),
                    Minecraft.getInstance().font));
            y += 16;
        } else {
            widgets.add(SettingsButtonWidget.builder(Component.literal("§cClear All"), btn -> {
                        EtherwarpFeature.clear();
                        requestRebuild.run();
                    }).bounds(contentX, y, 220, 20).build());
            y += 26;

            for (EtherwarpWaypoint w : new ArrayList<>(waypoints)) {
                widgets.add(new StringWidget(contentX, y, 200, 12,
                        Component.literal(String.format(Locale.US, "%s (%.0f, %.0f, %.0f)", w.name, w.x, w.y, w.z)),
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

    private static Component masterText() {
        return Component.literal("Etherwarp Waypoints: " + (EtherwarpWaypointsConfig.getInstance().isHighlightBlocks() ? "§aON" : "§cOFF"));
    }
}

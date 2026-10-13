package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.puzzlesolvers.SolverEspConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** The one setting every puzzle/boss solver's world highlight shares - see
 *  {@link com.killer560.hub.puzzlesolvers.SolverEspConfig}. killer560, 2026-09-20: "make all solvers ESP
 *  based for the waypoints"; one switch rather than eleven copies of the same toggle. */
public class SolverHighlightsTab extends BaseTab {

    public SolverHighlightsTab() {
        super("Solver Highlights");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        SolverEspConfig cfg = SolverEspConfig.getInstance();
        int y = contentY;

        // Solver Highlights as a whole (killer560, 2026-10-13): first, so it is the card's switch. Off, no solver draws
        // a world highlight (SolverEspRender) and the two shared settings below are hidden.
        widgets.add(SettingsButtonWidget.builder(onOff("Solver Highlights", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        if (!cfg.isEnabled()) {
            return widgets;
        }
        widgets.add(SettingsButtonWidget.builder(onOff("Through Walls", cfg.isThroughWalls()), btn -> {
                    cfg.setThroughWalls(!cfg.isThroughWalls());
                    cfg.save();
                    btn.setMessage(onOff("Through Walls", cfg.isThroughWalls()));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        widgets.add(SettingsButtonWidget.cycling(styleText(cfg), (btn, dir) -> {
                    cfg.setWaypointStyle(SettingsButtonWidget.step(cfg.getWaypointStyle(), dir));
                    cfg.save();
                    btn.setMessage(styleText(cfg));
                }).bounds(contentX, y, contentWidth, 20).build());

        return widgets;
    }

    private static Component styleText(SolverEspConfig cfg) {
        return Component.literal("Waypoint Style: \u00a76" + cfg.getWaypointStyle().label);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

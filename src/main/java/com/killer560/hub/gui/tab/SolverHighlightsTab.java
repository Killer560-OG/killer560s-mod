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

        widgets.add(SettingsButtonWidget.builder(onOff("Through Walls", cfg.isThroughWalls()), btn -> {
                    cfg.setThroughWalls(!cfg.isThroughWalls());
                    cfg.save();
                    btn.setMessage(onOff("Through Walls", cfg.isThroughWalls()));
                }).bounds(contentX, contentY, contentWidth, 20).build());
        widgets.add(SettingsButtonWidget.builder(styleText(cfg), btn -> {
                    cfg.setWaypointStyle(cfg.getWaypointStyle().next());
                    cfg.save();
                    btn.setMessage(styleText(cfg));
                }).bounds(contentX, contentY + 24, contentWidth, 20).build());

        return widgets;
    }

    private static Component styleText(SolverEspConfig cfg) {
        return Component.literal("Waypoint Style: \u00a76" + cfg.getWaypointStyle().label);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

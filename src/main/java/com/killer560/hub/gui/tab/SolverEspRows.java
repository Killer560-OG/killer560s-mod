package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.puzzlesolvers.SolverEspConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * A solver's own world-highlight rows (killer560, 2026-10-18: "remove solver highlights and put each of those as an
 * individual option in each of the solvers"): Solver Highlight, Solver Highlight Through Walls and, for a solver that draws
 * waypoints, Solver Highlight Style. They read and write that solver's entry in {@link SolverEspConfig}, and every solver
 * tab adds them with {@link #append} under its own settings. The labels are not "Through Walls" / "Highlight Style": the
 * tooltip table is keyed by label, a bare "through walls" is the Dungeon ESP one and a bare "highlight style" is Breaker
 * Aura's (Tic Tac Toe has its own Highlight Style row too).
 */
final class SolverEspRows {

    private SolverEspRows() {
    }

    /** Adds the rows one section under everything already in {@code widgets}. {@code waypoints}: the solver draws waypoints. */
    static void append(List<AbstractWidget> widgets, SolverEspConfig.Solver solver, int x, int width, int fallbackY,
                       boolean waypoints) {
        append(widgets, solver, x, width, fallbackY, waypoints, true);
    }

    /** As {@link #append(List, SolverEspConfig.Solver, int, int, int, boolean)}; {@code throughWallsRow} false leaves the
     *  Through Walls row out, for a tab that puts it somewhere else (Livid's is cheat-only, at the bottom of its tab). */
    static void append(List<AbstractWidget> widgets, SolverEspConfig.Solver solver, int x, int width, int fallbackY,
                       boolean waypoints, boolean throughWallsRow) {
        int y = fallbackY;
        for (AbstractWidget w : widgets) {
            y = Math.max(y, w.getY() + w.getHeight() + 6);
        }
        SolverEspConfig cfg = SolverEspConfig.getInstance();
        widgets.add(new StringWidget(x, y, width, 12, SectionHeaders.header("Solver Highlight", false),
                Minecraft.getInstance().font));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(BaseTab.onOff("Solver Highlight", cfg.isEnabled(solver)), btn -> {
                    cfg.setEnabled(solver, !cfg.isEnabled(solver));
                    cfg.save();
                    btn.setMessage(BaseTab.onOff("Solver Highlight", cfg.isEnabled(solver)));
                }).bounds(x, y, width, 18).build());
        y += 22;
        if (throughWallsRow) {
            throughWallsRow(widgets, solver, x, width, y);
            y += 22;
        }
        if (waypoints) {
            widgets.add(SettingsButtonWidget.cycling(styleText(cfg, solver), (btn, dir) -> {
                        cfg.setWaypointStyle(solver, SettingsButtonWidget.step(cfg.getWaypointStyle(solver), dir));
                        cfg.save();
                        btn.setMessage(styleText(cfg, solver));
                    }).bounds(x, y, width, 18).build());
        }
    }

    /** The Through Walls row alone, at {@code y}. */
    static void throughWallsRow(List<AbstractWidget> widgets, SolverEspConfig.Solver solver, int x, int width, int y) {
        SolverEspConfig cfg = SolverEspConfig.getInstance();
        widgets.add(SettingsButtonWidget.builder(BaseTab.onOff("Solver Highlight Through Walls", cfg.isThroughWalls(solver)), btn -> {
                    cfg.setThroughWalls(solver, !cfg.isThroughWalls(solver));
                    cfg.save();
                    btn.setMessage(BaseTab.onOff("Solver Highlight Through Walls", cfg.isThroughWalls(solver)));
                }).bounds(x, y, width, 18).build());
    }

    private static Component styleText(SolverEspConfig cfg, SolverEspConfig.Solver solver) {
        return Component.literal("Solver Highlight Style: §6" + cfg.getWaypointStyle(solver).label);
    }
}

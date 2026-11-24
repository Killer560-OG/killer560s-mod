package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.puzzlesolvers.SolverEspConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * A solver's own world-highlight rows (killer560, 2026-10-18: "remove solver highlights and put each of those as an
 * individual option in each of the solvers"): Solver Highlight, Solver Highlight Through Walls and, for a solver that draws
 * waypoints, Solver Highlight Style. They read and write that solver's entry in {@link SolverEspConfig}, and every solver
 * tab adds them with {@link #append} under its own settings. The labels are not "Through Walls" / "Highlight Style": the
 * tooltip table is keyed by label, a bare "through walls" is the Dungeon Mob Highlight one and a bare "highlight style" is Breaker
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
        widgets.add(SectionHeaders.row(x, y, width, "Solver Highlight", false));
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
            y += 22;
        }
        ColorRow[] colors = COLORS.get(solver);
        for (int slot = 0; colors != null && slot < colors.length; slot++) {
            int fslot = slot;
            ColorRow row = colors[slot];
            int shown = cfg.getColor(solver, slot) != 0 ? cfg.getColor(solver, slot) : row.argb;
            widgets.add(BaseTab.swatchRow(x, y, width, row.label, shown, row.argb, argb -> {
                cfg.setColor(solver, fslot, argb);
                cfg.save();
            }));
            y += 22;
        }
        // killer560, 2026-11-23: "add" (settings gap 10): 0 = each box and line keeps the width it always had.
        widgets.add(BaseTab.valueSlider(x, y, width, "Solver Highlight Line Width", "", 1, 6, 0.5, 1,
                () -> cfg.getLineWidth(solver) > 0 ? cfg.getLineWidth(solver) : 2.0, v -> cfg.setLineWidth(solver, (float) v),
                cfg::save));
    }

    /** One overridable color of a solver: its row label and the color it is drawn in today (the picker's default). */
    private record ColorRow(String label, int argb) {
        ColorRow(String label, float r, float g, float b) {
            this(label, 0xFF000000 | Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255));
        }
    }

    /** The rows per solver, in the order of the color slots its feature passes to SolverEspRender (gaps-colors-1124). */
    private static final java.util.Map<SolverEspConfig.Solver, ColorRow[]> COLORS = java.util.Map.of(
            SolverEspConfig.Solver.BOULDER, new ColorRow[]{new ColorRow("Solver Highlight Color", 0.4f, 0.9f, 1.0f)},
            SolverEspConfig.Solver.QUIZ, new ColorRow[]{new ColorRow("Solver Answer Color", 0.2f, 1.0f, 0.3f)},
            SolverEspConfig.Solver.WEIRDOS, new ColorRow[]{new ColorRow("Solver Correct Chest Color", 0.2f, 1.0f, 0.3f),
                    new ColorRow("Solver Wrong Chest Color", 1.0f, 0.2f, 0.2f)},
            SolverEspConfig.Solver.ICE_PATH, new ColorRow[]{new ColorRow("Solver Path Color", 0.33f, 1.0f, 0.33f),
                    new ColorRow("Solver Next Box Color", 1.0f, 0.33f, 0.33f)},
            SolverEspConfig.Solver.WATER, new ColorRow[]{new ColorRow("Solver Ready Lever Color", 1f / 3, 1.0f, 1f / 3),
                    new ColorRow("Solver Waiting Lever Color", 1.0f, 1.0f, 1f / 3), new ColorRow("Solver Lever Line Color", 1.0f, 0.8f, 0.2f)},
            SolverEspConfig.Solver.BLAZE, new ColorRow[]{new ColorRow("Solver First Blaze Color", 0.2f, 1.0f, 0.2f),
                    new ColorRow("Solver Second Blaze Color", 1.0f, 1.0f, 0.2f), new ColorRow("Solver Third Blaze Color", 1.0f, 0.2f, 0.2f),
                    new ColorRow("Solver Other Blazes Color", 1.0f, 1.0f, 1.0f)},
            SolverEspConfig.Solver.TIC_TAC_TOE, new ColorRow[]{new ColorRow("Solver Best Move Color", 0.33f, 1.0f, 0.33f),
                    new ColorRow("Solver Predicted Move Color", 1.0f, 1.0f, 0.33f)},
            SolverEspConfig.Solver.BEAMS, new ColorRow[]{new ColorRow("Solver Misaligned Pair Color", 1.0f, 0.15f, 0.15f)});

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

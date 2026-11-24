package com.killer560.hub.gui.tab;

import com.killer560.hub.foraging.SweepOverlay;

/** Highlight Optimal Blocks (Foraging): see {@link SweepOverlay}. */
public class SweepOptimalTab extends IslandFeatureTab {

    public SweepOptimalTab() {
        super("Highlight Optimal Blocks", SweepOverlay.OPT, false, "Boxes the best log to hit.");
    }

    @Override
    protected void rows(Rows r) {
        r.color("Color", "color", SweepOverlay.DEFAULT_COLOR);
    }
}

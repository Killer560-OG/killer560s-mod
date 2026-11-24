package com.killer560.hub.gui.tab;

import com.killer560.hub.foraging.SweepOverlay;

/** Sweep Overlay (Foraging): see {@link SweepOverlay}. */
public class SweepOverlayTab extends IslandFeatureTab {

    public SweepOverlayTab() {
        super("Sweep Overlay", SweepOverlay.CFG, false, "Shows what a swing fells.");
    }

    @Override
    protected void rows(Rows r) {
        r.color("Color", "color", SweepOverlay.DEFAULT_COLOR);
        r.slider("Sweep override", "sweep", "");
        r.toggle("Thrown axe", "thrown");
    }
}

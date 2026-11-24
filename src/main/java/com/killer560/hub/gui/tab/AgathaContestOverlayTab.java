package com.killer560.hub.gui.tab;

/** Agatha Contest Overlay (Foraging): see {@link com.killer560.hub.foraging.AgathaContest}. */
public class AgathaContestOverlayTab extends IslandFeatureTab {

    public AgathaContestOverlayTab() {
        super("Agatha Contest Overlay", com.killer560.hub.foraging.AgathaContest.CFG, false,
                "Tracks your contest score.");
    }

    @Override
    protected void rows(Rows r) {
        r.toggle("Show Final Result", "final");
        r.note("Move it in the HUD editor.");
    }
}

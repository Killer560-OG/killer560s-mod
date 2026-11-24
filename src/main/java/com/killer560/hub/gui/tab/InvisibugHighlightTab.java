package com.killer560.hub.gui.tab;

import com.killer560.hub.foraging.InvisibugHighlight;

/** Invisibug Highlight settings: see {@link InvisibugHighlight}. */
public class InvisibugHighlightTab extends IslandFeatureTab {

    public InvisibugHighlightTab() {
        super("Invisibug Highlight", InvisibugHighlight.CFG, false, "Marks Invisibugs.");
    }

    @Override
    protected void rows(Rows r) {
        r.color("Box Color", "color", 0);
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            r.header("Cheat", true);
            r.toggle("Through Walls", "throughWalls");
        }
    }
}

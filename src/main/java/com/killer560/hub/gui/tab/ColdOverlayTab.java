package com.killer560.hub.gui.tab;

import com.killer560.hub.mining.cold.ColdOverlay;

/** Cold Overlay settings: see {@link ColdOverlay}. */
public class ColdOverlayTab extends IslandFeatureTab {

    public ColdOverlayTab() {
        super("Cold Overlay", ColdOverlay.CFG, false, "Frost on screen as Cold rises.");
    }

    @Override
    protected void rows(Rows r) {
        r.slider("Strength", "strength", "%");
    }
}

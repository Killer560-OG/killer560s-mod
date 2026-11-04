package com.killer560.hub.gui.tab;

import com.killer560.hub.mining.skymall.SkyMallDisplay;

/** Sky Mall Display settings: see {@link SkyMallDisplay}. */
public class SkyMallDisplayTab extends IslandFeatureTab {

    public SkyMallDisplayTab() {
        super("Sky Mall Display", SkyMallDisplay.CFG, false, "Today's Sky Mall buff on a HUD.");
    }

    @Override
    protected void rows(Rows r) {
        r.toggle("Mining Islands Only", "miningOnly");
        r.cycle("Alert On", "alert", SkyMallDisplay.Buff.values().length + 1, SkyMallDisplay::alertLabel);
        r.note("Move it in the HUD editor. It waits for Hypixel's next \"New buff\" line.");
    }
}

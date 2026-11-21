package com.killer560.hub.gui.tab;

import com.killer560.hub.islands.AutoHarpFeature;

/** Auto Melody's Harp settings (cheat build only): see {@link AutoHarpFeature}. */
public class AutoHarpTab extends IslandFeatureTab {

    public AutoHarpTab() {
        super("Auto Melody's Harp", AutoHarpFeature.CFG, true, "Plays Melody's Harp for you.");
    }

    @Override
    protected void rows(Rows r) {
        r.note(IslandRows.AUTOMATION_NOTE);
        r.toggle("Auto Detect Lag", "detectLag");
        r.toggle("Auto Detect Ping", "detectPing");
        r.slider("Click Offset", "offset", v -> (v > 0 ? "+" : "") + v + " ms");
    }
}

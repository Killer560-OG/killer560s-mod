package com.killer560.hub.gui.tab;

import com.killer560.hub.islands.HarpHelperFeature;

/** Melody's Harp Helper settings (both builds): see {@link HarpHelperFeature}. */
public class HarpHelperTab extends IslandFeatureTab {

    public HarpHelperTab() {
        super("Melody's Harp Helper", HarpHelperFeature.CFG, false, "Harp keys and a tracker.");
    }

    @Override
    protected void rows(Rows r) {
        r.toggle("Key Binds", "keys", true);
        if (cfg.on("keys") || isSearchScan()) {
            for (int i = 1; i <= 7; i++) {
                r.key("Key " + i, "key" + i);
            }
            r.toggle("Show Keys", "numbers");
        }
        r.toggle("Progress Tracker", "tracker");
        r.toggle("Custom GUI Scale", "scaleOn", true);
        if (cfg.on("scaleOn") || isSearchScan()) {
            r.cycle("GUI Scale", "scale", 7, v -> v == 0 ? "Auto" : String.valueOf(v));
        }
        r.toggle("Hide Tooltips", "hideTips");
        r.toggle("Quick Restart", "quick");
    }
}

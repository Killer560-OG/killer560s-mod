package com.killer560.hub.gui.tab;

/** Beeheemoth Highlight (Foraging): see {@link com.killer560.hub.foraging.BeeheemothHighlight}. */
public class BeeheemothHighlightTab extends IslandFeatureTab {

    public BeeheemothHighlightTab() {
        super("Beeheemoth Highlight", com.killer560.hub.foraging.BeeheemothHighlight.CFG, false,
                "Boxes the Beeheemoth.");
    }

    @Override
    protected void rows(Rows r) {
        r.toggle("Highlight", "box");
        r.color("Color", "color", com.killer560.hub.foraging.BeeheemothHighlight.DEFAULT_COLOR);
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            r.header("Cheat", true);
            r.toggle("ESP (Through Walls)", "esp");
        }
    }
}

package com.killer560.hub.gui.tab;

/** Regenerating Trees (Foraging): see {@link com.killer560.hub.foraging.TreeRegen}. */
public class TreeRegenTab extends IslandFeatureTab {

    public TreeRegenTab() {
        super("Regenerating Trees", com.killer560.hub.foraging.TreeRegen.CFG, false,
                "Outlines regrowing logs.");
    }

    @Override
    protected void rows(Rows r) {
        r.color("Color", "color", com.killer560.hub.foraging.TreeRegen.DEFAULT_COLOR);
    }
}

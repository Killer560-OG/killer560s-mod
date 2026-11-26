package com.killer560.hub.gui.tab;

/** Regenerating Trees (Foraging): see {@link com.killer560.hub.foraging.TreeRegen}. */
public class TreeRegenTab extends IslandFeatureTab {

    public TreeRegenTab() {
        super("Regenerating Trees", com.killer560.hub.foraging.TreeRegen.CFG, false,
                "Marks leftover logs and leaves.");
    }

    @Override
    protected void rows(Rows r) {
        r.cycle("Style", "style", com.killer560.hub.foraging.TreeRegen.STYLES.length,
                i -> com.killer560.hub.foraging.TreeRegen.STYLES[i]);
        r.color("Color", "color", com.killer560.hub.foraging.TreeRegen.DEFAULT_COLOR);
    }
}

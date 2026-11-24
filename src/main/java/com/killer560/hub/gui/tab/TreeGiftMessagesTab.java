package com.killer560.hub.gui.tab;

/** Tree Gift Messages (Foraging): see {@link com.killer560.hub.foraging.TreeGiftHider}. */
public class TreeGiftMessagesTab extends IslandFeatureTab {

    public TreeGiftMessagesTab() {
        super("Tree Gift Messages", com.killer560.hub.foraging.TreeGiftHider.CFG, false,
                "Hides tree fall chat.");
    }

    @Override
    protected void rows(Rows r) {
        r.toggle("Keep Rare Drops", "rare");
    }
}

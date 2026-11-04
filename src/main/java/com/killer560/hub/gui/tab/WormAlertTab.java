package com.killer560.hub.gui.tab;

import com.killer560.hub.mining.worms.WormAlert;

/** Worm Alert settings: see {@link WormAlert}. */
public class WormAlertTab extends IslandFeatureTab {

    public WormAlertTab() {
        super("Worm Alert", WormAlert.CFG, false, "Worm and Scatha spawn alerts.");
    }

    @Override
    protected void rows(Rows r) {
        r.toggle("Spawn Title", "title");
        r.toggle("Spawn Sound", "sound");
        r.toggle("Scatha Alert", "scatha");
        r.toggle("Cooldown HUD", "hud");
    }
}

package com.killer560.hub.gui.tab;

import com.killer560.hub.mining.compass.WishingCompassSolver;

/** Wishing Compass Solver settings: see {@link WishingCompassSolver}. */
public class WishingCompassTab extends IslandFeatureTab {

    public WishingCompassTab() {
        super("Wishing Compass Solver", WishingCompassSolver.CFG, false, "Finds where the compass points.");
    }

    @Override
    protected void rows(Rows r) {
        r.toggle("Show Waypoint", "waypoint");
        r.note("Use it once, walk 8 blocks or more, use it again.");
    }
}

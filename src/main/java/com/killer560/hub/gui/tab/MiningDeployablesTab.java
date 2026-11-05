package com.killer560.hub.gui.tab;

import com.killer560.hub.mining.deployables.MiningDeployables;

import java.util.Locale;

/** Deployables settings: see {@link MiningDeployables}. */
public class MiningDeployablesTab extends IslandFeatureTab {

    public MiningDeployablesTab() {
        super("Deployables", MiningDeployables.CFG, false, "Lantern range circles and timers.");
    }

    @Override
    protected void rows(Rows r) {
        r.toggle("Show Radius Circle", "circle", true);
        if (cfg.on("circle") || isSearchScan()) {
            r.cycle("Range Style", "style", MiningDeployables.STYLE_NAMES.length, i -> MiningDeployables.STYLE_NAMES[i]);
            r.slider("Circle Thickness", "thickness", "px");
            r.slider("Circle Height", "lift", v -> String.format(Locale.ROOT, "%.1f blocks", v / 10.0));
            r.color("Circle Color", "color", MiningDeployables.DEFAULT_COLOR);
            r.color("In Radius Color", "insideColor", MiningDeployables.DEFAULT_INSIDE_COLOR);
        }
        r.toggle("Timer HUD", "timer", true);
        if (cfg.on("timer") || isSearchScan()) {
            r.cycle("Timer Shows", "timerShows", MiningDeployables.TIMER_NAMES.length, i -> MiningDeployables.TIMER_NAMES[i]);
        }
        r.toggle("Other Deployables", "others");
        r.note("Move the timer in the HUD editor.");
    }
}

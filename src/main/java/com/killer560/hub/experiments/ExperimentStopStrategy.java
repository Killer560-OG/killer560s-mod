package com.killer560.hub.experiments;

/** How autonomous mode decides when to back out of a Chronomatron/Ultrasequencer round. */
public enum ExperimentStopStrategy {
    /** Stops once SkyHanni-style auto-detection (reading the real "Chain of N:"/"Series of N:"
     *  lore off a stakes screen) says the max click bonus has been reached - no manual number, per
     *  killer560's explicit "I want skyhanni's format of auto detection not manual choice." If that
     *  lore is never found, this mode simply never backs out on its own. */
    MAX_CLICKS("Max Clicks (auto-detect)"),
    /** Ignores the click-bonus entirely and just runs up to the Hypixel Wiki's documented reward
     *  cap (15 for Chronomatron, 20 for Ultrasequencer) - maximizes raw Enchanting XP instead. */
    MAX_XP("Max XP (goes until reward cap)");

    public final String displayName;

    ExperimentStopStrategy(String displayName) {
        this.displayName = displayName;
    }

    public ExperimentStopStrategy next() {
        ExperimentStopStrategy[] vals = values();
        return vals[(this.ordinal() + 1) % vals.length];
    }
}

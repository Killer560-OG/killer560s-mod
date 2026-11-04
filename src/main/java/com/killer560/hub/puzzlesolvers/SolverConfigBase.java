package com.killer560.hub.puzzlesolvers;

/** The switch every puzzle solver config shares. */
abstract class SolverConfigBase {

    protected boolean enabled = false;

    public boolean isEnabled() { return enabled && com.killer560.hub.util.SkyblockGate.allows(); }

    /** The stored switch alone (no Skyblock Only gate): FeatureBits' gate. */
    public boolean isEnabledRaw() { return enabled; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        com.killer560.hub.util.FeatureBits.changed();
    }
}

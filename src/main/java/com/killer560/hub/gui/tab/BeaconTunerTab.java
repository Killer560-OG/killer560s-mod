package com.killer560.hub.gui.tab;

/** Moonglade Beacon Tuner switch (both builds): see {@link com.killer560.hub.foraging.BeaconTuner}. */
public class BeaconTunerTab extends IslandFeatureTab {

    public BeaconTunerTab() {
        super("Beacon Tuner", com.killer560.hub.foraging.BeaconTuner.CFG, false, "Shows the beacon tuning presses.");
    }

    @Override
    protected void rows(Rows r) {
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            r.header("Cheat", true);
            r.toggle("Auto Tune", "autoTune");
            r.slider("Click Delay", "tuneDelay", " ticks");
        }
    }
}

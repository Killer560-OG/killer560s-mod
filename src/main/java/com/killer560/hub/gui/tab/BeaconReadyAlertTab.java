package com.killer560.hub.gui.tab;

/** Beacon Ready Alert settings (both builds): see {@link com.killer560.hub.foraging.BeaconReadyAlert}. */
public class BeaconReadyAlertTab extends IslandFeatureTab {

    public BeaconReadyAlertTab() {
        super("Beacon Ready Alert", com.killer560.hub.foraging.BeaconReadyAlert.CFG, false, "Your beacon is ready again.");
    }

    @Override
    protected void rows(Rows r) {
        r.toggle("Alert Sound", "sound");
        r.slider("Repeat After", "repeat", " min");
    }
}

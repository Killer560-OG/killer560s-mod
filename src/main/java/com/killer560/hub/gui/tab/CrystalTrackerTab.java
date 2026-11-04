package com.killer560.hub.gui.tab;

import com.killer560.hub.mining.crystals.CrystalTrackerConfig;
import com.killer560.hub.mining.crystals.CrystalTrackerFeature;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Crystal Tracker (legit): see {@link CrystalTrackerFeature}. */
public class CrystalTrackerTab extends BaseTab {

    public CrystalTrackerTab() {
        super("Crystal Tracker");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        CrystalTrackerConfig cfg = CrystalTrackerConfig.getInstance();
        int[] y = {contentY};
        MiningRows.master(w, contentX, y, contentWidth, "Crystal Tracker", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild);
        if (!cfg.isEnabled() && !isSearchScan()) {
            return w;
        }
        MiningRows.header(w, contentX, y, contentWidth, "Alerts", false);
        MiningRows.toggle(w, contentX, y[0], contentWidth, "All Found Title", cfg::isAllFoundAlert,
                cfg::setAllFoundAlert, cfg::save);
        y[0] += 24;
        MiningRows.header(w, contentX, y, contentWidth, "Now", false);
        Map<String, CrystalTrackerFeature.State> states = CrystalTrackerFeature.states();
        if (states.isEmpty()) {
            MiningRows.note(w, contentX, y, contentWidth, "Not in the Crystal Hollows, or the tab list has no Crystals widget.");
        } else {
            for (Map.Entry<String, CrystalTrackerFeature.State> e : states.entrySet()) {
                MiningRows.note(w, contentX, y, contentWidth, e.getKey() + ": " + e.getValue().name().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return w;
    }
}

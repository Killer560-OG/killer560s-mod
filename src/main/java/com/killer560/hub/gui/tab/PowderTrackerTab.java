package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.powdermining.PowderMiningConfig;
import com.killer560.hub.powdermining.PowderTracker;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Powder Tracker (Mining): see {@code com.killer560.hub.powdermining.PowderTracker}. */
public class PowderTrackerTab extends BaseTab {

    public PowderTrackerTab() {
        super("Powder Tracker");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        PowderMiningConfig cfg = PowderMiningConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Powder Tracker", cfg::isPowderTrackerRaw, cfg::setPowderTracker, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += MiningQolRows.STEP;
        MiningQolRows.toggle(w, contentX, y, contentWidth, "Show Mithril Powder", cfg::isPowderTrackerMithril,
                cfg::setPowderTrackerMithril, cfg::save);
        y += MiningQolRows.STEP;
        MiningQolRows.toggle(w, contentX, y, contentWidth, "Show Top Drops", cfg::isPowderTrackerItems,
                cfg::setPowderTrackerItems, cfg::save);
        y += MiningQolRows.STEP;
        w.add(SettingsButtonWidget.builder(Component.literal("Reset Session"), btn -> PowderTracker.reset())
                .bounds(contentX, y, contentWidth, MiningQolRows.ROW).build());
        return w;
    }
}

package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonalerts.DungeonAlertsConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Teammate Low Health Alert (see {@link com.killer560.hub.dungeonalerts.LowHealthAlert}). Backed by
 *  {@link DungeonAlertsConfig}; every change is saved at once. */
public class LowHealthAlertTab extends BaseTab {

    public LowHealthAlertTab() {
        super("Low Health Alert");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Low Health Alert", cfg::isLowHealthEnabled, cfg::setLowHealthEnabled, cfg::save, requestRebuild, contentX, y, contentWidth, 20));
        y += 24;
        if (!cfg.isLowHealthEnabled()) {
            return w;
        }
        w.add(onOffButton("Only While Healer", cfg::isLowHealthOnlyHealer, cfg::setLowHealthOnlyHealer, cfg::save, null, contentX, y, contentWidth, 18));
        y += 22;
        w.add(onOffButton("Alert At Yellow Health", cfg::isLowHealthYellow, cfg::setLowHealthYellow, cfg::save, null, contentX, y, contentWidth, 18));
        y += 22;
        w.add(onOffButton("Alert Sound", cfg::isLowHealthSound, cfg::setLowHealthSound, cfg::save, null, contentX, y, contentWidth, 18));
        return w;
    }
}

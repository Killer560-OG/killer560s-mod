package com.killer560.hub.gui.tab;

import com.killer560.hub.miningqol.MiningQolConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Commission HUD (pre-2.0 Mining): see {@code com.killer560.hub.miningqol}. */
public class MiningCommissionHudTab extends BaseTab {

    public MiningCommissionHudTab() {
        super("Commission HUD");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Commission HUD", () -> cfg.isCommissionHudRaw(), cfg::setCommissionHud, cfg::save, requestRebuild, contentX, y,
                contentWidth, 20));
        y += MiningQolRows.STEP;

        MiningQolRows.toggle(w, contentX, y, contentWidth, "Color By Progress", cfg::isCommissionColorProgress,
                cfg::setCommissionColorProgress, cfg::save);
        y += MiningQolRows.STEP;
        MiningQolRows.toggle(w, contentX, y, contentWidth, "Commission Waypoints", cfg::isCommissionWaypoints,
                cfg::setCommissionWaypoints, cfg::save);
        y += MiningQolRows.STEP;
        MiningQolRows.toggle(w, contentX, y, contentWidth, "Emissary Waypoints", cfg::isCommissionEmissaries,
                cfg::setCommissionEmissaries, cfg::save);
        return w;
    }
}

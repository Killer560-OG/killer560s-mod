package com.killer560.hub.gui.tab;

import com.killer560.hub.miningqol.MiningQolConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Pickobulus Estimate (pre-2.0 Mining): see {@code com.killer560.hub.miningqol}. */
public class MiningPickobulusTab extends BaseTab {

    public MiningPickobulusTab() {
        super("Pickobulus Estimate");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Pickobulus Estimate", () -> cfg.isPickobulusRaw(), cfg::setPickobulus, cfg::save, requestRebuild, contentX, y,
                contentWidth, 20));
        y += MiningQolRows.STEP;

        MiningQolRows.color(w, contentX, y, contentWidth, "Outline Color", cfg::getPickobulusColor,
                cfg::setPickobulusColor, MiningQolConfig.DEFAULT_PICKOBULUS_COLOR, cfg::save);
        return w;
    }
}

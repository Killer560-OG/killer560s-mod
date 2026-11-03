package com.killer560.hub.gui.tab;

import com.killer560.hub.miningqol.MiningQolConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Mithril As Stone (pre-2.0 Mining): see {@code com.killer560.hub.miningqol}. */
public class MiningMithrilStoneTab extends BaseTab {

    public MiningMithrilStoneTab() {
        super("Mithril As Stone");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Mithril As Stone", () -> cfg.isMithrilStoneRaw(), cfg::setMithrilStone, cfg::save, requestRebuild, contentX, y,
                contentWidth, 20));
        return w;
    }
}

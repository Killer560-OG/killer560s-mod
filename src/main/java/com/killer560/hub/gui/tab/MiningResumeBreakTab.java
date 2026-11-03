package com.killer560.hub.gui.tab;

import com.killer560.hub.miningqol.MiningQolConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Resume Held Break After Menus (pre-2.0 Mining): see {@code com.killer560.hub.miningqol.AttackHoldProbe}. */
public class MiningResumeBreakTab extends BaseTab {

    public MiningResumeBreakTab() {
        super("Resume Held Break After Menus");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        w.add(onOffButton("Resume Held Break After Menus", () -> cfg.isResumeHeldBreakRaw(), cfg::setResumeHeldBreak,
                cfg::save, requestRebuild, contentX, contentY, contentWidth, 20));
        return w;
    }
}

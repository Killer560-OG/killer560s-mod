package com.killer560.hub.gui.tab;

import com.killer560.hub.powdermining.PowderMiningConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Lockpick Helper (Mining, legit): see {@code com.killer560.hub.powdermining.LockpickHelper}. */
public class LockpickHelperTab extends BaseTab {

    public LockpickHelperTab() {
        super("Lockpick Helper");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        PowderMiningConfig cfg = PowderMiningConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Lockpick Helper", cfg::isLockpickHelperRaw, cfg::setLockpickHelper, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += MiningQolRows.STEP;
        MiningQolRows.color(w, contentX, y, contentWidth, "Color", cfg::getLockpickColor, cfg::setLockpickColor,
                PowderMiningConfig.DEFAULT_LOCKPICK_COLOR, cfg::save);
        return w;
    }
}

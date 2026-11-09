package com.killer560.hub.gui.tab;

import com.killer560.hub.milestone.MilestoneConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Milestone Display ({@link com.killer560.hub.milestone.MilestoneFeature}): one switch, the HUD moves in Edit HUD. */
public class MilestoneTab extends BaseTab {

    public MilestoneTab() {
        super("Milestone Display");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        MilestoneConfig cfg = MilestoneConfig.getInstance();
        w.add(onOffButton("Milestone Display", cfg::isEnabledRaw, cfg::setEnabled, cfg::save, null,
                contentX, contentY, contentWidth, 20));
        return w;
    }
}

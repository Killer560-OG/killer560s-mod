package com.killer560.hub.gui.tab;

import com.killer560.hub.autosprint.AutoSprintConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Auto Sprint (2026-11-01): one switch. Sprints whenever you walk forward yourself; the mod's own movement (AP3, Auto
 *  Routes, the pathfinder) always keeps its own sprint key - see {@link com.killer560.hub.autosprint.AutoSprintFeature}. */
public class AutoSprintTab extends BaseTab {

    public AutoSprintTab() {
        super("Auto Sprint");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        AutoSprintConfig cfg = AutoSprintConfig.getInstance();
        widgets.add(onOffButton("Auto Sprint", cfg::isEnabledRaw, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, contentY, contentWidth, 20));
        return widgets;
    }
}

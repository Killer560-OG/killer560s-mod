package com.killer560.hub.gui.tab;

import com.killer560.hub.splittimers.TerminalTimersConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Terminal Timers settings - see {@link com.killer560.hub.splittimers.TerminalTimersFeature}. Lives with Tick
 *  Timers and Split Timers (killer560's "add this into the timer section"). */
public class TerminalTimersTab extends BaseTab {

    public TerminalTimersTab() {
        super("Terminal Timers");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        TerminalTimersConfig cfg = TerminalTimersConfig.getInstance();

        widgets.add(onOffButton("Terminal Timers", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(onOffButton("Terminal Solve Times", cfg::getSolveTimesRaw, cfg::setSolveTimes, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 20;

        widgets.add(onOffButton("Terminal/Device Splits", cfg::getSplitsRaw, cfg::setSplits, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 20;

        widgets.add(onOffButton("Simon Says Time", cfg::getSimonSaysTimeRaw, cfg::setSimonSaysTime, cfg::save,
                null, contentX, y, contentWidth, 18));

        return widgets;
    }
}

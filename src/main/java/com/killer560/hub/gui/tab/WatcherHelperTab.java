package com.killer560.hub.gui.tab;

import com.killer560.hub.splittimers.WatcherHelperConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Watcher Helper (see {@link com.killer560.hub.splittimers.WatcherHelper}): the switch, the speed title and the chat breakdown. */
public class WatcherHelperTab extends BaseTab {

    public WatcherHelperTab() {
        super("Watcher Helper");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        WatcherHelperConfig cfg = WatcherHelperConfig.getInstance();
        if (masterClosed(widgets, "Watcher Helper", cfg::getEnabledRaw, cfg::setEnabled, cfg::save, requestRebuild, contentX, contentY, contentWidth)) {
            return widgets;
        }
        int colW = (contentWidth - 8) / 2;
        widgets.add(onOffButton("Watcher Speed Title", cfg::isSpeedTitle, cfg::setSpeedTitle, cfg::save, null, contentX, contentY + 24, colW, 18));
        widgets.add(onOffButton("Watcher Breakdown", cfg::isBreakdownChat, cfg::setBreakdownChat, cfg::save, null, contentX + colW + 8, contentY + 24, colW, 18));
        widgets.add(onOffButton("Send To Party Chat", cfg::isSendToParty, cfg::setSendToParty, cfg::save, null, contentX, contentY + 48, colW, 18));
        return widgets;
    }
}

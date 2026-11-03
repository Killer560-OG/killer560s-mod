package com.killer560.hub.gui.tab;

import com.killer560.hub.chattabs.ChatTabsConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Chat Tabs ({@link com.killer560.hub.chattabs.ChatTabsFeature}): the switch, Switch Channel, Filter Closed Chat. */
public class ChatTabsTab extends BaseTab {

    public ChatTabsTab() {
        super("Chat Tabs");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        ChatTabsConfig cfg = ChatTabsConfig.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colBX = contentX + colW + gap;
        int y = contentY;

        widgets.add(onOffButton("Chat Tabs", cfg::isEnabledRaw, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 24;
        if (!cfg.isEnabledRaw() && !isSearchScan()) {
            return widgets;
        }
        widgets.add(onOffButton("Switch Channel", cfg::isSwitchChannel, cfg::setSwitchChannel, cfg::save,
                null, contentX, y, colW, 18));
        widgets.add(onOffButton("Filter Closed Chat", cfg::isFilterClosedChat, cfg::setFilterClosedChat, cfg::save,
                null, colBX, y, colW, 18));
        return widgets;
    }
}

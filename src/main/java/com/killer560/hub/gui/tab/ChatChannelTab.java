package com.killer560.hub.gui.tab;

import com.killer560.hub.chatchannel.ChatChannelConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Chat Channel Indicator ({@link com.killer560.hub.chatchannel.ChatChannelFeature}): the switch, Show Member Count, Use Hypixel Colors. */
public class ChatChannelTab extends BaseTab {

    public ChatChannelTab() {
        super("Chat Channel");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        ChatChannelConfig cfg = ChatChannelConfig.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colBX = contentX + colW + gap;
        int y = contentY;

        if (masterClosed(widgets, "Chat Channel Indicator", cfg::isEnabledRaw, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, y, contentWidth)) {
            return widgets;
        }
        y += 24;
        widgets.add(onOffButton("Show Member Count", cfg::isShowMemberCount, cfg::setShowMemberCount, cfg::save,
                null, contentX, y, colW, 18));
        widgets.add(onOffButton("Use Hypixel Colors", cfg::isHypixelColors, cfg::setHypixelColors, cfg::save,
                null, colBX, y, colW, 18));
        y += 22;
        widgets.add(SettingsButtonWidget.cycling(showLabel(cfg), (btn, dir) -> {
                    cfg.setAlwaysShow(!cfg.isAlwaysShow());
                    cfg.save();
                    btn.setMessage(showLabel(cfg));
                }).bounds(contentX, y, contentWidth, 18).build());
        return widgets;
    }

    private static Component showLabel(ChatChannelConfig cfg) {
        return Component.literal("Show: " + (cfg.isAlwaysShow() ? "Always" : "While Chat Is Open"));
    }
}

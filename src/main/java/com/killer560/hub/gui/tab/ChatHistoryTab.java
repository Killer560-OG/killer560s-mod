package com.killer560.hub.gui.tab;

import com.killer560.hub.chathistory.ChatHistoryConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat History settings: the history length, Off (vanilla's 100), Limited (a slider from 200 to 10000 in steps of 200,
 * killer560 2026-11-01) or Unlimited (2026-10-18). See {@link ChatHistoryConfig}.
 */
public class ChatHistoryTab extends BaseTab {

    private static final int ROW = 20;
    private static final int GAP = 24;

    public ChatHistoryTab() {
        super("Chat History");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        ChatHistoryConfig cfg = ChatHistoryConfig.getInstance();
        int y = contentY;
        widgets.add(SettingsButtonWidget.cycling(modeText(cfg), (btn, dir) -> {
                    cfg.setMode(SettingsButtonWidget.step(cfg.getMode(), dir));
                    cfg.save();
                    btn.setMessage(modeText(cfg));
                    if (requestRebuild != null) {
                        requestRebuild.run();
                    }
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += GAP;
        if (cfg.getMode() == ChatHistoryConfig.Mode.LIMITED) {
            widgets.add(steppedSlider(contentX, y, contentWidth, ROW, limitText(cfg),
                    ChatHistoryConfig.LIMIT_MIN, ChatHistoryConfig.LIMIT_MAX, cfg.getCustomLimit(),
                    ChatHistoryConfig.LIMIT_STEP, v -> cfg.setCustomLimit((int) Math.round(v)),
                    () -> limitText(cfg), cfg::save));
        }
        return widgets;
    }

    private static Component modeText(ChatHistoryConfig cfg) {
        return Component.literal("Chat History: " + cfg.getMode().label);
    }

    private static Component limitText(ChatHistoryConfig cfg) {
        return Component.literal("History Limit: " + cfg.getCustomLimit() + " messages");
    }
}

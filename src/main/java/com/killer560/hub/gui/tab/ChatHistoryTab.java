package com.killer560.hub.gui.tab;

import com.killer560.hub.chathistory.ChatHistoryConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat History settings: the Chat History switch (OFF is vanilla's 100 lines, killer560 2026-11-02), then while it is
 * ON the length, Limited (a slider from 200 to 10000 in steps of 200, 2026-11-01) or Unlimited (2026-10-18). See
 * {@link ChatHistoryConfig}.
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
        // A real switch first (killer560, 2026-11-02: "this needs an actual toggle"), so the card shows it; OFF is
        // vanilla's 100 lines. The length stays as it was while the switch is off.
        widgets.add(onOffButton("Chat History", cfg::isEnabled, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, y, contentWidth, ROW));
        y += GAP;
        if (!cfg.isEnabled()) {
            return widgets;
        }
        widgets.add(SettingsButtonWidget.cycling(lengthText(cfg), (btn, dir) -> {
                    cfg.setLength(SettingsButtonWidget.step(cfg.getLength(), dir));
                    cfg.save();
                    btn.setMessage(lengthText(cfg));
                    if (requestRebuild != null) {
                        requestRebuild.run();
                    }
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += GAP;
        if (cfg.getLength() == ChatHistoryConfig.Length.LIMITED) {
            widgets.add(steppedSlider(contentX, y, contentWidth, ROW, limitText(cfg),
                    ChatHistoryConfig.LIMIT_MIN, ChatHistoryConfig.LIMIT_MAX, cfg.getCustomLimit(),
                    ChatHistoryConfig.LIMIT_STEP, v -> cfg.setCustomLimit((int) Math.round(v)),
                    () -> limitText(cfg), cfg::save));
        }
        return widgets;
    }

    private static Component lengthText(ChatHistoryConfig cfg) {
        return Component.literal("Length: " + cfg.getLength().label);
    }

    private static Component limitText(ChatHistoryConfig cfg) {
        return Component.literal("History Limit: " + cfg.getCustomLimit() + " messages");
    }
}

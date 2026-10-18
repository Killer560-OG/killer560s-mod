package com.killer560.hub.gui.tab;

import com.killer560.hub.chathistory.ChatHistoryConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Chat History settings (killer560, 2026-10-18): the Unlimited Chat History switch. See {@link ChatHistoryConfig}. */
public class ChatHistoryTab extends BaseTab {

    public ChatHistoryTab() {
        super("Chat History");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        ChatHistoryConfig cfg = ChatHistoryConfig.getInstance();
        widgets.add(SettingsButtonWidget.builder(text(cfg), btn -> {
                    cfg.setUnlimited(!cfg.isUnlimited());
                    cfg.save();
                    btn.setMessage(text(cfg));
                }).bounds(contentX, contentY, contentWidth, 20).build());
        return widgets;
    }

    private static Component text(ChatHistoryConfig cfg) {
        return Component.literal("Unlimited Chat History: " + (cfg.isUnlimited() ? "§aON" : "§cOFF"));
    }
}

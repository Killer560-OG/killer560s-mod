package com.killer560.hub.gui.tab;

import com.killer560.hub.emotes.ChatEmoteConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Chat Emotes settings: on/off only. See {@link com.killer560.hub.emotes.ChatEmoteFeature}. */
public class ChatEmotesTab extends BaseTab {

    public ChatEmotesTab() {
        super("Chat Emotes");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(enabledText(), btn -> {
                    ChatEmoteConfig cfg = ChatEmoteConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(enabledText());
                }).bounds(contentX, y, 220, 20).build());

        return widgets;
    }

    private static Component enabledText() {
        return Component.literal("Chat Emotes Enabled: " + (ChatEmoteConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }
}

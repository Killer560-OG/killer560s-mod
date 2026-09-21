package com.killer560.hub.gui.tab;

import com.killer560.hub.copychat.CopyChatConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Copy Chat settings: a single on/off toggle. See {@link com.killer560.hub.copychat.CopyChatFeature}. */
public class CopyChatTab extends BaseTab {

    public CopyChatTab() {
        super("Copy Chat");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(enabledText(), btn -> {
                    CopyChatConfig cfg = CopyChatConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(enabledText());
                }).bounds(contentX, y, 220, 20).build());

        // In-panel usage text moved to the toggle's hover tooltip (mod-wide in-panel-paragraph cleanup, 2026-09-21).
        return widgets;
    }

    private static Component enabledText() {
        return Component.literal("Copy Chat: " + (CopyChatConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }
}

package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.modchat.ModChatConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Mod Chat settings - see {@link com.killer560.hub.modchat.ModChatFeature}. Messages go over the mod's own
 *  relay, never over Hypixel chat, so non-mod-users cannot see them. */
public class ModChatTab extends BaseTab {

    public ModChatTab() {
        super("Mod Chat");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        ModChatConfig cfg = ModChatConfig.getInstance();

        widgets.add(onOffButton("Mod Chat", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, 220, 20));
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.cycling(
                Component.literal("Room: " + cfg.getRoomMode().label), (btn, dir) -> {
                    cfg.setRoomMode(dir > 0 ? cfg.getRoomMode().next() : cfg.getRoomMode().previous());
                    cfg.save();
                    btn.setMessage(Component.literal("Room: " + cfg.getRoomMode().label));
                }).bounds(contentX, y, 220, 18).build());
        y += 22;

        widgets.add(onOffButton("Log To Chat", cfg::isLogToChat, cfg::setLogToChat, cfg::save,
                null, contentX, y, 220, 18));
        y += 26;

        // The live Relay / Room / Mod users lines are no longer drawn on this tab (killer560, 2026-10-18).

        return widgets;
    }
}

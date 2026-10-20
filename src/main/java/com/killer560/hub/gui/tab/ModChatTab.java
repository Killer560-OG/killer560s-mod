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

        widgets.add(SettingsButtonWidget.builder(onOff("Mod Chat", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(
                Component.literal("Room: " + cfg.getRoomMode().label), btn -> {
                    cfg.setRoomMode(cfg.getRoomMode().next());
                    cfg.save();
                    btn.setMessage(Component.literal("Room: " + cfg.getRoomMode().label));
                }).secondaryPress(btn -> {
                    cfg.setRoomMode(cfg.getRoomMode().previous());
                    cfg.save();
                    btn.setMessage(Component.literal("Room: " + cfg.getRoomMode().label));
                }).bounds(contentX, y, 220, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Log To Chat", cfg.isLogToChat()), btn -> {
                    cfg.setLogToChat(!cfg.isLogToChat());
                    cfg.save();
                    btn.setMessage(onOff("Log To Chat", cfg.isLogToChat()));
                }).bounds(contentX, y, 220, 18).build());
        y += 26;

        // The live Relay / Room / Mod users lines are no longer drawn on this tab (killer560, 2026-10-18).

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

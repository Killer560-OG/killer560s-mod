package com.killer560.hub.gui.tab;

import com.killer560.hub.chatcommands.ChatCommandsConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Chat Commands settings - see {@link com.killer560.hub.chatcommands.ChatCommandsFeature}'s class doc
 *  for the real Odin-ported "!command" reply system this is built on (informational replies only). */
public class ChatCommandsTab extends BaseTab {

    public ChatCommandsTab() {
        super("Chat Commands");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        ChatCommandsConfig cfg = ChatCommandsConfig.getInstance();
        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2aX = contentX;
        int col2bX = contentX + col2W + gap;

        widgets.add(SettingsButtonWidget.builder(onOff("Chat Commands", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Party", cfg.isPartyEnabled()), btn -> {
                    cfg.setPartyEnabled(!cfg.isPartyEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Party", cfg.isPartyEnabled()));
                }).bounds(col2aX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Guild", cfg.isGuildEnabled()), btn -> {
                    cfg.setGuildEnabled(!cfg.isGuildEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Guild", cfg.isGuildEnabled()));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Private", cfg.isPrivateEnabled()), btn -> {
                    cfg.setPrivateEnabled(!cfg.isPrivateEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Private", cfg.isPrivateEnabled()));
                }).bounds(col2aX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Co-op", cfg.isCoopEnabled()), btn -> {
                    cfg.setCoopEnabled(!cfg.isCoopEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Co-op", cfg.isCoopEnabled()));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Replies to \"!coords\", \"!ping\", \"!fps\", \"!time\", \"!holding\","),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7\"!cf\", \"!8ball\", \"!dice\" in whichever channels are on above."),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Never runs party-management commands from a chat message."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

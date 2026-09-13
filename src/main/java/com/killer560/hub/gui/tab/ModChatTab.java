package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.modchat.ModChatConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Mod Chat settings - see {@link com.killer560.hub.modchat.ModChatFeature}'s class doc for the real,
 *  disclosed limitation this is built around (Hypixel party/guild chat, not a private channel). */
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

        widgets.add(SettingsButtonWidget.builder(channelText(cfg), btn -> {
                    cfg.setChannel(cfg.getChannel().next());
                    cfg.save();
                    btn.setMessage(channelText(cfg));
                }).bounds(contentX, y, 220, 18).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Use /killer560 chat <message> to send. Other mod users get a"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7highlighted overlay; non-mod-users in the same " + cfg.getChannel().name().toLowerCase()
                        + " still see"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7the raw tagged line in normal chat - this isn't a private channel,"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Hypixel doesn't offer one without a real relay server."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component channelText(ModChatConfig cfg) {
        return Component.literal("Channel: " + cfg.getChannel().name());
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

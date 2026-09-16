package com.killer560.hub.gui.tab;

import com.killer560.hub.discordrpc.DiscordRpcConfig;
import com.killer560.hub.discordrpc.DiscordRpcFeature;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Discord Rich Presence settings - see {@link DiscordRpcFeature}. The Application ID box is the whole
 *  feature: Rich Presence shows the NAME of a Discord application, so it only works with an app killer560
 *  creates himself at discord.com/developers (named "Killer560's Mod"), and no id ships with the mod. */
public class DiscordRpcTab extends BaseTab {

    public DiscordRpcTab() {
        super("Discord Rich Presence");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        DiscordRpcConfig cfg = DiscordRpcConfig.getInstance();
        var font = Minecraft.getInstance().font;
        int y = contentY;
        int gap = 8;
        int halfW = (contentWidth - gap) / 2;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Discord Rich Presence", false), font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Discord Rich Presence", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    DiscordRpcFeature.onSettingsChanged();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Status: §f" + DiscordRpcFeature.status()
                        + "  §8|  §7Now showing: §f" + safePreview()), font));
        y += 18;

        // --- what to show
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("What To Show", false), font));
        y += 14;

        widgets.add(SettingsButtonWidget.builder(onOff("Hide Details", cfg.isHideDetails()), btn -> {
                    cfg.setHideDetails(!cfg.isHideDetails());
                    cfg.save();
                    DiscordRpcFeature.onSettingsChanged();
                    requestRebuild.run();
                }).bounds(contentX, y, halfW, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Show Elapsed Time", cfg.isShowElapsed()), btn -> {
                    cfg.setShowElapsed(!cfg.isShowElapsed());
                    cfg.save();
                    DiscordRpcFeature.onSettingsChanged();
                    btn.setMessage(onOff("Show Elapsed Time", cfg.isShowElapsed()));
                }).bounds(contentX + halfW + gap, y, halfW, 18).build());
        y += 22;

        if (cfg.isHideDetails()) {
        } else {
            widgets.add(SettingsButtonWidget.builder(onOff("Show Island / Area", cfg.isShowArea()), btn -> {
                        cfg.setShowArea(!cfg.isShowArea());
                        cfg.save();
                        DiscordRpcFeature.onSettingsChanged();
                        btn.setMessage(onOff("Show Island / Area", cfg.isShowArea()));
                    }).bounds(contentX, y, halfW, 18).build());
            widgets.add(SettingsButtonWidget.builder(onOff("Show Dungeon Info", cfg.isShowDungeonInfo()), btn -> {
                        cfg.setShowDungeonInfo(!cfg.isShowDungeonInfo());
                        cfg.save();
                        DiscordRpcFeature.onSettingsChanged();
                        btn.setMessage(onOff("Show Dungeon Info", cfg.isShowDungeonInfo()));
                    }).bounds(contentX + halfW + gap, y, halfW, 18).build());
            y += 22;
        }
        y += 4;

        return widgets;
    }

    private String safePreview() {
        try {
            return DiscordRpcFeature.previewText();
        } catch (Throwable t) {
            return "-";
        }
    }

    private static Component onOff(String label, boolean on) {
        return Component.literal(label + ": " + (on ? "§aON" : "§cOFF"));
    }
}

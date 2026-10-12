package com.killer560.hub.gui.tab;

import com.killer560.hub.autoupdate.AutoUpdateConfig;
import com.killer560.hub.autoupdate.AutoUpdateFeature;
import com.killer560.hub.autoupdate.AutoUpdater;
import com.killer560.hub.autoupdate.BuildInfo;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Home's Auto Update section (2026-10-11): the channel and a Check Now button. See {@link AutoUpdateFeature}. */
public class AutoUpdateTab extends BaseTab {

    public AutoUpdateTab() {
        super("Auto Update");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int gap = 8;
        int half = (contentWidth - gap) / 2;
        int rightW = Math.max(1, contentWidth - half - gap);
        int y = contentY;

        // Home draws every section inline with no accordion header, so each one draws its own heading.
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Auto Update", false), Minecraft.getInstance().font));
        y += 16;

        widgets.add(SettingsButtonWidget.cycling(channelText(), (btn, dir) -> {
                    AutoUpdateConfig cfg = AutoUpdateConfig.getInstance();
                    cfg.setChannel(SettingsButtonWidget.step(cfg.getChannel(), dir));
                    cfg.save();
                    btn.setMessage(channelText());
                }).bounds(contentX, y, half, 20).build());
        widgets.add(SettingsButtonWidget.builder(checkText(), btn -> {
                    if (AutoUpdateFeature.isChecking()) {
                        return;
                    }
                    AutoUpdateFeature.checkAsync(o -> btn.setMessage(checkText()));
                    btn.setMessage(checkText());
                }).bounds(contentX + half + gap, y, rightW, 20).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal("§7This build: "
                + BuildInfo.shortCommit() + " (" + BuildInfo.minecraft() + " " + BuildInfo.variant() + ")"),
                Minecraft.getInstance().font));
        return widgets;
    }

    private static Component channelText() {
        AutoUpdateConfig.Channel c = AutoUpdateConfig.getInstance().getChannel();
        return Component.literal("Auto Update: " + (c == AutoUpdateConfig.Channel.OFF ? "§c" : "§a") + c.label);
    }

    private static Component checkText() {
        if (AutoUpdateFeature.isChecking()) {
            return Component.literal("Check Now: §7checking...");
        }
        AutoUpdater.Outcome o = AutoUpdateFeature.lastOutcome();
        if (o == null) {
            return Component.literal("Check Now");
        }
        return Component.literal("Check Now: " + switch (o.status()) {
            case UP_TO_DATE -> "§aup to date";
            case STAGED, ALREADY_STAGED -> "§aready, installs on close";
            case BLOCKED -> "§7" + shortReason(o.detail());
            case NO_RELEASE, NO_ASSET -> "§7no update";
            case BAD_SOURCE, NO_CHECKSUM, CHECKSUM_MISMATCH -> "§crejected";
            case ERROR -> "§cfailed";
        });
    }

    private static String shortReason(String detail) {
        if (detail == null) {
            return "skipped";
        }
        if (detail.startsWith("instance managed")) {
            return "deploy-managed";
        }
        if (detail.startsWith("the mod is not a jar")) {
            return "not in mods/";
        }
        if (detail.startsWith("Auto Update is Off")) {
            return "off";
        }
        return "skipped";
    }
}

package com.killer560.hub.gui.tab;

import com.killer560.hub.banreport.BanReportConfig;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Home's Ban Reports section (killer560, 2026-11-01, to-do Doc "BAN LOGGER": "Make it a toggle that is very noticeable
 * where they can turn on auto report bans. Do not have it auto on."). On Home, below his own ordered cards, with a loud
 * switch and a plain list of what is and is not sent. See {@code banreport/BanReportFeature}.
 */
public class BanReportTab extends BaseTab {

    public BanReportTab() {
        super("Ban Reports");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Ban Reports", false));
        y += 16;

        BanReportConfig cfg = BanReportConfig.getInstance();
        widgets.add(SettingsButtonWidget.builder(switchText(cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        String[] lines = {
                "§eIf you get banned on Hypixel, sends one report to killer560's staff:",
                "§7the ban message (Ban ID hidden), mod version and variant,",
                "§7the features you had on and their settings, and this session's",
                "§7mod log with server corrections.",
                "§7Never sent: tokens, session IDs, other players' chat or names,",
                "§7IP addresses, API keys. Off by default; only you can turn it on."
        };
        for (String line : lines) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal(line), Minecraft.getInstance().font));
            y += 11;
        }
        return widgets;
    }

    /** "Auto Report Bans: ON" in bold green, or OFF in bold red, so the state is impossible to miss. */
    public static Component switchText(boolean on) {
        return Component.literal("§lAuto Report Bans: " + (on ? "§a§lON" : "§c§lOFF"));
    }
}

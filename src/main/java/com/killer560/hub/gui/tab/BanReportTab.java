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

        // Short bullets (killer560, 2026-11-02: "this says way to much ... shorter and more bullet point style"). Each
        // line wraps at the drawer's width instead of being cut off; a wrapped bullet keeps its color and indent.
        for (String line : LINES) {
            String color = line.startsWith("§") ? line.substring(0, 2) : "";
            String body = line.substring(color.length());
            boolean bullet = body.startsWith("• ");
            List<String> parts = com.killer560.hub.gui.MenuStyle.wrap(body, Math.max(40, contentWidth - 2), 4);
            for (int i = 0; i < parts.size(); i++) {
                String text = color + (i > 0 && bullet ? "  " : "") + parts.get(i);
                widgets.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal(text), Minecraft.getInstance().font));
                y += 11;
            }
        }
        return widgets;
    }

    /** What a report holds and never holds; kept to what {@code BanReportFeature.buildPayload} really sends. */
    public static final String[] LINES = {
            "§eIf you're ever banned, this shares with the Discord admins:",
            "§7• the features you had on and their settings",
            "§7• this session's mod log",
            "§7• the ban message and mod version",
            "§eIt never shares:",
            "§7• tokens, passwords or session IDs",
            "§7• other players' names or chat",
            "§7• your IP or API keys"
    };

    /** "Auto Report Bans: ON" in bold green, or OFF in bold red, so the state is impossible to miss. */
    public static Component switchText(boolean on) {
        return Component.literal("§lAuto Report Bans: " + (on ? "§a§lON" : "§c§lOFF"));
    }
}

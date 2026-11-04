package com.killer560.hub.gui.tab;

import com.killer560.hub.crashreport.CrashReportConfig;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Home's Crash Reports section (killer560, 2026-11-04: Auto Crash Reports, "opt-in like Auto Report Bans ... next to it in
 * Home"): the same loud switch, whether his name may go on a report, and a short list of what is and is not sent. See
 * {@code crashreport/CrashReportFeature}.
 */
public class CrashReportTab extends BaseTab {

    public CrashReportTab() {
        super("Crash Reports");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Crash Reports", false));
        y += 16;

        CrashReportConfig cfg = CrashReportConfig.getInstance();
        widgets.add(SettingsButtonWidget.builder(switchText(cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        widgets.add(onOffButton("Include My Username", cfg::isShareName, cfg::setShareName, cfg::save, null,
                contentX, y, contentWidth, 20));
        y += 24;

        for (String line : LINES) {
            String color = line.substring(0, 2);
            String body = line.substring(2);
            List<String> parts = MenuStyle.wrap(body, Math.max(40, contentWidth - 2), 4);
            for (int i = 0; i < parts.size(); i++) {
                String text = color + (i > 0 && body.startsWith("• ") ? "  " : "") + parts.get(i);
                widgets.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal(text), Minecraft.getInstance().font));
                y += 11;
            }
        }
        return widgets;
    }

    /** What a report holds and never holds; kept to what {@code CrashReportFeature.buildPayload} really sends. */
    public static final String[] LINES = {
            "§eIf the game crashes, this sends the Discord admins:",
            "§7• the crash report",
            "§7• mod version, your other mods and features on",
            "§eIt never sends:",
            "§7• tokens, keys or your IP",
            "§7• server addresses or other players' names",
            "§7• your username, unless you turn it on above"
    };

    /** "Auto Crash Reports: ON" in bold green, or OFF in bold red. */
    public static Component switchText(boolean on) {
        return Component.literal("§lAuto Crash Reports: " + (on ? "§a§lON" : "§c§lOFF"));
    }
}

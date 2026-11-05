package com.killer560.hub.gui.tab;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.discordlink.DiscordLink;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Home's Discord Link section (discordlink-1105, killer560 2026-11-05): link this Minecraft account to your Discord with a
 * one-time code typed as {@code /link code:<code>} in killer560's Discord; shows the linked Discord and unlinks. Opt-in, both
 * builds, nothing stored in the mod. See {@code discordlink/DiscordLink}. The status is asked for once when the card is first
 * really shown ({@link #isOnScreenBuild()}, never in the search scan), then only on a button.
 */
public class DiscordLinkTab extends BaseTab {

    public static final String NAME = "Discord Link";

    public DiscordLinkTab() {
        super(NAME);
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, NAME, false));
        y += 16;
        if (isOnScreenBuild()) {
            Screen shown = McCompat.screen(Minecraft.getInstance());
            DiscordLink.watch(() -> {
                if (McCompat.screen(Minecraft.getInstance()) == shown) {
                    requestRebuild.run();
                }
            });
            if (DiscordLink.state() == DiscordLink.State.UNKNOWN) {
                DiscordLink.refresh();
            }
        }

        DiscordLink.State s = DiscordLink.state();
        List<String> status = statusLines();
        for (String line : status) {
            y = text(widgets, line, contentX, y, contentWidth);
        }
        y += 4;

        String linkLabel = s == DiscordLink.State.CODE ? "New Code"
                : s == DiscordLink.State.LINKED ? "Link a Different Discord" : "Link Discord";
        widgets.add(button(linkLabel, DiscordLink::start, contentX, y, contentWidth));
        y += 24;
        if (s == DiscordLink.State.CODE) {
            widgets.add(button("Copy /link Command",
                    () -> Minecraft.getInstance().keyboardHandler.setClipboard(DiscordLink.command()), contentX, y, contentWidth));
            y += 24;
        }
        widgets.add(button("Check Status", DiscordLink::refresh, contentX, y, contentWidth));
        y += 24;
        if (s == DiscordLink.State.LINKED || !DiscordLink.discordName().isEmpty()) {
            widgets.add(button("Unlink", DiscordLink::unlink, contentX, y, contentWidth));
            y += 24;
        }
        for (String line : LINES) {
            y = text(widgets, line, contentX, y, contentWidth);
        }
        return widgets;
    }

    /** The status as the card and the tour show it. */
    public static List<String> statusLines() {
        return switch (DiscordLink.state()) {
            case UNKNOWN -> List.of("§7Not checked yet.");
            case WORKING -> List.of("§7Asking the relay...");
            case NOT_LINKED -> List.of("§7Not linked.");
            case CODE -> {
                List<String> l = new ArrayList<>();
                if (!DiscordLink.discordName().isEmpty()) {
                    l.add("§aLinked to §f" + DiscordLink.discordName() + "§a. A new code links a different Discord.");
                }
                l.add("§eType §f" + DiscordLink.command() + "§e in killer560's Discord.");
                l.add("§7The code works once, for " + DiscordLink.codeMinutesLeft() + " more min.");
                yield l;
            }
            case LINKED -> List.of("§aLinked to §f" + DiscordLink.discordName());
            case ERROR -> List.of("§cCouldn't link right now: " + DiscordLink.error() + ". Try again later.");
        };
    }

    public static final String[] LINES = {
            "§eWhat it's for:",
            "§7• killer560 knows your Discord, e.g. on your crash reports",
            "§7• only the link is kept; Unlink deletes it"
    };

    private static SettingsButtonWidget button(String label, Runnable action, int x, int y, int w) {
        return SettingsButtonWidget.builder(Component.literal(label), btn -> action.run()).bounds(x, y, w, 20).build();
    }

    private static int text(List<AbstractWidget> widgets, String line, int x, int y, int w) {
        String color = line.startsWith("§") ? line.substring(0, 2) : "";
        String body = line.substring(color.length());
        List<String> parts = MenuStyle.wrap(body, Math.max(40, w - 2), 4);
        for (int i = 0; i < parts.size(); i++) {
            String t = color + (i > 0 && body.startsWith("• ") ? "  " : "") + parts.get(i);
            widgets.add(new StringWidget(x, y, w, 10, Component.literal(t), Minecraft.getInstance().font));
            y += 11;
        }
        return y;
    }
}

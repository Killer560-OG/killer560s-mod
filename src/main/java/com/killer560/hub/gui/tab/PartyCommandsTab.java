package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.partycommands.PartyCommandsConfig;
import com.killer560.hub.partycommands.PartyCommandsConfig.Command;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Party Commands settings - Odin's party commands, but only a real party/dungeon teammate can trigger one.
 *  See {@link com.killer560.hub.partycommands.PartyCommandsFeature} for the rules. Every command is its own
 *  toggle and every one starts OFF; the party-mutating ones (warp, warp + transfer, kick, demote, queue floor)
 *  need the separate "Allow Destructive Commands" switch as well. */
public class PartyCommandsTab extends BaseTab {

    public PartyCommandsTab() {
        super("Party Commands");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        PartyCommandsConfig cfg = PartyCommandsConfig.getInstance();
        int y = contentY;
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colAX = contentX;
        int colBX = contentX + colW + gap;

        widgets.add(SettingsButtonWidget.builder(onOff("Party Commands", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabledRaw()) {
            widgets.add(note(contentX, y, contentWidth, "Only players in your party or dungeon run can trigger these."));
            return widgets;
        }

        widgets.add(note(contentX, y, contentWidth, "Only your own party / dungeon teammates can trigger these."));
        y += 12;
        widgets.add(note(contentX, y, contentWidth, "Guild chat, DMs and anyone else are ignored. Party chat only."));
        y += 16;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Commands", false), Minecraft.getInstance().font));
        y += 16;
        y = addToggles(widgets, cfg, false, y, colAX, colBX, colW);

        widgets.add(SettingsButtonWidget.builder(onOff("Confirm Invites", cfg.isConfirmInvites()), btn -> {
                    cfg.setConfirmInvites(!cfg.isConfirmInvites());
                    cfg.save();
                    btn.setMessage(onOff("Confirm Invites", cfg.isConfirmInvites()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;
        widgets.add(note(contentX, y, contentWidth, "Confirm Invites: !invite only shows a click-to-invite line."));
        y += 18;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Destructive Commands", false), Minecraft.getInstance().font));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(onOff("Allow Destructive Commands", cfg.isAllowDestructive()), btn -> {
                    cfg.setAllowDestructive(!cfg.isAllowDestructive());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        if (cfg.isAllowDestructive()) {
            y = addToggles(widgets, cfg, true, y, colAX, colBX, colW);
        } else {
            widgets.add(note(contentX, y, contentWidth, "Warp, warp + transfer, kick, demote and floor queue stay off."));
            y += 16;
        }

        widgets.add(note(contentX, y, contentWidth, "Rate limited: 1 every 2s overall, 6 a minute, 3 a minute per player."));
        y += 12;
        widgets.add(note(contentX, y, contentWidth, "Everything a teammate runs is printed in your chat."));
        return widgets;
    }

    private int addToggles(List<AbstractWidget> widgets, PartyCommandsConfig cfg, boolean destructive, int y,
                           int colAX, int colBX, int colW) {
        boolean left = true;
        for (Command command : Command.values()) {
            if (command.isDestructive() != destructive) {
                continue;
            }
            String label = command.label();
            int x = left ? colAX : colBX;
            widgets.add(SettingsButtonWidget.builder(onOff(label, cfg.isOn(command)), btn -> {
                        cfg.setOn(command, !cfg.isOn(command));
                        cfg.save();
                        btn.setMessage(onOff(label, cfg.isOn(command)));
                    }).bounds(x, y, colW, 18).build());
            if (!left) {
                y += 22;
            }
            left = !left;
        }
        return left ? y + 4 : y + 26;
    }

    private static StringWidget note(int x, int y, int width, String text) {
        return new StringWidget(x, y, width, 12, Component.literal("§7" + text), Minecraft.getInstance().font);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

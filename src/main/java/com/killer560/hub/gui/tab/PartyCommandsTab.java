package com.killer560.hub.gui.tab;

import com.killer560.hub.chatcommands.ChatCommandsConfig;
import com.killer560.hub.chatcommands.ChatCommandsConfig.InfoCommand;
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

/**
 * Party Commands - killer560 (2026-09-21): "Move chat and party commands into the same tab. Make it one
 * toggle not two separate settings." This tab is now the single home for both of what used to be separate
 * tabs/features:
 * <ul>
 * <li>Odin's real party-management commands ({@code partycommands.PartyCommandsFeature}) - warp, kick,
 *     promote, the floor-queue commands, etc, gated to real party/dungeon teammates.
 * <li>Odin's informational "!" replies ({@code chatcommands.ChatCommandsFeature}, formerly its own "Chat
 *     Commands" tab, now deleted) - coords, ping, fps, time, holding, coinflip, 8ball, dice, plus tps,
 *     location and a Discord link added in the 2026-09-21 Odin-parity pass.
 * </ul>
 * The single master toggle below flips BOTH {@link PartyCommandsConfig#setEnabled} and
 * {@link ChatCommandsConfig#setEnabled} together, so from the player's side there really is one switch - but
 * the two configs stay separate files/classes underneath (their own {@code killer560smod-*.json}, already
 * wired into {@code ProfileManager.reloadAllConfigs}) so nobody's existing settings in either file get
 * migrated or reset just because the GUI merged. If the two ever disagree (e.g. a config file was hand-edited,
 * or a profile was made before this merge), the toggle just shows "on" whenever either one already was and
 * turning it off/on brings both back in sync - so upgrading never silently turns something off that was
 * already working.
 * <p>
 * Every command from both features - party-management and informational - is its own toggle and ships OFF
 * by default UNLESS it already existed before per-command toggles were added (the 8 original informational
 * replies default ON, matching how they behaved before: always active whenever Chat Commands + the channel
 * were on). See {@link PartyCommandsConfig.Command} and {@link InfoCommand} for the exact list; per
 * killer560's standing instruction this mirrors every Odin command with no extra safety gate beyond what
 * Odin itself has for that command, and the party-mutating ones keep the separate "Allow Destructive
 * Commands" switch on top of their own toggle, same as before.
 */
public class PartyCommandsTab extends BaseTab {

    public PartyCommandsTab() {
        super("Party Commands");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        PartyCommandsConfig partyCfg = PartyCommandsConfig.getInstance();
        ChatCommandsConfig chatCfg = ChatCommandsConfig.getInstance();
        boolean masterOn = partyCfg.isEnabledRaw() || chatCfg.isEnabledRaw();
        int y = contentY;
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colAX = contentX;
        int colBX = contentX + colW + gap;

        widgets.add(SettingsButtonWidget.builder(onOff("Party Commands", masterOn), btn -> {
                    boolean next = !masterOn;
                    partyCfg.setEnabled(next);
                    partyCfg.save();
                    chatCfg.setEnabled(next);
                    chatCfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!masterOn) {
            return widgets;
        }

        // ------------------------------------------------------------ Party Management
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Party Management", false), Minecraft.getInstance().font));
        y += 16;
        y = addCommandToggles(widgets, partyCfg, false, y, colAX, colBX, colW);

        widgets.add(SettingsButtonWidget.builder(onOff("Confirm Invites", partyCfg.isConfirmInvites()), btn -> {
                    partyCfg.setConfirmInvites(!partyCfg.isConfirmInvites());
                    partyCfg.save();
                    btn.setMessage(onOff("Confirm Invites", partyCfg.isConfirmInvites()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Destructive Commands", false), Minecraft.getInstance().font));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(onOff("Allow Destructive Commands", partyCfg.isAllowDestructive()), btn -> {
                    partyCfg.setAllowDestructive(!partyCfg.isAllowDestructive());
                    partyCfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        if (partyCfg.isAllowDestructive()) {
            y = addCommandToggles(widgets, partyCfg, true, y, colAX, colBX, colW);
        } else {
            widgets.add(note(contentX, y, contentWidth, "Warp, warp + transfer, kick, reinvite, demote and queue stay off."));
            y += 16;
        }
        y += 6;

        // ------------------------------------------------------------ Info Commands
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Info Commands", false), Minecraft.getInstance().font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Party", chatCfg.isPartyEnabled()), btn -> {
                    chatCfg.setPartyEnabled(!chatCfg.isPartyEnabled());
                    chatCfg.save();
                    btn.setMessage(onOff("Party", chatCfg.isPartyEnabled()));
                }).bounds(colAX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Guild", chatCfg.isGuildEnabled()), btn -> {
                    chatCfg.setGuildEnabled(!chatCfg.isGuildEnabled());
                    chatCfg.save();
                    btn.setMessage(onOff("Guild", chatCfg.isGuildEnabled()));
                }).bounds(colBX, y, colW, 18).build());
        y += 22;
        widgets.add(SettingsButtonWidget.builder(onOff("Private", chatCfg.isPrivateEnabled()), btn -> {
                    chatCfg.setPrivateEnabled(!chatCfg.isPrivateEnabled());
                    chatCfg.save();
                    btn.setMessage(onOff("Private", chatCfg.isPrivateEnabled()));
                }).bounds(colAX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Co-op", chatCfg.isCoopEnabled()), btn -> {
                    chatCfg.setCoopEnabled(!chatCfg.isCoopEnabled());
                    chatCfg.save();
                    btn.setMessage(onOff("Co-op", chatCfg.isCoopEnabled()));
                }).bounds(colBX, y, colW, 18).build());
        y += 24;

        boolean left = true;
        for (InfoCommand info : InfoCommand.values()) {
            String label = info.label();
            int x = left ? colAX : colBX;
            widgets.add(SettingsButtonWidget.builder(onOff(label, chatCfg.isOn(info)), btn -> {
                        chatCfg.setOn(info, !chatCfg.isOn(info));
                        chatCfg.save();
                        btn.setMessage(onOff(label, chatCfg.isOn(info)));
                    }).bounds(x, y, colW, 18).build());
            if (!left) {
                y += 22;
            }
            left = !left;
        }

        return widgets;
    }

    private int addCommandToggles(List<AbstractWidget> widgets, PartyCommandsConfig cfg, boolean destructive, int y,
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

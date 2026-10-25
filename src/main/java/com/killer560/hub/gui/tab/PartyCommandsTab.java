package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.partycommands.PartyCommandsConfig;
import com.killer560.hub.partycommands.PartyCommandsConfig.Channel;
import com.killer560.hub.partycommands.PartyCommandsConfig.Command;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Party Commands - exactly three sections since 2026-10-08 (killer560: "The only 3 sets should be the general toggle,
 * which chats can use (kc gc pc ac etc) then the commands you want able to be used"):
 * <ol>
 * <li>the master toggle, with the reply delay under it (his "very slight delay after it actually receives the
 *     command", 200 ms by default);</li>
 * <li><b>Chats</b> - one toggle per {@link Channel} that may trigger a command;</li>
 * <li><b>Commands</b> - every command in one list, one toggle each (party management and the old informational
 *     replies together; the destructive switch, Confirm Invites and the joke command are gone).</li>
 * </ol>
 * Everything lives in {@link PartyCommandsConfig}; see its class doc for how older settings were carried over.
 */
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

        // ------------------------------------------------------------ 1. the general toggle
        widgets.add(onOffButton("Party Commands", cfg::isEnabledRaw, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        widgets.add(delaySlider(contentX, y, contentWidth, cfg));
        y += 26;

        // ------------------------------------------------------------ 2. which chats can use them
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Chats", false));
        y += 16;
        boolean left = true;
        for (Channel channel : Channel.values()) {
            String label = channel.label();
            widgets.add(SettingsButtonWidget.builder(onOff(label, cfg.isChannelOn(channel)), btn -> {
                        cfg.setChannelOn(channel, !cfg.isChannelOn(channel));
                        cfg.save();
                        btn.setMessage(onOff(label, cfg.isChannelOn(channel)));
                    }).bounds(left ? colAX : colBX, y, colW, 18).build());
            if (!left) {
                y += 22;
            }
            left = !left;
        }
        y = left ? y + 6 : y + 28;

        // ------------------------------------------------------------ 3. the commands
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Commands", false));
        y += 16;
        left = true;
        for (Command command : Command.values()) {
            String label = command.label();
            widgets.add(SettingsButtonWidget.builder(onOff(label, cfg.isOn(command)), btn -> {
                        cfg.setOn(command, !cfg.isOn(command));
                        cfg.save();
                        btn.setMessage(onOff(label, cfg.isOn(command)));
                    }).bounds(left ? colAX : colBX, y, colW, 18).build());
            if (!left) {
                y += 22;
            }
            left = !left;
        }
        return widgets;
    }

    /** 0-1000 ms in steps of 10. */
    private static ThemedSliderButton delaySlider(int x, int y, int w, PartyCommandsConfig cfg) {
        int max = PartyCommandsConfig.MAX_REPLY_DELAY_MS;
        return new ThemedSliderButton(x, y, w, 18, delayText(cfg), cfg.getReplyDelayMs() / (double) max) {
            @Override
            protected void updateMessage() {
                setMessage(delayText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setReplyDelayMs((int) (Math.round(this.value * max / 10.0) * 10));
                saveOnRelease(() -> cfg.save());
            }
        };
    }

    private static Component delayText(PartyCommandsConfig cfg) {
        return Component.literal("Reply Delay: " + cfg.getReplyDelayMs() + " ms");
    }
}

package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeoninfo.DungeonInfoConfig;
import com.killer560.hub.dungeoninfo.DungeonInfoFeature;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Secrets-found HUD, run-time tracker, score-milestone messages, and mimic/prince/bat KILL party
 *  alerts - see {@link com.killer560.hub.dungeoninfo.DungeonInfoFeature}'s own doc for what each one
 *  triggers on. */
public class DungeonInfoTab extends BaseTab {

    public DungeonInfoTab() {
        super("Secrets/Score/Timing");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        DungeonInfoConfig cfg = DungeonInfoConfig.getInstance();

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Secrets count is read from the tab list. Kill alerts only fire in a"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7dungeon, once per run. Check the log if something's off."),
                Minecraft.getInstance().font));
        y += 20;

        widgets.add(SettingsButtonWidget.builder(secretsText(), btn -> {
                    cfg.setSecretsHudEnabled(!cfg.isSecretsHudEnabled());
                    cfg.save();
                    btn.setMessage(secretsText());
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(timeText(), btn -> {
                    cfg.setTimeTrackerEnabled(!cfg.isTimeTrackerEnabled());
                    cfg.save();
                    btn.setMessage(timeText());
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Current run time: " + DungeonInfoFeature.elapsedTimeText()
                        + " §8(" + DungeonInfoFeature.elapsedTimeWithoutLagText() + " without lag)"),
                Minecraft.getInstance().font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Include \"Without Lag\"", cfg.isSendTimeWithoutLag()), btn -> {
                    cfg.setSendTimeWithoutLag(!cfg.isSendTimeWithoutLag());
                    cfg.save();
                    btn.setMessage(onOff("Include \"Without Lag\"", cfg.isSendTimeWithoutLag()));
                }).bounds(contentX, y, 160, 18).build());

        widgets.add(SettingsButtonWidget.builder(Component.literal("Send Time"), btn -> DungeonInfoFeature.sendTime())
                .bounds(contentX + 168, y, 100, 18).build());
        y += 24;

        // Real bug found and fixed (2026-09-14, first real F7 run log): the keyword boxes are gone - a plain
        // "bat" substring matched every "Combat Wisdom" line. Each alert now has a fixed, confirmed trigger.
        y = buildAlertRow(widgets, "Mimic Killed Msg", "§7Trigger: baby zombie (mimic) dies, F6/F7 clear",
                contentX, y, contentWidth,
                cfg.isMimicMessageEnabled(), cfg::setMimicMessageEnabled,
                cfg.getMimicMessage(), cfg::setMimicMessage);
        y = buildAlertRow(widgets, "Prince Killed Msg", "§7Trigger: \"A Prince falls. +1 Bonus Score\"",
                contentX, y, contentWidth,
                cfg.isPrinceMessageEnabled(), cfg::setPrinceMessageEnabled,
                cfg.getPrinceMessage(), cfg::setPrinceMessage);
        y = buildAlertRow(widgets, "Bat Killed Msg", "§7Trigger: \"A Bat has been slain. +1 Bonus Score\"",
                contentX, y, contentWidth,
                cfg.isBatMessageEnabled(), cfg::setBatMessageEnabled,
                cfg.getBatMessage(), cfg::setBatMessage);

        y += 6;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Score Milestone Messages:"), Minecraft.getInstance().font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("270 Msg", cfg.isScore270Enabled()), btn -> {
                    cfg.setScore270Enabled(!cfg.isScore270Enabled());
                    cfg.save();
                    btn.setMessage(onOff("270 Msg", cfg.isScore270Enabled()));
                }).bounds(contentX, y, 140, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Send Now"), btn -> DungeonInfoFeature.sendScore270())
                .bounds(contentX + 146, y, 80, 18).build());
        y += 20;

        EditBox msg270 = new EditBox(Minecraft.getInstance().font, contentX, y, contentWidth, 18, Component.literal("270 message"));
        msg270.setMaxLength(200);
        msg270.setValue(cfg.getScore270Message());
        msg270.setResponder(text -> {
            cfg.setScore270Message(text);
            cfg.save();
        });
        widgets.add(msg270);
        y += 24;

        widgets.add(SettingsButtonWidget.builder(onOff("300 Msg", cfg.isScore300Enabled()), btn -> {
                    cfg.setScore300Enabled(!cfg.isScore300Enabled());
                    cfg.save();
                    btn.setMessage(onOff("300 Msg", cfg.isScore300Enabled()));
                }).bounds(contentX, y, 140, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Send Now"), btn -> DungeonInfoFeature.sendScore300())
                .bounds(contentX + 146, y, 80, 18).build());
        y += 20;

        EditBox msg300 = new EditBox(Minecraft.getInstance().font, contentX, y, contentWidth, 18, Component.literal("300 message"));
        msg300.setMaxLength(200);
        msg300.setValue(cfg.getScore300Message());
        msg300.setResponder(text -> {
            cfg.setScore300Message(text);
            cfg.save();
        });
        widgets.add(msg300);
        y += 20;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7These two are manual - Send Now is their only trigger. For"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7automatic 270/300 alerts use the Score Calculator tab instead."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private interface StringSetter {
        void set(String value);
    }

    private interface BoolSetter {
        void set(boolean value);
    }

    private int buildAlertRow(List<AbstractWidget> widgets, String label, String triggerText, int contentX, int y, int contentWidth,
                               boolean enabled, BoolSetter enabledSetter,
                               String message, StringSetter messageSetter) {
        boolean[] state = {enabled};
        widgets.add(SettingsButtonWidget.builder(onOff(label, state[0]), btn -> {
                    state[0] = !state[0];
                    enabledSetter.set(state[0]);
                    DungeonInfoConfig.getInstance().save();
                    btn.setMessage(onOff(label, state[0]));
                }).bounds(contentX, y, 160, 18).build());

        widgets.add(new StringWidget(contentX + 166, y + 5, Math.max(0, contentWidth - 166), 12,
                Component.literal(triggerText), Minecraft.getInstance().font));
        y += 20;

        EditBox messageField = new EditBox(Minecraft.getInstance().font, contentX, y, contentWidth, 18, Component.literal(label + " message"));
        messageField.setMaxLength(200);
        messageField.setValue(message);
        messageField.setResponder(text -> {
            messageSetter.set(text);
            DungeonInfoConfig.getInstance().save();
        });
        widgets.add(messageField);
        y += 22;

        return y;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private static Component secretsText() {
        return Component.literal("Secrets HUD: " + (DungeonInfoConfig.getInstance().isSecretsHudEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component timeText() {
        return Component.literal("Run Time HUD: " + (DungeonInfoConfig.getInstance().isTimeTrackerEnabled() ? "§aON" : "§cOFF"));
    }
}

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

/** Secrets-found HUD, run-time tracker, score-milestone messages, and mimic/prince/bat keyword
 *  alerts - see {@link com.killer560.hub.dungeoninfo.DungeonInfoFeature}'s own doc for the honest
 *  caveat on secrets-count/keyword accuracy (unverified against a live game this session). */
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
                Component.literal("§7Secrets-count parsing and keyword text below are best-effort - not"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7verified against a live game yet. Check the log if something's off."),
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
                Component.literal("§7Current run time: " + DungeonInfoFeature.elapsedTimeText()),
                Minecraft.getInstance().font));
        y += 24;

        y = buildKeywordRow(widgets, "Mimic", contentX, y, contentWidth,
                cfg.isMimicMessageEnabled(), cfg::setMimicMessageEnabled,
                cfg.getMimicKeyword(), cfg::setMimicKeyword,
                cfg.getMimicMessage(), cfg::setMimicMessage);
        y = buildKeywordRow(widgets, "Prince", contentX, y, contentWidth,
                cfg.isPrinceMessageEnabled(), cfg::setPrinceMessageEnabled,
                cfg.getPrinceKeyword(), cfg::setPrinceKeyword,
                cfg.getPrinceMessage(), cfg::setPrinceMessage);
        y = buildKeywordRow(widgets, "Bat", contentX, y, contentWidth,
                cfg.isBatMessageEnabled(), cfg::setBatMessageEnabled,
                cfg.getBatKeyword(), cfg::setBatKeyword,
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
                Component.literal("§7No live automatic score detection yet - Send Now / a future keybind"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7is the trigger for now (Hypixel's real score formula isn't ported)."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private interface StringSetter {
        void set(String value);
    }

    private interface BoolSetter {
        void set(boolean value);
    }

    private int buildKeywordRow(List<AbstractWidget> widgets, String label, int contentX, int y, int contentWidth,
                                 boolean enabled, BoolSetter enabledSetter,
                                 String keyword, StringSetter keywordSetter,
                                 String message, StringSetter messageSetter) {
        widgets.add(SettingsButtonWidget.builder(onOff(label, enabled), btn -> {
                    enabledSetter.set(!enabled);
                    DungeonInfoConfig.getInstance().save();
                    btn.setMessage(onOff(label, !enabled));
                }).bounds(contentX, y, 140, 18).build());

        EditBox keywordField = new EditBox(Minecraft.getInstance().font, contentX + 146, y, 80, 18, Component.literal(label + " keyword"));
        keywordField.setMaxLength(30);
        keywordField.setValue(keyword);
        keywordField.setResponder(text -> {
            keywordSetter.set(text);
            DungeonInfoConfig.getInstance().save();
        });
        widgets.add(keywordField);
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

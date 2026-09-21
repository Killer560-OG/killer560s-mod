package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeoninfo.DungeonInfoConfig;
import com.killer560.hub.dungeoninfo.DungeonInfoFeature;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.splittimers.SplitTimersConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Time HUD settings - run elapsed / no-lag timer, new 2026-09-21 (killer560's secret/score/time HUD split:
 *  "a time hud that displays split timers and whatnot"). This tab does NOT own split timers - the full
 *  split breakdown is {@code SplitTimersFeature}'s own separately-movable, separately-toggleable HUD (see
 *  the "Split Timers" tab). All this tab's "Show Current Split" toggle does is mirror that feature's own
 *  public {@code getCurrentSegmentLabel()}/{@code getCurrentSegmentStartedAtMs()} getters as one extra line
 *  here - it does not read chat or re-derive splits itself, and does nothing while Split Timers is off. */
public class TimeHudTab extends BaseTab {

    public TimeHudTab() {
        super("Time HUD");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        DungeonInfoConfig cfg = DungeonInfoConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(timeText(), btn -> {
                    cfg.setTimeTrackerEnabled(!cfg.isTimeTrackerEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        if (!cfg.isTimeTrackerEnabled()) {
            return widgets;
        }

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
        y += 26;

        widgets.add(SettingsButtonWidget.builder(onOff("Show Current Split", cfg.isShowCurrentSplit()), btn -> {
                    cfg.setShowCurrentSplit(!cfg.isShowCurrentSplit());
                    cfg.save();
                    btn.setMessage(onOff("Show Current Split", cfg.isShowCurrentSplit()));
                }).bounds(contentX, y, 220, 18).build());
        y += 20;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Mirrors Split Timers' own current-segment name/time - needs"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Split Timers enabled too (" + (SplitTimersConfig.getInstance().isEnabled() ? "§aON" : "§cOFF")
                        + "§7 right now). Full split list is that tab's own HUD."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private static Component timeText() {
        return Component.literal("Time HUD: " + (DungeonInfoConfig.getInstance().isTimeTrackerEnabled() ? "§aON" : "§cOFF"));
    }
}

package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.splittimers.TerminalTimersConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Terminal Timers settings - see {@link com.killer560.hub.splittimers.TerminalTimersFeature}. Lives with Tick
 *  Timers and Split Timers (killer560's "add this into the timer section"). */
public class TerminalTimersTab extends BaseTab {

    public TerminalTimersTab() {
        super("Terminal Timers");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        TerminalTimersConfig cfg = TerminalTimersConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Terminal Timers", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Terminal Solve Times", cfg.getSolveTimesRaw()), btn -> {
                    cfg.setSolveTimes(!cfg.getSolveTimesRaw());
                    cfg.save();
                    btn.setMessage(onOff("Terminal Solve Times", cfg.getSolveTimesRaw()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 20;
        widgets.add(label(contentX, y, contentWidth, "§7\"Panes solved in 3.21s!\" after each terminal you complete."));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Terminal/Device Splits", cfg.getSplitsRaw()), btn -> {
                    cfg.setSplits(!cfg.getSplitsRaw());
                    cfg.save();
                    btn.setMessage(onOff("Terminal/Device Splits", cfg.getSplitsRaw()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 20;
        widgets.add(label(contentX, y, contentWidth, "§7Adds (section | phase) times to every terminal/device/lever"));
        y += 12;
        widgets.add(label(contentX, y, contentWidth, "§7completion line, and all section times when the core opens."));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Simon Says Time", cfg.getSimonSaysTimeRaw()), btn -> {
                    cfg.setSimonSaysTime(!cfg.getSimonSaysTimeRaw());
                    cfg.save();
                    btn.setMessage(onOff("Simon Says Time", cfg.getSimonSaysTimeRaw()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 20;
        widgets.add(label(contentX, y, contentWidth, "§7\"Whole device solved in X\" after Simon Says."));

        return widgets;
    }

    private static StringWidget label(int x, int y, int width, String text) {
        return new StringWidget(x, y, width, 12, Component.literal(text), Minecraft.getInstance().font);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

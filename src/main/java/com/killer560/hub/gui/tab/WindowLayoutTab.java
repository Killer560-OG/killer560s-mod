package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.windowlayout.WindowLayoutConfig;
import com.killer560.hub.windowlayout.WindowLayoutFeature;
import com.killer560.hub.windowlayout.WindowMonitors;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Window Layout settings - tile several Minecraft instances on one monitor. */
public class WindowLayoutTab extends BaseTab implements KeyCaptureTab {

    private boolean capturingPickerKey = false;

    public WindowLayoutTab() {
        super("Window Layout");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        WindowLayoutConfig cfg = WindowLayoutConfig.getInstance();
        int y = contentY;
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colBX = contentX + colW + gap;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Layout Picker"), btn -> WindowLayoutFeature.openPicker())
                .bounds(contentX, y, contentWidth, 20).build());
        y += 28;

        int span = WindowLayoutConfig.MAX_WINDOWS - WindowLayoutConfig.MIN_WINDOWS;
        widgets.add(new ThemedSliderButton(contentX, y, colW, 18, windowsText(cfg),
                (cfg.getWindowsPerMonitor() - WindowLayoutConfig.MIN_WINDOWS) / (double) span) {
            @Override
            protected void updateMessage() {
                setMessage(windowsText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setWindowsPerMonitor(WindowLayoutConfig.MIN_WINDOWS + (int) Math.round(this.value * span));
                cfg.save();
            }
        });

        widgets.add(SettingsButtonWidget.builder(monitorText(cfg), btn -> {
                    List<WindowMonitors.MonitorInfo> monitors = WindowMonitors.list();
                    // Cycle Auto -> 1 -> 2 -> ... -> Auto.
                    WindowMonitors.MonitorInfo current = cfg.isMonitorAuto() ? null
                            : WindowMonitors.find(monitors, cfg.getMonitorIndex(), cfg.getMonitorDevice(), cfg.getMonitorName());
                    int next = current == null ? 0 : current.index() + 1;
                    if (next >= monitors.size()) {
                        cfg.setMonitorAuto();
                    } else {
                        cfg.setMonitor(monitors.get(next));
                    }
                    cfg.save();
                    btn.setMessage(monitorText(cfg));
                }).bounds(colBX, y, colW, 18).build());
        y += 22;

        widgets.add(new ThemedSliderButton(contentX, y, colW, 18, gapText(cfg),
                cfg.getGap() / (double) WindowLayoutConfig.MAX_GAP) {
            @Override
            protected void updateMessage() {
                setMessage(gapText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setGap((int) Math.round(this.value * WindowLayoutConfig.MAX_GAP));
                cfg.save();
            }
        });

        widgets.add(SettingsButtonWidget.builder(onOff("Respect Taskbar", cfg.isRespectTaskbar()), btn -> {
                    cfg.setRespectTaskbar(!cfg.isRespectTaskbar());
                    cfg.save();
                    btn.setMessage(onOff("Respect Taskbar", cfg.isRespectTaskbar()));
                }).bounds(colBX, y, colW, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Restore on Launch", cfg.isRestoreOnLaunch()), btn -> {
                    cfg.setRestoreOnLaunch(!cfg.isRestoreOnLaunch());
                    cfg.save();
                    btn.setMessage(onOff("Restore on Launch", cfg.isRestoreOnLaunch()));
                }).bounds(contentX, y, colW, 18).build());

        Component keyLabel = capturingPickerKey ? Component.literal("Press any key...") : pickerKeyText(cfg);
        widgets.add(SettingsButtonWidget.builder(keyLabel, btn -> {
                    capturingPickerKey = true;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(colBX, y, colW, 18).build());

        return widgets;
    }

    private static Component windowsText(WindowLayoutConfig cfg) {
        return Component.literal("Windows per Monitor: " + cfg.getWindowsPerMonitor());
    }

    private static Component gapText(WindowLayoutConfig cfg) {
        return Component.literal("Gap: " + cfg.getGap() + "px");
    }

    private static Component monitorText(WindowLayoutConfig cfg) {
        if (cfg.isMonitorAuto()) {
            return Component.literal("Monitor: §bAuto");
        }
        WindowMonitors.MonitorInfo m = WindowMonitors.find(WindowMonitors.list(), cfg.getMonitorIndex(),
                cfg.getMonitorDevice(), cfg.getMonitorName());
        return Component.literal("Monitor: §b" + (m == null ? (cfg.getMonitorIndex() + 1) + " (missing)" : m.label()));
    }

    private static Component pickerKeyText(WindowLayoutConfig cfg) {
        String name = cfg.getPickerKeyCode() < 0 ? "Not Set"
                : InputConstants.Type.KEYSYM.getOrCreate(cfg.getPickerKeyCode()).getDisplayName().getString();
        return Component.literal("Picker Key: §b" + name);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    @Override
    public boolean isListeningForKey() {
        return capturingPickerKey;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        WindowLayoutConfig cfg = WindowLayoutConfig.getInstance();
        cfg.setPickerKeyCode(keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
        capturingPickerKey = false;
        cfg.save();
    }
}

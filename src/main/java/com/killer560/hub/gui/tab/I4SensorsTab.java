package com.killer560.hub.gui.tab;

import com.killer560.hub.autoleap.AutoLeapConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.i4sensors.I4SensorsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** "Sharp Shooter (i4)" - everything i4 in one section, laid out like {@link SimonSaysTab} (killer560's own
 *  request, 2026-09-14): the always-on i4 sensors' verbose-diff toggle on every build, then Auto i4 (Mode
 *  Rotate/No Rotate, Rotation Time, Predictions) plus the Auto Leap i4 trigger behind the same red
 *  "Cheat Build - Automation" divider on the cheat build only. See
 *  {@link com.killer560.hub.i4sensors.AutoI4Feature} and {@link com.killer560.hub.i4sensors.I4SensorsFeature}. */
public class I4SensorsTab extends BaseTab {

    public I4SensorsTab() {
        super("Sharp Shooter (i4)");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        I4SensorsConfig cfg = I4SensorsConfig.getInstance();
        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2aX = contentX;
        int col2bX = contentX + col2W + gap;

        widgets.add(SettingsButtonWidget.builder(onOff("Verbose Sensor Logging", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Verbose Sensor Logging", cfg.isEnabled()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7i4 sensors always log near the device ([I4Sensors] in latest.log)."),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Verbose adds a capped block diff of the whole area around it."),
                Minecraft.getInstance().font));
        y += 20;

        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§c§lCheat Build - Automation"), Minecraft.getInstance().font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Auto i4", cfg.isAutoI4Enabled()), btn -> {
                    cfg.setAutoI4Enabled(!cfg.isAutoI4Enabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        if (cfg.isAutoI4Enabled()) {
            widgets.add(SettingsButtonWidget.builder(rotateText(cfg), btn -> {
                        cfg.setAutoI4Rotate(!cfg.isAutoI4Rotate());
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(col2aX, y, col2W, 18).build());

            widgets.add(SettingsButtonWidget.builder(onOff("Predictions", cfg.isAutoI4Predictions()), btn -> {
                        cfg.setAutoI4Predictions(!cfg.isAutoI4Predictions());
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(col2bX, y, col2W, 18).build());
            y += 22;

            // Noamm forces 170ms while Predictions is on; the slider only applies with Predictions off.
            if (cfg.isAutoI4Rotate() && !cfg.isAutoI4Predictions()) {
                double norm = cfg.getAutoI4RotationTimeMs() / (double) I4SensorsConfig.MAX_ROTATION_TIME_MS;
                widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18,
                        Component.literal(rotationLabel(cfg)), norm) {
                    @Override
                    protected void updateMessage() {
                        setMessage(Component.literal(rotationLabel(cfg)));
                    }

                    @Override
                    protected void applyValue() {
                        cfg.setAutoI4RotationTimeMs((int) Math.round(this.value * I4SensorsConfig.MAX_ROTATION_TIME_MS));
                        cfg.save();
                    }
                });
                y += 22;
            } else if (cfg.isAutoI4Rotate()) {
                widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                        Component.literal("§7Rotation Time is fixed at 170ms while Predictions is on."),
                        Minecraft.getInstance().font));
                y += 16;
            }
        }

        // Same setting as Auto Leap's "I4 Device" trigger - mirrored here so everything i4 is in one place.
        AutoLeapConfig leapCfg = AutoLeapConfig.getInstance();
        widgets.add(SettingsButtonWidget.builder(onOff("Auto Leap When i4 Done", leapCfg.isLeapOnI4Device()), btn -> {
                    leapCfg.setLeapOnI4Device(!leapCfg.isLeapOnI4Device());
                    leapCfg.save();
                    btn.setMessage(onOff("Auto Leap When i4 Done", leapCfg.isLeapOnI4Device()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 20;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Uses Auto Leap's own target - Auto Leap itself must be ON."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static String rotationLabel(I4SensorsConfig cfg) {
        return "Rotation Time: " + cfg.getAutoI4RotationTimeMs() + "ms";
    }

    private static Component rotateText(I4SensorsConfig cfg) {
        return Component.literal(cfg.isAutoI4Rotate() ? "Mode: §bRotate" : "Mode: §bNo Rotate");
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

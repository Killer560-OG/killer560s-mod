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
 *  request, 2026-09-14): the visual Solver on every build, then Auto i4 (Mode, Weapon, Rotation Time,
 *  Predictions, Auto Swap To Bow, Auto Mask + Order) plus the mirrored Auto Leap i4 trigger behind the red
 *  "Cheat Build - Automation" divider on the cheat build only. No explanatory text lines (killer560: "remove
 *  the random text under sharp shooter"). See {@link com.killer560.hub.i4sensors.AutoI4Feature}. */
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

        widgets.add(SettingsButtonWidget.builder(onOff("Solver", cfg.isSolverEnabled()), btn -> {
                    cfg.setSolverEnabled(!cfg.isSolverEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Solver", cfg.isSolverEnabled()));
                }).bounds(col2aX, y, col2W, 18).build());

        widgets.add(SettingsButtonWidget.builder(com.killer560.hub.gui.ColorSwatch.label("Aim Marker Color", cfg.getSolverColor()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new com.killer560.hub.gui.ColorPickerScreen(client.screen, "Aim Marker Color",
                            cfg.getSolverColor(), I4SensorsConfig.DEFAULT_SOLVER_COLOR, argb -> {
                        cfg.setSolverColor(argb);
                        cfg.save();
                    }));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 28;

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

            widgets.add(SettingsButtonWidget.builder(weaponText(cfg), btn -> {
                        I4SensorsConfig.Weapon[] all = I4SensorsConfig.Weapon.values();
                        cfg.setAutoI4Weapon(all[(cfg.getAutoI4Weapon().ordinal() + 1) % all.length]);
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(col2bX, y, col2W, 18).build());
            y += 22;

            boolean terminator = cfg.getAutoI4Weapon() == I4SensorsConfig.Weapon.TERMINATOR;
            if (cfg.isAutoI4Rotate()) {
                double norm = cfg.getAutoI4RotationTimeMs() / (double) I4SensorsConfig.MAX_ROTATION_TIME_MS;
                widgets.add(new ThemedSliderButton(contentX, y, terminator ? col2W : contentWidth, 18,
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
            }
            if (terminator) {
                int predX = cfg.isAutoI4Rotate() ? col2bX : contentX;
                int predW = cfg.isAutoI4Rotate() ? col2W : contentWidth;
                widgets.add(SettingsButtonWidget.builder(onOff("Predictions", cfg.getAutoI4PredictionsSetting()), btn -> {
                            cfg.setAutoI4Predictions(!cfg.getAutoI4PredictionsSetting());
                            cfg.save();
                            btn.setMessage(onOff("Predictions", cfg.getAutoI4PredictionsSetting()));
                        }).bounds(predX, y, predW, 18).build());
            }
            if (cfg.isAutoI4Rotate() || terminator) {
                y += 22;
            }

            widgets.add(SettingsButtonWidget.builder(onOff("Auto Swap To Bow", cfg.isAutoSwapToBow()), btn -> {
                        cfg.setAutoSwapToBow(!cfg.isAutoSwapToBow());
                        cfg.save();
                        btn.setMessage(onOff("Auto Swap To Bow", cfg.isAutoSwapToBow()));
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 22;

            widgets.add(new com.killer560.hub.gui.RangeSliderWidget(contentX, y, contentWidth, 18,
                    I4SensorsConfig.MIN_CPS, I4SensorsConfig.MAX_CPS, cfg.getCpsMin(), cfg.getCpsMax()) {
                @Override
                protected Component label(int low, int high) {
                    return Component.literal("CPS: " + low + " - " + high);
                }

                @Override
                protected void onRangeChanged(int low, int high) {
                    cfg.setCpsRange(low, high);
                    cfg.save();
                }
            });
            y += 22;

            widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18,
                    Component.literal(accuracyLabel(cfg)), cfg.getShotAccuracyPercent() / 100.0) {
                @Override
                protected void updateMessage() {
                    setMessage(Component.literal(accuracyLabel(cfg)));
                }

                @Override
                protected void applyValue() {
                    cfg.setShotAccuracyPercent((int) Math.round(this.value * 100));
                    cfg.save();
                }
            });
            y += 22;

            widgets.add(SettingsButtonWidget.builder(onOff("Auto Mask", cfg.isAutoMask()), btn -> {
                        cfg.setAutoMask(!cfg.isAutoMask());
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(cfg.isAutoMask() ? col2aX : contentX, y, cfg.isAutoMask() ? col2W : contentWidth, 18).build());
            if (cfg.isAutoMask()) {
                widgets.add(SettingsButtonWidget.builder(orderText(cfg), btn -> {
                            cfg.setMaskOrderIndex(cfg.getMaskOrderIndex() + 1);
                            cfg.save();
                            btn.setMessage(orderText(cfg));
                        }).bounds(col2bX, y, col2W, 18).build());
            }
            y += 22;
        }

        // Same setting as Auto Leap's "I4 Device" trigger - mirrored here so everything i4 is in one place.
        AutoLeapConfig leapCfg = AutoLeapConfig.getInstance();
        widgets.add(SettingsButtonWidget.builder(onOff("Auto Leap When i4 Done", leapCfg.isLeapOnI4Device()), btn -> {
                    leapCfg.setLeapOnI4Device(!leapCfg.isLeapOnI4Device());
                    leapCfg.save();
                    btn.setMessage(onOff("Auto Leap When i4 Done", leapCfg.isLeapOnI4Device()));
                }).bounds(contentX, y, contentWidth, 18).build());

        return widgets;
    }

    private static String accuracyLabel(I4SensorsConfig cfg) {
        return "Shot Accuracy: " + cfg.getShotAccuracyPercent() + "%";
    }

    private static String rotationLabel(I4SensorsConfig cfg) {
        return "Rotation Time: " + cfg.getAutoI4RotationTimeMs() + "ms";
    }

    private static Component rotateText(I4SensorsConfig cfg) {
        return Component.literal(cfg.isAutoI4Rotate() ? "Mode: §bRotate" : "Mode: §bNo Rotate");
    }

    private static Component weaponText(I4SensorsConfig cfg) {
        return Component.literal("Weapon: §b" + cfg.getAutoI4Weapon().label);
    }

    private static Component orderText(I4SensorsConfig cfg) {
        return Component.literal("Order: §b" + I4SensorsConfig.orderLabel(cfg.getMaskOrder()));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

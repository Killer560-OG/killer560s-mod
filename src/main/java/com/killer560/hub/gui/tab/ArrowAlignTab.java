package com.killer560.hub.gui.tab;

import com.killer560.hub.arrowalign.ArrowAlignConfig;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.RangeSliderWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.killer560.hub.compat.McCompat;

/** Arrow Align (F7/M7 P3 third device) settings - see {@link com.killer560.hub.arrowalign.ArrowAlignFeature}. Laid out
 *  like {@link SimonSaysTab}: legit rows first, Trigger Bot/Aura only on the cheat build behind the red divider. */
public class ArrowAlignTab extends BaseTab {

    public ArrowAlignTab() {
        super("Arrow Align");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        ArrowAlignConfig cfg = ArrowAlignConfig.getInstance();

        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2aX = contentX;
        int col2bX = contentX + col2W + gap;

        widgets.add(SettingsButtonWidget.builder(onOff("Solver", cfg.getSolverRaw()), btn -> {
                    cfg.setSolverEnabled(!cfg.getSolverRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (cfg.getSolverRaw()) {
            widgets.add(SettingsButtonWidget.builder(onOff("Highlight Frames", cfg.isHighlightFrames()), btn -> {
                        cfg.setHighlightFrames(!cfg.isHighlightFrames());
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(col2aX, y, col2W, 18).build());

            float minScale = ArrowAlignConfig.MIN_NUMBER_SCALE;
            float maxScale = ArrowAlignConfig.MAX_NUMBER_SCALE;
            double scaleNorm = (cfg.getNumberScale() - minScale) / (double) (maxScale - minScale);
            widgets.add(new ThemedSliderButton(col2bX, y, col2W, 18, scaleText(cfg), scaleNorm) {
                @Override
                protected void updateMessage() {
                    setMessage(scaleText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setNumberScale((float) (minScale + this.value * (maxScale - minScale)));
                    cfg.save();
                }
            });
            y += 22;

            if (cfg.isHighlightFrames()) {
                widgets.add(SettingsButtonWidget.builder(
                        ColorSwatch.label("Highlight Colour", cfg.getHighlightColor()), btn -> {
                            Minecraft client = Minecraft.getInstance();
                            McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), "Highlight Colour",
                                    cfg.getHighlightColor(), ArrowAlignConfig.DEFAULT_HIGHLIGHT_COLOR, argb -> {
                                cfg.setHighlightColor(argb);
                                cfg.save();
                            }));
                        }).bounds(contentX, y, contentWidth, 18).build());
                y += 22;
            }
        }
        y += 6;

        widgets.add(SettingsButtonWidget.builder(onOff("Prevent Misclicks", cfg.getPreventMisclicksRaw()), btn -> {
                    cfg.setPreventMisclicksEnabled(!cfg.getPreventMisclicksRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(cfg.getPreventMisclicksRaw() ? col2aX : contentX, y,
                cfg.getPreventMisclicksRaw() ? col2W : contentWidth, 18).build());

        if (cfg.getPreventMisclicksRaw()) {
            widgets.add(SettingsButtonWidget.builder(onOff("Crouch To Override", cfg.isCrouchOverride()), btn -> {
                        cfg.setCrouchOverride(!cfg.isCrouchOverride());
                        cfg.save();
                        btn.setMessage(onOff("Crouch To Override", cfg.isCrouchOverride()));
                    }).bounds(col2bX, y, col2W, 18).build());
        }
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Solve Time", cfg.getSolveTimeRaw()), btn -> {
                    cfg.setSolveTimeEnabled(!cfg.getSolveTimeRaw());
                    cfg.save();
                    btn.setMessage(onOff("Solve Time", cfg.getSolveTimeRaw()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 28;

        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Cheat Build - Automation", true), Minecraft.getInstance().font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Trigger Bot", cfg.getTriggerBotRaw()), btn -> {
                    cfg.setTriggerBotEnabled(!cfg.getTriggerBotRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col2aX, y, col2W, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Aura", cfg.getAuraRaw()), btn -> {
                    cfg.setAuraEnabled(!cfg.getAuraRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col2bX, y, col2W, 18).build());
        y += 22;

        if (cfg.getTriggerBotRaw()) {
            widgets.add(new RangeSliderWidget(contentX, y, contentWidth, 18,
                    ArrowAlignConfig.MIN_TRIGGER_BOT_CPS, ArrowAlignConfig.MAX_TRIGGER_BOT_CPS,
                    cfg.getTriggerBotCpsMin(), cfg.getTriggerBotCpsMax()) {
                @Override
                protected Component label(int low, int high) {
                    return Component.literal("Trigger Bot CPS: " + low + " - " + high);
                }

                @Override
                protected void onRangeChanged(int low, int high) {
                    cfg.setTriggerBotCpsRange(low, high);
                    cfg.save();
                }
            });
            y += 22;
            widgets.add(SettingsButtonWidget.builder(onOff("Disable Trigger Bot While Crouching",
                            cfg.isTriggerBotDisableWhileCrouching()), btn -> {
                        cfg.setTriggerBotDisableWhileCrouching(!cfg.isTriggerBotDisableWhileCrouching());
                        cfg.save();
                        btn.setMessage(onOff("Disable Trigger Bot While Crouching", cfg.isTriggerBotDisableWhileCrouching()));
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 22;
        }

        if (cfg.getAuraRaw()) {
            widgets.add(SettingsButtonWidget.builder(onOff("Disable Aura While Crouching",
                            cfg.isAuraDisableWhileCrouching()), btn -> {
                        cfg.setAuraDisableWhileCrouching(!cfg.isAuraDisableWhileCrouching());
                        cfg.save();
                        btn.setMessage(onOff("Disable Aura While Crouching", cfg.isAuraDisableWhileCrouching()));
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 22;
            widgets.add(SettingsButtonWidget.cycling(Component.literal("Aura Clicks Per Tick: " + cfg.getAuraClicksPerTick()),
                    (btn, dir) -> {
                        cfg.setAuraClicksPerTick((int) SettingsButtonWidget.step(cfg.getAuraClicksPerTick(), 1, 1, 4, dir));
                        cfg.save();
                        btn.setMessage(Component.literal("Aura Clicks Per Tick: " + cfg.getAuraClicksPerTick()));
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 22;
            widgets.add(SettingsButtonWidget.builder(onOff("Leave Last Arrow", cfg.isAuraLeaveLastArrow()), btn -> {
                        cfg.setAuraLeaveLastArrow(!cfg.isAuraLeaveLastArrow());
                        cfg.save();
                        btn.setMessage(onOff("Leave Last Arrow", cfg.isAuraLeaveLastArrow()));
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 22;
            widgets.add(SettingsButtonWidget.builder(onOff("Disable Leave Last Arrow While Crouching",
                            cfg.isLeaveLastArrowDisableWhileCrouching()), btn -> {
                        cfg.setLeaveLastArrowDisableWhileCrouching(!cfg.isLeaveLastArrowDisableWhileCrouching());
                        cfg.save();
                        btn.setMessage(onOff("Disable Leave Last Arrow While Crouching",
                                cfg.isLeaveLastArrowDisableWhileCrouching()));
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 22;
            int minCps = ArrowAlignConfig.MIN_AURA_CPS;
            int maxCps = ArrowAlignConfig.MAX_AURA_CPS;
            widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18,
                    Component.literal("Aura CPS: " + cfg.getAuraCps()),
                    (cfg.getAuraCps() - minCps) / (double) (maxCps - minCps)) {
                @Override
                protected void updateMessage() {
                    setMessage(Component.literal("Aura CPS: " + cfg.getAuraCps()));
                }

                @Override
                protected void applyValue() {
                    cfg.setAuraCps((int) Math.round(minCps + this.value * (maxCps - minCps)));
                    cfg.save();
                }
            });
            y += 22;

            double minRange = ArrowAlignConfig.MIN_AURA_RANGE;
            double maxRange = ArrowAlignConfig.MAX_AURA_RANGE;
            widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, rangeText(cfg),
                    (cfg.getAuraRange() - minRange) / (maxRange - minRange)) {
                @Override
                protected void updateMessage() {
                    setMessage(rangeText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setAuraRange(minRange + this.value * (maxRange - minRange));
                    cfg.save();
                }
            });
        }
        return widgets;
    }

    private static Component scaleText(ArrowAlignConfig cfg) {
        return Component.literal(String.format(Locale.US, "Scale: %.2fx", cfg.getNumberScale()));
    }

    private static Component rangeText(ArrowAlignConfig cfg) {
        return Component.literal(String.format(Locale.US, "Aura Range: %.1f", cfg.getAuraRange()));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

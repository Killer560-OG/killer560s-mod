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

        widgets.add(onOffButton("Solver", cfg::getSolverRaw, cfg::setSolverEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 24;

        if (cfg.getSolverRaw()) {
            widgets.add(onOffButton("Highlight Frames", cfg::isHighlightFrames, cfg::setHighlightFrames, cfg::save,
                    requestRebuild, col2aX, y, col2W, 18));

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
                    saveOnRelease(() -> cfg.save());
                }
            });
            y += 22;

            if (cfg.isHighlightFrames()) {
                widgets.add(SettingsButtonWidget.builder(
                        ColorSwatch.label("Highlight Color", cfg.getHighlightColor()), btn -> {
                            Minecraft client = Minecraft.getInstance();
                            McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), "Highlight Color",
                                    cfg.getHighlightColor(), ArrowAlignConfig.DEFAULT_HIGHLIGHT_COLOR, argb -> {
                                cfg.setHighlightColor(argb);
                                cfg.save();
                            }));
                        }).bounds(contentX, y, contentWidth, 18).build());
                y += 22;
            }
        }
        y += 6;

        widgets.add(onOffButton("Prevent Misclicks", cfg::getPreventMisclicksRaw, cfg::setPreventMisclicksEnabled,
                cfg::save, requestRebuild, cfg.getPreventMisclicksRaw() ? col2aX : contentX, y,
                cfg.getPreventMisclicksRaw() ? col2W : contentWidth, 18));

        if (cfg.getPreventMisclicksRaw()) {
            widgets.add(onOffButton("Crouch To Override", cfg::isCrouchOverride, cfg::setCrouchOverride, cfg::save,
                    null, col2bX, y, col2W, 18));
        }
        y += 22;

        widgets.add(onOffButton("Solve Time", cfg::getSolveTimeRaw, cfg::setSolveTimeEnabled, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 28;

        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }

        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Cheat Build - Automation", true));
        y += 16;

        widgets.add(onOffButton("Trigger Bot", cfg::getTriggerBotRaw, cfg::setTriggerBotEnabled, cfg::save,
                requestRebuild, col2aX, y, col2W, 18));

        widgets.add(onOffButton("Aura", cfg::getAuraRaw, cfg::setAuraEnabled, cfg::save,
                requestRebuild, col2bX, y, col2W, 18));
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
            widgets.add(onOffButton("Disable Trigger Bot While Crouching", cfg::isTriggerBotDisableWhileCrouching,
                    cfg::setTriggerBotDisableWhileCrouching, cfg::save, null, contentX, y, contentWidth, 18));
            y += 22;
        }

        if (cfg.getAuraRaw()) {
            widgets.add(onOffButton("Disable Aura While Crouching", cfg::isAuraDisableWhileCrouching,
                    cfg::setAuraDisableWhileCrouching, cfg::save, null, contentX, y, contentWidth, 18));
            y += 22;
            widgets.add(SettingsButtonWidget.cycling(Component.literal("Aura Clicks Per Tick: " + cfg.getAuraClicksPerTick()),
                    (btn, dir) -> {
                        cfg.setAuraClicksPerTick((int) SettingsButtonWidget.step(cfg.getAuraClicksPerTick(), 1, 1, 4, dir));
                        cfg.save();
                        btn.setMessage(Component.literal("Aura Clicks Per Tick: " + cfg.getAuraClicksPerTick()));
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 22;
            widgets.add(onOffButton("Leave Last Arrow", cfg::isAuraLeaveLastArrow, cfg::setAuraLeaveLastArrow,
                    cfg::save, null, contentX, y, contentWidth, 18));
            y += 22;
            widgets.add(onOffButton("Disable Leave Last Arrow While Crouching",
                    cfg::isLeaveLastArrowDisableWhileCrouching, cfg::setLeaveLastArrowDisableWhileCrouching,
                    cfg::save, null, contentX, y, contentWidth, 18));
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
                    saveOnRelease(() -> cfg.save());
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
                    saveOnRelease(() -> cfg.save());
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
}

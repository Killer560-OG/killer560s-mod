package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.simonsays.SimonSaysConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Simon Says solver + automation settings - see {@link com.killer560.hub.simonsays.SimonSaysFeature}'s
 *  class doc for the real device layout/detection logic this is built on (ported from Odin/QUOI/
 *  NoammAddons, confirmed against this exact Minecraft version). Auto-solve/trigger-bot/auto-start rows
 *  only appear on the cheat build - the legit build can't run them even with a copied config.json (see
 *  the config getters' own gating). Restructured 2026-09-14 per killer560's own layout request - no more
 *  section-header/explanation text, just the controls themselves. */
public class SimonSaysTab extends BaseTab implements KeyCaptureTab {

    private boolean capturingAnnounceKey = false;

    public SimonSaysTab() {
        super("Simon Says");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        SimonSaysConfig cfg = SimonSaysConfig.getInstance();
        int col1 = contentX;
        int col2 = contentX + 108;
        int col3 = contentX + 216;

        widgets.add(SettingsButtonWidget.builder(onOff("Simon Says", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Show Highlights", cfg.isSolverEnabled()), btn -> {
                    cfg.setSolverEnabled(!cfg.isSolverEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Show Highlights", cfg.isSolverEnabled()));
                }).bounds(col1, y, 108, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Show Numbers", cfg.isNumberOverlay()), btn -> {
                    cfg.setNumberOverlay(!cfg.isNumberOverlay());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col2, y, 108, 18).build());
        y += 20;

        widgets.add(SettingsButtonWidget.builder(styleText(cfg), btn -> {
                    cfg.setStyle(nextStyle(cfg.getStyle()));
                    cfg.save();
                    btn.setMessage(styleText(cfg));
                }).bounds(col1, y, 108, 18).build());

        if (cfg.isNumberOverlay()) {
            double scaleNorm = (cfg.getNumberScale() - 0.25) / (3.0 - 0.25);
            widgets.add(new ThemedSliderButton(col2, y, 108, 18,
                    Component.literal(String.format(java.util.Locale.US, "Scale: %.2fx", cfg.getNumberScale())), scaleNorm) {
                @Override
                protected void updateMessage() {
                    setMessage(Component.literal(String.format(java.util.Locale.US, "Scale: %.2fx", cfg.getNumberScale())));
                }

                @Override
                protected void applyValue() {
                    cfg.setNumberScale((float) (0.25 + this.value * (3.0 - 0.25)));
                    cfg.save();
                }
            });
        }
        y += 20;

        // Opens a real color-picker screen (hue bar + saturation/value square + alpha) instead of
        // cycling a fixed palette - killer560's explicit request. Each button's own click handler
        // captures a fresh Minecraft/Screen reference each press so re-opening after a rebuild still
        // points at the current tab's own screen instance.
        widgets.add(SettingsButtonWidget.builder(Component.literal("1st Color: ■"), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, "First Color",
                            cfg.getFirstColor(), SimonSaysConfig.DEFAULT_FIRST_COLOR, argb -> {
                        cfg.setFirstColor(argb);
                        cfg.save();
                    }));
                }).bounds(col1, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(Component.literal("2nd Color: ■"), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, "Second Color",
                            cfg.getSecondColor(), SimonSaysConfig.DEFAULT_SECOND_COLOR, argb -> {
                        cfg.setSecondColor(argb);
                        cfg.save();
                    }));
                }).bounds(col2, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(Component.literal("3rd Color: ■"), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, "Third Color+",
                            cfg.getThirdColor(), SimonSaysConfig.DEFAULT_THIRD_COLOR, argb -> {
                        cfg.setThirdColor(argb);
                        cfg.save();
                    }));
                }).bounds(col3, y, 108, 18).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(onOff("Prevent Misclicks", cfg.isPreventMisclicksEnabled()), btn -> {
                    cfg.setPreventMisclicksEnabled(!cfg.isPreventMisclicksEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Prevent Misclicks", cfg.isPreventMisclicksEnabled()));
                }).bounds(col1, y, 160, 18).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(onOff("Announce Progress", cfg.isAnnounceProgress()), btn -> {
                    cfg.setAnnounceProgress(!cfg.isAnnounceProgress());
                    cfg.save();
                    btn.setMessage(onOff("Announce Progress", cfg.isAnnounceProgress()));
                }).bounds(col1, y, 108, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Party Tracker", cfg.isPartyProgressTrackerEnabled()), btn -> {
                    cfg.setPartyProgressTrackerEnabled(!cfg.isPartyProgressTrackerEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Party Tracker", cfg.isPartyProgressTrackerEnabled()));
                }).bounds(col2, y, 108, 18).build());
        y += 24;

        Component keyLabel = capturingAnnounceKey ? Component.literal("Press any key...") : announceKeyText(cfg);
        widgets.add(SettingsButtonWidget.builder(keyLabel, btn -> {
                    capturingAnnounceKey = true;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(col1, y, 108, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Message", cfg.isAutoSendResetMessage()), btn -> {
                    cfg.setAutoSendResetMessage(!cfg.isAutoSendResetMessage());
                    cfg.save();
                    btn.setMessage(onOff("Auto Message", cfg.isAutoSendResetMessage()));
                }).bounds(col2, y, 108, 18).build());
        y += 20;

        EditBox resetMsgField = new EditBox(Minecraft.getInstance().font, contentX, y, contentWidth, 18,
                Component.literal("Reset message"));
        resetMsgField.setMaxLength(100);
        resetMsgField.setValue(cfg.getResetMessageText());
        resetMsgField.setResponder(text -> {
            cfg.setResetMessageText(text);
            cfg.save();
        });
        widgets.add(resetMsgField);
        y += 26;

        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Trigger Bot", cfg.isTriggerBotEnabled()), btn -> {
                    cfg.setTriggerBotEnabled(!cfg.isTriggerBotEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Trigger Bot", cfg.isTriggerBotEnabled()));
                }).bounds(col1, y, 108, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Solve", cfg.isAutoSolveEnabled()), btn -> {
                    cfg.setAutoSolveEnabled(!cfg.isAutoSolveEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col2, y, 108, 18).build());
        y += 24;

        if (cfg.isAutoSolveEnabled()) {
            widgets.add(SettingsButtonWidget.builder(rotateText(cfg), btn -> {
                        cfg.setAutoSolveRotate(!cfg.isAutoSolveRotate());
                        cfg.save();
                        btn.setMessage(rotateText(cfg));
                    }).bounds(col1, y, 220, 18).build());
            y += 20;

            widgets.add(SettingsButtonWidget.builder(
                        Component.literal("Timer Target: " + (cfg.getClickTimerTargetMs() / 100) / 10.0 + "s"), btn -> {
                            int next = cfg.getClickTimerTargetMs() + 500;
                            cfg.setClickTimerTargetMs(next > 30_000 ? 1000 : next);
                            cfg.save();
                            btn.setMessage(Component.literal("Timer Target: " + (cfg.getClickTimerTargetMs() / 100) / 10.0 + "s"));
                        }).bounds(col1, y, 108, 18).build());

            widgets.add(SettingsButtonWidget.builder(
                        Component.literal("Variance: ±" + cfg.getClickTimerVarianceMs() + "ms"), btn -> {
                            int next = cfg.getClickTimerVarianceMs() + 50;
                            cfg.setClickTimerVarianceMs(next > 2000 ? 0 : next);
                            cfg.save();
                            btn.setMessage(Component.literal("Variance: ±" + cfg.getClickTimerVarianceMs() + "ms"));
                        }).bounds(col2, y, 108, 18).build());
            y += 24;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Start", cfg.isAutoStartEnabled()), btn -> {
                    cfg.setAutoStartEnabled(!cfg.isAutoStartEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col1, y, 108, 18).build());
        y += 20;

        if (!cfg.isAutoStartEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(
                    Component.literal("Clicks: " + cfg.getAutoStartClicks()), btn -> {
                        int next = cfg.getAutoStartClicks() + 1;
                        cfg.setAutoStartClicks(next > 10 ? 1 : next);
                        cfg.save();
                        btn.setMessage(Component.literal("Clicks: " + cfg.getAutoStartClicks()));
                    }).bounds(col1, y, 108, 18).build());

        widgets.add(SettingsButtonWidget.builder(
                    Component.literal("Delay: " + cfg.getAutoStartClickDelayTicks() + "t"), btn -> {
                        int next = cfg.getAutoStartClickDelayTicks() + 1;
                        cfg.setAutoStartClickDelayTicks(next > 25 ? 1 : next);
                        cfg.save();
                        btn.setMessage(Component.literal("Delay: " + cfg.getAutoStartClickDelayTicks() + "t"));
                    }).bounds(col2, y, 108, 18).build());

        return widgets;
    }

    private static SimonSaysConfig.Style nextStyle(SimonSaysConfig.Style style) {
        SimonSaysConfig.Style[] values = SimonSaysConfig.Style.values();
        return values[(style.ordinal() + 1) % values.length];
    }

    private static Component styleText(SimonSaysConfig cfg) {
        return Component.literal("Style: " + switch (cfg.getStyle()) {
            case FILLED -> "Filled";
            case OUTLINE -> "Outline";
            case FILLED_OUTLINE -> "Filled+Outline";
        });
    }

    private static Component rotateText(SimonSaysConfig cfg) {
        return Component.literal(cfg.isAutoSolveRotate() ? "Mode: §bRotate" : "Mode: §bNo Rotate");
    }

    private static Component announceKeyText(SimonSaysConfig cfg) {
        String name = cfg.getAnnounceKeyCode() < 0 ? "Not Set"
                : InputConstants.Type.KEYSYM.getOrCreate(cfg.getAnnounceKeyCode()).getDisplayName().getString();
        return Component.literal("Announce Key: §b" + name);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    @Override
    public boolean isListeningForKey() {
        return capturingAnnounceKey;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        capturingAnnounceKey = false;
        SimonSaysConfig cfg = SimonSaysConfig.getInstance();
        cfg.setAnnounceKeyCode(keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
        cfg.save();
    }
}

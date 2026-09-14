package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.simonsays.SimonSaysConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Simon Says solver + automation settings - see {@link com.killer560.hub.simonsays.SimonSaysFeature}'s
 *  class doc for the real device layout/detection logic this is built on (ported from Odin/QUOI/
 *  NoammAddons, confirmed against this exact Minecraft version). Auto-solve/trigger-bot/auto-start rows
 *  only appear on the cheat build, behind the same red "Cheat Build - Automation" divider every other
 *  automation-heavy tab in this mod uses (see MaskInvincibilityTab) - killer560's own explicit request
 *  (2026-09-14) to keep the actually bannable stuff visually separated from the rest. Column widths are
 *  computed from the real {@code contentWidth} passed in rather than a hardcoded guess - a hardcoded
 *  108px column is what clipped "Announce Progress: ON" / "Announce Key: Not Set" in a real screenshot
 *  killer560 sent, since the real panel is noticeably wider than that. */
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

        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2aX = contentX;
        int col2bX = contentX + col2W + gap;
        int col3W = (contentWidth - gap * 2) / 3;
        int col3aX = contentX;
        int col3bX = contentX + col3W + gap;
        int col3cX = contentX + (col3W + gap) * 2;

        widgets.add(SettingsButtonWidget.builder(onOff("Simon Says", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 28;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Show Highlights", cfg.isSolverEnabled()), btn -> {
                    cfg.setSolverEnabled(!cfg.isSolverEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Show Highlights", cfg.isSolverEnabled()));
                }).bounds(col2aX, y, col2W, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Show Numbers", cfg.isNumberOverlay()), btn -> {
                    cfg.setNumberOverlay(!cfg.isNumberOverlay());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col2bX, y, col2W, 18).build());
        y += 20;

        widgets.add(SettingsButtonWidget.builder(styleText(cfg), btn -> {
                    cfg.setStyle(nextStyle(cfg.getStyle()));
                    cfg.save();
                    btn.setMessage(styleText(cfg));
                }).bounds(col2aX, y, col2W, 18).build());

        if (cfg.isNumberOverlay()) {
            double scaleNorm = (cfg.getNumberScale() - 0.25) / (3.0 - 0.25);
            widgets.add(new ThemedSliderButton(col2bX, y, col2W, 18,
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
                }).bounds(col3aX, y, col3W, 18).build());

        widgets.add(SettingsButtonWidget.builder(Component.literal("2nd Color: ■"), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, "Second Color",
                            cfg.getSecondColor(), SimonSaysConfig.DEFAULT_SECOND_COLOR, argb -> {
                        cfg.setSecondColor(argb);
                        cfg.save();
                    }));
                }).bounds(col3bX, y, col3W, 18).build());

        widgets.add(SettingsButtonWidget.builder(Component.literal("3rd Color: ■"), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, "Third Color+",
                            cfg.getThirdColor(), SimonSaysConfig.DEFAULT_THIRD_COLOR, argb -> {
                        cfg.setThirdColor(argb);
                        cfg.save();
                    }));
                }).bounds(col3cX, y, col3W, 18).build());
        y += 30;

        widgets.add(SettingsButtonWidget.builder(onOff("Prevent Misclicks", cfg.isPreventMisclicksEnabled()), btn -> {
                    cfg.setPreventMisclicksEnabled(!cfg.isPreventMisclicksEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Prevent Misclicks", cfg.isPreventMisclicksEnabled()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 28;

        widgets.add(SettingsButtonWidget.builder(onOff("Announce Progress", cfg.isAnnounceProgress()), btn -> {
                    cfg.setAnnounceProgress(!cfg.isAnnounceProgress());
                    cfg.save();
                    btn.setMessage(onOff("Announce Progress", cfg.isAnnounceProgress()));
                }).bounds(col2aX, y, col2W, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Party Tracker", cfg.isPartyProgressTrackerEnabled()), btn -> {
                    cfg.setPartyProgressTrackerEnabled(!cfg.isPartyProgressTrackerEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Party Tracker", cfg.isPartyProgressTrackerEnabled()));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 22;

        Component keyLabel = capturingAnnounceKey ? Component.literal("Press any key...") : announceKeyText(cfg);
        widgets.add(SettingsButtonWidget.builder(keyLabel, btn -> {
                    capturingAnnounceKey = true;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(col2aX, y, col2W, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Message", cfg.isAutoSendResetMessage()), btn -> {
                    cfg.setAutoSendResetMessage(!cfg.isAutoSendResetMessage());
                    cfg.save();
                    btn.setMessage(onOff("Auto Message", cfg.isAutoSendResetMessage()));
                }).bounds(col2bX, y, col2W, 18).build());
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
        y += 30;

        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }

        // Killer560's own explicit request (2026-09-14): keep the actually bannable automation visually
        // separated from the settings above it - same red divider MaskInvincibilityTab already uses for
        // exactly this reason.
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§c§lCheat Build - Automation"), Minecraft.getInstance().font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Trigger Bot", cfg.isTriggerBotEnabled()), btn -> {
                    cfg.setTriggerBotEnabled(!cfg.isTriggerBotEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Trigger Bot", cfg.isTriggerBotEnabled()));
                }).bounds(col2aX, y, col2W, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Solve", cfg.isAutoSolveEnabled()), btn -> {
                    cfg.setAutoSolveEnabled(!cfg.isAutoSolveEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col2bX, y, col2W, 18).build());
        y += 22;

        if (cfg.isAutoSolveEnabled()) {
            widgets.add(SettingsButtonWidget.builder(rotateText(cfg), btn -> {
                        cfg.setAutoSolveRotate(!cfg.isAutoSolveRotate());
                        cfg.save();
                        btn.setMessage(rotateText(cfg));
                    }).bounds(col2aX, y, col2W, 18).build());

            // Alternative pacing mode (2026-09-14, killer560's own request after seeing real log data
            // show the Target/Variance model below landing at a consistent ~850ms/click that still felt
            // too slow) - a flat, directly controllable delay instead of an overall-duration target.
            widgets.add(SettingsButtonWidget.builder(pacingModeText(cfg), btn -> {
                        cfg.setAutoSolveFixedDelayMode(!cfg.isAutoSolveFixedDelayMode());
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(col2bX, y, col2W, 18).build());
            y += 22;

            if (cfg.isAutoSolveFixedDelayMode()) {
                double fixedDelayNorm = cfg.getAutoSolveFixedDelayMs() / 3000.0;
                widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18,
                        Component.literal("Click Delay: " + cfg.getAutoSolveFixedDelayMs() + "ms"), fixedDelayNorm) {
                    @Override
                    protected void updateMessage() {
                        setMessage(Component.literal("Click Delay: " + cfg.getAutoSolveFixedDelayMs() + "ms"));
                    }

                    @Override
                    protected void applyValue() {
                        cfg.setAutoSolveFixedDelayMs((int) Math.round(this.value * 3000));
                        cfg.save();
                    }
                });
                y += 22;
            } else {
                // Real bug found and fixed (2026-09-14): killer560 reported Auto Solve was "extremely
                // slow" and correctly guessed why - this used to re-arm a fresh Target ± Variance window
                // every time a new ROUND started, applying the full target duration to that round's
                // handful of clicks alone (round 1 has just ONE click, so it waited the full ~12s target
                // just to press it once). Now arms exactly once per full device attempt and paces across
                // the real total of 15 clicks across all 5 rounds, so the target is genuinely the time
                // for the WHOLE solve.
                double targetNorm = (cfg.getClickTimerTargetMs() - 1000.0) / (60_000.0 - 1000.0);
                widgets.add(new ThemedSliderButton(col2aX, y, col2W, 18,
                        Component.literal("Timer Target: " + (cfg.getClickTimerTargetMs() / 100) / 10.0 + "s"), targetNorm) {
                    @Override
                    protected void updateMessage() {
                        setMessage(Component.literal("Timer Target: " + (cfg.getClickTimerTargetMs() / 100) / 10.0 + "s"));
                    }

                    @Override
                    protected void applyValue() {
                        cfg.setClickTimerTargetMs((int) Math.round(1000 + this.value * (60_000 - 1000)));
                        cfg.save();
                    }
                });

                double varianceNorm = cfg.getClickTimerVarianceMs() / 5000.0;
                widgets.add(new ThemedSliderButton(col2bX, y, col2W, 18,
                        Component.literal("Variance: ±" + cfg.getClickTimerVarianceMs() + "ms"), varianceNorm) {
                    @Override
                    protected void updateMessage() {
                        setMessage(Component.literal("Variance: ±" + cfg.getClickTimerVarianceMs() + "ms"));
                    }

                    @Override
                    protected void applyValue() {
                        cfg.setClickTimerVarianceMs((int) Math.round(this.value * 5000));
                        cfg.save();
                    }
                });
                y += 22;
            }
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Start", cfg.isAutoStartEnabled()), btn -> {
                    cfg.setAutoStartEnabled(!cfg.isAutoStartEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 20;

        if (!cfg.isAutoStartEnabled()) {
            return widgets;
        }

        double clicksNorm = cfg.getAutoStartClicks() / 20.0;
        widgets.add(new ThemedSliderButton(col2aX, y, col2W, 18,
                Component.literal("Clicks: " + cfg.getAutoStartClicks()), clicksNorm) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal("Clicks: " + cfg.getAutoStartClicks()));
            }

            @Override
            protected void applyValue() {
                cfg.setAutoStartClicks((int) Math.round(this.value * 20));
                cfg.save();
            }
        });

        // Range is 1-20, not 0-20 (killer560's own call - 0 ticks isn't a real delay option).
        double delayNorm = (cfg.getAutoStartClickDelayTicks() - 1) / 19.0;
        widgets.add(new ThemedSliderButton(col2bX, y, col2W, 18,
                Component.literal("Delay: " + cfg.getAutoStartClickDelayTicks() + "t"), delayNorm) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal("Delay: " + cfg.getAutoStartClickDelayTicks() + "t"));
            }

            @Override
            protected void applyValue() {
                cfg.setAutoStartClickDelayTicks(1 + (int) Math.round(this.value * 19));
                cfg.save();
            }
        });
        y += 22;

        // "Aura" (no-rotate, works anywhere) vs "Look Only" (only clicks using the real crosshair
        // raycast, same as Trigger Bot) - killer560's own request (2026-09-14), partly to test his own
        // theory about why Auto Start "isn't working" against a real skip.
        widgets.add(SettingsButtonWidget.builder(clickModeText(cfg), btn -> {
                    cfg.setAutoStartLookOnlyMode(!cfg.isAutoStartLookOnlyMode());
                    cfg.save();
                    btn.setMessage(clickModeText(cfg));
                }).bounds(contentX, y, contentWidth, 18).build());

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

    private static Component pacingModeText(SimonSaysConfig cfg) {
        return Component.literal(cfg.isAutoSolveFixedDelayMode() ? "Pacing: §bFixed Delay" : "Pacing: §bTarget");
    }

    private static Component clickModeText(SimonSaysConfig cfg) {
        return Component.literal(cfg.isAutoStartLookOnlyMode() ? "Click Mode: §bLook Only" : "Click Mode: §bAura");
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

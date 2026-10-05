package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.simonsays.SimonSaysConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Auto Simon Says - the cheat half of Simon Says (Trigger Bot, Auto Start, Auto Solve with Auto Restart),
 *  moved out of {@link SimonSaysTab} into its own section on 2026-10-04, per killer560: "Move all the cheat
 *  stuff from Simon Says into its own red Simon Says portion." Only added to {@link DungeonTab} on the cheat
 *  build, so {@link FolderTab} draws its header red ({@link #isCheatOnly()}); the legit build has no such
 *  section, and every getter it drives is already false there. Same fields, same JSON keys as before the
 *  move. The rows still need Simon Says itself switched on, as they always did. */
public class AutoSimonSaysTab extends BaseTab implements KeyCaptureTab {

    private boolean capturingRestartKey = false;

    public AutoSimonSaysTab() {
        super("Auto Simon Says");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }
        int y = contentY;
        SimonSaysConfig cfg = SimonSaysConfig.getInstance();
        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2aX = contentX;
        int col2bX = contentX + col2W + gap;

        if (!cfg.isEnabled()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("\u00a77Turn on Simon Says in its own section to use these."),
                    Minecraft.getInstance().font));
            return widgets;
        }

        // Re-laid-out into one category per feature (2026-09-21, killer560's own request): "There should be
        // one red title for triggerbot with only triggerbot in it. Then the next should be for autoss. Also
        // put the auto start in its own category right below the triggerbot." Order below is Trigger Bot ->
        // Auto Start -> Auto Solve; each is cheat-only so each gets its own red divider (SectionHeaders,
        // cheatOnly=true), same convention MaskInvincibilityTab/ObjectHiderTab use for cheat-only content.

        // --- Trigger Bot ---
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Trigger Bot", true), Minecraft.getInstance().font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Trigger Bot", cfg.isTriggerBotEnabled()), btn -> {
                    cfg.setTriggerBotEnabled(!cfg.isTriggerBotEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        if (cfg.isTriggerBotEnabled()) {
            double triggerDelayNorm = cfg.getTriggerBotDelayMs() / (double) SimonSaysConfig.MAX_TRIGGER_BOT_DELAY_MS;
            widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18,
                    Component.literal("Trigger Bot Delay: " + cfg.getTriggerBotDelayMs() + "ms"), triggerDelayNorm) {
                @Override
                protected void updateMessage() {
                    setMessage(Component.literal("Trigger Bot Delay: " + cfg.getTriggerBotDelayMs() + "ms"));
                }

                @Override
                protected void applyValue() {
                    cfg.setTriggerBotDelayMs((int) Math.round(this.value * SimonSaysConfig.MAX_TRIGGER_BOT_DELAY_MS));
                    cfg.save();
                }
            });
            y += 22;
        }

        // --- Auto Start (its own category, directly below Trigger Bot per killer560's request above) ---
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Auto Start", true), Minecraft.getInstance().font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Start", cfg.isAutoStartEnabled()), btn -> {
                    cfg.setAutoStartEnabled(!cfg.isAutoStartEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 20;

        if (cfg.isAutoStartEnabled()) {
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
                    Component.literal("Delay: " + cfg.getAutoStartClickDelayTicks() + "t (" + cfg.getAutoStartClickDelayTicks() * 50 + "ms)"), delayNorm) {
                @Override
                protected void updateMessage() {
                    setMessage(Component.literal("Delay: " + cfg.getAutoStartClickDelayTicks() + "t (" + cfg.getAutoStartClickDelayTicks() * 50 + "ms)"));
                }

                @Override
                protected void applyValue() {
                    cfg.setAutoStartClickDelayTicks(1 + (int) Math.round(this.value * 19));
                    cfg.save();
                }
            });
            // No separate Auto Start "Click Mode" button anymore (2026-09-14, killer560's own call) - it follows
            // Auto Solve's Mode: Rotate = look only, No Rotate = aura. See SimonSaysFeature#tickAutoStart.
            //
            // UNRESOLVED CONFLICT (2026-09-21, killer560): "If i have autoss off but AutoStart on then it
            // should not be the aura mode." Today, with Auto Solve off, SimonSaysFeature#rotateActive is
            // always false regardless of this Mode setting, so Auto Start always clicks aura-style and the
            // camera is never touched - see rotateActive's own doc comment there, which is itself killer560's
            // own explicit fix request from 2026-09-14: "when auto solve is off it shouldnt mess with my
            // crosshair at all. Right now it pulls it towards the middle of the obsidian." Read literally these
            // two requests conflict (Auto Start rotating the camera with Auto Solve off is exactly what the
            // 09-14 request had fixed). Not resolving this here - layout only, per the implementation brief;
            // see the staging notes (impl-simonsaystab.md) for both quotes and the two possible readings.
            y += 22;
        }

        // --- Auto Solve ("autoss") ---
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Auto Solve", true), Minecraft.getInstance().font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Solve", cfg.isAutoSolveEnabled()), btn -> {
                    cfg.setAutoSolveEnabled(!cfg.isAutoSolveEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
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
                int minTarget = SimonSaysConfig.MIN_CLICK_TIMER_TARGET_MS;
                int maxTarget = SimonSaysConfig.MAX_CLICK_TIMER_TARGET_MS;
                double targetNorm = (cfg.getClickTimerTargetMs() - (double) minTarget) / (maxTarget - minTarget);
                widgets.add(new ThemedSliderButton(col2aX, y, col2W, 18,
                        Component.literal("Timer Target: " + (cfg.getClickTimerTargetMs() / 100) / 10.0 + "s"), targetNorm) {
                    @Override
                    protected void updateMessage() {
                        setMessage(Component.literal("Timer Target: " + (cfg.getClickTimerTargetMs() / 100) / 10.0 + "s"));
                    }

                    @Override
                    protected void applyValue() {
                        cfg.setClickTimerTargetMs((int) Math.round(minTarget + this.value * (maxTarget - minTarget)));
                        cfg.save();
                    }
                });

                double varianceNorm = cfg.getClickTimerVarianceMs() / (double) SimonSaysConfig.MAX_CLICK_TIMER_VARIANCE_MS;
                widgets.add(new ThemedSliderButton(col2bX, y, col2W, 18,
                        Component.literal("Variance: ±" + cfg.getClickTimerVarianceMs() + "ms"), varianceNorm) {
                    @Override
                    protected void updateMessage() {
                        setMessage(Component.literal("Variance: ±" + cfg.getClickTimerVarianceMs() + "ms"));
                    }

                    @Override
                    protected void applyValue() {
                        cfg.setClickTimerVarianceMs((int) Math.round(this.value * SimonSaysConfig.MAX_CLICK_TIMER_VARIANCE_MS));
                        cfg.save();
                    }
                });
                y += 22;
            }

            // Auto Restart SS + Restart Key (2026-09-14, killer560's own request) - independent of Auto Start
            // being on; they use Auto Start's Clicks/Delay and the same aura vs look-only rule. Nested under
            // Auto Solve and gated on it (2026-09-21, killer560's own request: "put auto restart ss as a
            // feature that only appears under auto solve. Same with the restart key that should only show if
            // auto restart is on.") - this changes only where/when the ROW draws; the underlying autoRestart
            // trigger (SS-failure detection + the manual Restart Key press) still fires independently of Auto
            // Start's own on/off state, same as before this change.
            widgets.add(SettingsButtonWidget.builder(onOff("Auto Restart SS", cfg.getAutoRestartRaw()), btn -> {
                        cfg.setAutoRestartEnabled(!cfg.getAutoRestartRaw());
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(col2aX, y, col2W, 18).build());

            if (cfg.getAutoRestartRaw()) {
                Component restartKeyLabel = capturingRestartKey ? Component.literal("Press any key...") : restartKeyText(cfg);
                widgets.add(SettingsButtonWidget.builder(restartKeyLabel, btn -> {
                            capturingRestartKey = true;
                            btn.setMessage(Component.literal("Press any key..."));
                        }).bounds(col2bX, y, col2W, 18).build());
            }
            y += 22;
        }

        return widgets;
    }

    private static Component rotateText(SimonSaysConfig cfg) {
        return Component.literal(cfg.isAutoSolveRotate() ? "Mode: \u00a7bRotate" : "Mode: \u00a7bNo Rotate");
    }

    private static Component pacingModeText(SimonSaysConfig cfg) {
        return Component.literal(cfg.isAutoSolveFixedDelayMode() ? "Pacing: \u00a7bFixed Delay" : "Pacing: \u00a7bTarget");
    }

    private static Component restartKeyText(SimonSaysConfig cfg) {
        String name = cfg.getRestartKeyCode() < 0 ? "Not Set"
                : InputConstants.Type.KEYSYM.getOrCreate(cfg.getRestartKeyCode()).getDisplayName().getString();
        return Component.literal("Restart Key: \u00a7b" + name);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "\u00a7aON" : "\u00a7cOFF"));
    }

    @Override
    public boolean isListeningForKey() {
        return capturingRestartKey;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        SimonSaysConfig cfg = SimonSaysConfig.getInstance();
        cfg.setRestartKeyCode(keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
        capturingRestartKey = false;
        cfg.save();
    }
}

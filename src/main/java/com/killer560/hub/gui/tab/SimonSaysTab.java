package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.simonsays.SimonSaysConfig;
import com.killer560.hub.simonsays.SimonSaysFeature;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Simon Says solver + automation settings - see {@link SimonSaysFeature}'s class doc for the real
 *  device layout/detection logic this is built on (ported from Odin/QUOI, confirmed against this exact
 *  Minecraft version). Auto-solve/trigger-bot/auto-start rows only appear on the cheat build - the
 *  legit build can't run them even with a copied config.json (see the config getters' own gating). */
public class SimonSaysTab extends BaseTab implements KeyCaptureTab {

    private boolean capturingResetKey = false;

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

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Solver Display"), Minecraft.getInstance().font));
        y += 14;

        widgets.add(SettingsButtonWidget.builder(onOff("Show Highlights", cfg.isSolverEnabled()), btn -> {
                    cfg.setSolverEnabled(!cfg.isSolverEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Show Highlights", cfg.isSolverEnabled()));
                }).bounds(col1, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(styleText(cfg), btn -> {
                    cfg.setStyle(nextStyle(cfg.getStyle()));
                    cfg.save();
                    btn.setMessage(styleText(cfg));
                }).bounds(col2, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Numbers", cfg.isNumberOverlay()), btn -> {
                    cfg.setNumberOverlay(!cfg.isNumberOverlay());
                    cfg.save();
                    btn.setMessage(onOff("Numbers", cfg.isNumberOverlay()));
                }).bounds(col3, y, 108, 18).build());
        y += 20;

        widgets.add(SettingsButtonWidget.builder(Component.literal("1st Color: ■"), btn -> {
                    cfg.setFirstColor(nextColor(cfg.getFirstColor()));
                    cfg.save();
                }).bounds(col1, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(Component.literal("2nd Color: ■"), btn -> {
                    cfg.setSecondColor(nextColor(cfg.getSecondColor()));
                    cfg.save();
                }).bounds(col2, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(Component.literal("3rd Color: ■"), btn -> {
                    cfg.setThirdColor(nextColor(cfg.getThirdColor()));
                    cfg.save();
                }).bounds(col3, y, 108, 18).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Chat"), Minecraft.getInstance().font));
        y += 14;

        widgets.add(SettingsButtonWidget.builder(onOff("Announce Progress", cfg.isAnnounceProgress()), btn -> {
                    cfg.setAnnounceProgress(!cfg.isAnnounceProgress());
                    cfg.save();
                    btn.setMessage(onOff("Announce Progress", cfg.isAnnounceProgress()));
                }).bounds(col1, y, 160, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Party Tracker", cfg.isPartyProgressTrackerEnabled()), btn -> {
                    cfg.setPartyProgressTrackerEnabled(!cfg.isPartyProgressTrackerEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Party Tracker", cfg.isPartyProgressTrackerEnabled()));
                }).bounds(col3, y, 108, 18).build());
        y += 20;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7\"Announce Progress\" sends real \"SS n/total\" party chat lines that"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Odin/QUOI's own trackers also read - and this mod's tracker reads theirs."),
                Minecraft.getInstance().font));
        y += 22;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Diagnostics"), Minecraft.getInstance().font));
        y += 14;

        // Real bug found and fixed (2026-09-14): this toggle existed in SimonSaysConfig and
        // SimonSaysFeature already checked it, but no tab ever exposed it - there was no way to
        // actually turn it on. Found while investigating a real p3sim.net report where this exact
        // logger was the tool needed to see whether the real block grid even exists there.
        widgets.add(SettingsButtonWidget.builder(onOff("Log Block Changes", cfg.isDiagnosticLoggingEnabled()), btn -> {
                    cfg.setDiagnosticLoggingEnabled(!cfg.isDiagnosticLoggingEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Log Block Changes", cfg.isDiagnosticLoggingEnabled()));
                }).bounds(col1, y, 160, 18).build());
        y += 20;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Logs every block that changes state in a box around you to"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7logs/latest.log, tagged [SimonSays] - real diagnostic data, not a solver."),
                Minecraft.getInstance().font));
        y += 22;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Reset"), Minecraft.getInstance().font));
        y += 14;

        Component keyLabel = capturingResetKey ? Component.literal("Press any key...") : resetKeyText(cfg);
        widgets.add(SettingsButtonWidget.builder(keyLabel, btn -> {
                    capturingResetKey = true;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(col1, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Message", cfg.isAutoSendResetMessage()), btn -> {
                    cfg.setAutoSendResetMessage(!cfg.isAutoSendResetMessage());
                    cfg.save();
                    btn.setMessage(onOff("Auto Message", cfg.isAutoSendResetMessage()));
                }).bounds(col2, y, 100, 18).build());
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
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7Trigger bot / auto-solve / auto-start are cheat-build only."),
                    Minecraft.getInstance().font));
            return widgets;
        }

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§c§lCheat Build - Automation"), Minecraft.getInstance().font));
        y += 14;

        widgets.add(SettingsButtonWidget.builder(onOff("Trigger Bot", cfg.isTriggerBotEnabled()), btn -> {
                    cfg.setTriggerBotEnabled(!cfg.isTriggerBotEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Trigger Bot", cfg.isTriggerBotEnabled()));
                }).bounds(col1, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Solve", cfg.isAutoSolveEnabled()), btn -> {
                    cfg.setAutoSolveEnabled(!cfg.isAutoSolveEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Auto Solve", cfg.isAutoSolveEnabled()));
                }).bounds(col2, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Skip Compat", cfg.isSkipCompatibility()), btn -> {
                    cfg.setSkipCompatibility(!cfg.isSkipCompatibility());
                    cfg.save();
                    btn.setMessage(onOff("Skip Compat", cfg.isSkipCompatibility()));
                }).bounds(col3, y, 108, 18).build());
        y += 20;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Auto Solve/Trigger Bot never rotate your camera (\"no-rotate\", like QUOI)."),
                Minecraft.getInstance().font));
        y += 22;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Auto Start (skip)"), Minecraft.getInstance().font));
        y += 14;

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Start", cfg.isAutoStartEnabled()), btn -> {
                    cfg.setAutoStartEnabled(!cfg.isAutoStartEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Auto Start", cfg.isAutoStartEnabled()));
                    requestRebuild.run();
                }).bounds(col1, y, 100, 18).build());

        if (!cfg.isAutoStartEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(Component.literal("Start Now"), btn ->
                    SimonSaysFeature.beginAutoStart()
                ).bounds(col2, y, 100, 18).build());
        y += 20;

        widgets.add(SettingsButtonWidget.builder(modeText(cfg), btn -> {
                    cfg.setAutoStartMode(nextMode(cfg.getAutoStartMode()));
                    cfg.save();
                    btn.setMessage(modeText(cfg));
                }).bounds(col1, y, 220, 18).build());
        y += 20;

        SimonSaysConfig.SkipMode mode = cfg.getAutoStartMode();
        widgets.add(SettingsButtonWidget.builder(
                    Component.literal("Clicks for this mode: " + cfg.getSkipClicks(mode)), btn -> {
                        int next = cfg.getSkipClicks(mode) + 1;
                        cfg.setSkipClicks(mode, next > 10 ? 1 : next);
                        cfg.save();
                        btn.setMessage(Component.literal("Clicks for this mode: " + cfg.getSkipClicks(mode)));
                    }).bounds(col1, y, 220, 18).build());
        y += 20;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Real click counts per skip mode are unconfirmed - correct these after"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7testing a real skip in a dungeon."),
                Minecraft.getInstance().font));
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

        widgets.add(SettingsButtonWidget.builder(
                    Component.literal("Click Delay: " + cfg.getAutoStartClickDelayMs() + "ms"), btn -> {
                        int next = cfg.getAutoStartClickDelayMs() + 50;
                        cfg.setAutoStartClickDelayMs(next > 1000 ? 50 : next);
                        cfg.save();
                        btn.setMessage(Component.literal("Click Delay: " + cfg.getAutoStartClickDelayMs() + "ms"));
                    }).bounds(col3, y, 108, 18).build());
        y += 20;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Paces clicks to land within Target ± Variance overall, instead of a"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7flat per-click delay."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static SimonSaysConfig.Style nextStyle(SimonSaysConfig.Style style) {
        SimonSaysConfig.Style[] values = SimonSaysConfig.Style.values();
        return values[(style.ordinal() + 1) % values.length];
    }

    private static SimonSaysConfig.SkipMode nextMode(SimonSaysConfig.SkipMode mode) {
        SimonSaysConfig.SkipMode[] values = SimonSaysConfig.SkipMode.values();
        return values[(mode.ordinal() + 1) % values.length];
    }

    // Same "cycle through a fixed palette" convention PosmsgTab already established - this codebase
    // doesn't have a full RGB slider widget yet.
    private static final int[] COLOR_CYCLE = {
            0xFF55FF55, 0xFFFFAA00, 0xFFFF5555, 0xFF55FFFF, 0xFFAA55FF, 0xFFFFFFFF
    };

    private static int nextColor(int current) {
        for (int i = 0; i < COLOR_CYCLE.length; i++) {
            if (COLOR_CYCLE[i] == current) {
                return COLOR_CYCLE[(i + 1) % COLOR_CYCLE.length];
            }
        }
        return COLOR_CYCLE[0];
    }

    private static Component styleText(SimonSaysConfig cfg) {
        return Component.literal("Style: " + switch (cfg.getStyle()) {
            case FILLED -> "Filled";
            case OUTLINE -> "Outline";
            case FILLED_OUTLINE -> "Filled+Outline";
        });
    }

    private static Component modeText(SimonSaysConfig cfg) {
        return Component.literal("Mode: " + cfg.getAutoStartMode().label);
    }

    private static Component resetKeyText(SimonSaysConfig cfg) {
        String name = cfg.getResetKeyCode() < 0 ? "Not Set"
                : InputConstants.Type.KEYSYM.getOrCreate(cfg.getResetKeyCode()).getDisplayName().getString();
        return Component.literal("Reset Key: §b" + name);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    @Override
    public boolean isListeningForKey() {
        return capturingResetKey;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        capturingResetKey = false;
        SimonSaysConfig cfg = SimonSaysConfig.getInstance();
        cfg.setResetKeyCode(keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
        cfg.save();
    }
}

package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.pathfinding.AutoSoulRunner;
import com.killer560.hub.pathfinding.PathfindingConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto Fairy Souls - walks to and collects every unfound Fairy Soul by itself. CHEAT BUILD ONLY (the whole tab sits in
 * the cheat block of its category).
 * <p>
 * killer560, 2026-10-17: "have auto fairy soul toggle itself if it is on as the whole setting then auto walk and auto
 * fairy soul should both be on by default". The tab's switch IS the feature (it needs nothing else switched on - not
 * the Fairy Souls tab either). Auto Walk and Auto Click are parts of that one feature, not features of their own:
 * neither does anything unless a run is going, so they are plain rows under the switch, both ON by default, and the
 * card stands for the feature switch alone (CLAUDE.md's no-"X as a whole" rule is about independent features side by
 * side, which these are not). Turn Speed is gone - the turning draws its own human speeds.
 */
public class AutoFairySoulsTab extends BaseTab implements KeyCaptureTab {

    private boolean capturingResumeKey = false;

    public AutoFairySoulsTab() {
        super("Auto Fairy Souls");
    }

    /** Only added to its category behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
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
        PathfindingConfig cfg = PathfindingConfig.getInstance();
        int y = contentY;
        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2bX = contentX + col2W + gap;

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Fairy Souls", cfg.isAutoSoulsRaw()), btn -> {
                    cfg.setAutoSouls(!cfg.isAutoSoulsRaw());
                    cfg.save();
                    if (!cfg.isAutoSoulsRaw()) {
                        AutoSoulRunner.stop("Auto Fairy Souls turned off", true);
                    }
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;
        if (!cfg.isAutoSoulsRaw()) {
            return widgets;
        }
        widgets.add(SettingsButtonWidget.builder(onOff("Auto Walk", cfg.isAutoWalkRaw()), btn -> {
                    cfg.setAutoWalk(!cfg.isAutoWalkRaw());
                    cfg.save();
                    btn.setMessage(onOff("Auto Walk", cfg.isAutoWalkRaw()));
                }).bounds(contentX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Auto Click", cfg.isAutoCollect()), btn -> {
                    cfg.setAutoCollect(!cfg.isAutoCollect());
                    cfg.save();
                    btn.setMessage(onOff("Auto Click", cfg.isAutoCollect()));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 20;
        widgets.add(SettingsButtonWidget.cycling(autoModeText(cfg), (btn, dir) -> {
                    cfg.setAutoMode(SettingsButtonWidget.step(cfg.getAutoMode(), dir));
                    cfg.save();
                    btn.setMessage(autoModeText(cfg));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 20;
        widgets.add(SettingsButtonWidget.builder(onOff("Sprint", cfg.isAutoSprint()), btn -> {
                    cfg.setAutoSprint(!cfg.isAutoSprint());
                    cfg.save();
                    btn.setMessage(onOff("Sprint", cfg.isAutoSprint()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 20;
        widgets.add(SettingsButtonWidget.builder(capturingResumeKey
                        ? Component.literal("Press any key...") : resumeKeyText(cfg), btn -> {
                    capturingResumeKey = true;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(contentX, y, contentWidth, 18).build());
        // No Start/Stop button (killer560, 2026-10-11): the Start/Stop key and /k560path souls auto start it.
        return widgets;
    }

    private static Component autoModeText(PathfindingConfig cfg) {
        return Component.literal("Auto Mode: §6" + cfg.getAutoMode().label);
    }

    private static Component resumeKeyText(PathfindingConfig cfg) {
        int code = cfg.getResumeKeyCode();
        String name = code < 0 ? "Not Set" : InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName().getString();
        return Component.literal("Start/Stop Key: §6" + name);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    @Override
    public boolean isListeningForKey() {
        return capturingResumeKey;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        if (!capturingResumeKey) {
            return;
        }
        PathfindingConfig cfg = PathfindingConfig.getInstance();
        cfg.setResumeKeyCode(keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
        cfg.save();
        capturingResumeKey = false;
    }
}

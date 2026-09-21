package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.pathfinding.AutoSoulRunner;
import com.killer560.hub.pathfinding.PathfindingConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Auto Fairy Souls - walks to and collects every unfound Fairy Soul by itself. CHEAT BUILD ONLY, split out of
 * the old "Pathfinding" tab 2026-09-21 per killer560: "make auto fairy soul tab that is red with all of that
 * logic" - every setting here drives {@link AutoSoulRunner} / {@link com.killer560.hub.pathfinding.AutoWalker},
 * which is the only thing in this mod that currently auto-walks or auto-clicks anything (confirmed by reading
 * the whole call graph before splitting - nothing else calls {@code AutoWalker.startSession()}), so unlike the
 * old single tab, this whole page is legitimately cheat-only. The legit "Fairy Souls" section (waypoints, route,
 * found-log) stays on {@link PathfindingTab} - see its own "Fairy Souls" section header there.
 */
public class AutoFairySoulsTab extends BaseTab implements KeyCaptureTab {

    private boolean capturingResumeKey = false;

    public AutoFairySoulsTab() {
        super("Auto Fairy Souls");
    }

    /** Only added to {@link NewTab} behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
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
        Minecraft client = Minecraft.getInstance();
        int y = contentY;
        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2bX = contentX + col2W + gap;

        if (!cfg.isFairySoulsRaw()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§cTurn on Fairy Souls in the Pathfinding tab first."), client.font));
            y += 16;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Walk Path", cfg.isAutoWalkRaw()), btn -> {
                    cfg.setAutoWalk(!cfg.isAutoWalkRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Auto Fairy Souls", cfg.isAutoSoulsRaw()), btn -> {
                    cfg.setAutoSouls(!cfg.isAutoSoulsRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col2bX, y, col2W, 18).build());
        y += 20;
        widgets.add(SettingsButtonWidget.builder(autoModeText(cfg), btn -> {
                    PathfindingConfig.AutoMode[] values = PathfindingConfig.AutoMode.values();
                    cfg.setAutoMode(values[(cfg.getAutoMode().ordinal() + 1) % values.length]);
                    cfg.save();
                    btn.setMessage(autoModeText(cfg));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 20;
        widgets.add(SettingsButtonWidget.builder(onOff("Auto Click Souls", cfg.isAutoCollect()), btn -> {
                    cfg.setAutoCollect(!cfg.isAutoCollect());
                    cfg.save();
                    btn.setMessage(onOff("Auto Click Souls", cfg.isAutoCollect()));
                }).bounds(contentX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Sprint", cfg.isAutoSprint()), btn -> {
                    cfg.setAutoSprint(!cfg.isAutoSprint());
                    cfg.save();
                    btn.setMessage(onOff("Sprint", cfg.isAutoSprint()));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 20;
        widgets.add(new ThemedSliderButton(contentX, y, col2W, 18, rotationText(cfg),
                (cfg.getRotationSpeed() - 3.0) / 32.0) {
            @Override
            protected void updateMessage() {
                setMessage(rotationText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setRotationSpeed((float) (3.0 + this.value * 32.0));
                cfg.save();
            }
        });
        widgets.add(SettingsButtonWidget.builder(capturingResumeKey
                        ? Component.literal("Press any key...") : resumeKeyText(cfg), btn -> {
                    capturingResumeKey = true;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 20;
        widgets.add(SettingsButtonWidget.builder(Component.literal(AutoSoulRunner.isActive()
                        ? "§cStop Auto Fairy Souls" : "Start Auto Fairy Souls"), btn -> {
                    if (AutoSoulRunner.isActive()) {
                        AutoSoulRunner.stop("stopped in settings", true);
                    } else {
                        AutoSoulRunner.start();
                    }
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        // Stop-condition list moved into the Start/Stop button's hover tooltip (mod-wide in-panel-paragraph cleanup, 2026-09-21).
        return widgets;
    }

    private static Component rotationText(PathfindingConfig cfg) {
        return Component.literal(String.format(Locale.US, "Turn Speed: §6%.0f°/t", cfg.getRotationSpeed()));
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

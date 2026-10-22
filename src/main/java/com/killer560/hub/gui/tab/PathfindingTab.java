package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.pathfinding.FairySoulsFeature;
import com.killer560.hub.pathfinding.PathfindingConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.killer560.hub.compat.McCompat;

/** Fairy Souls settings - see {@link com.killer560.hub.pathfinding.PathfindingFeature}. The cheat-only walking and
 *  clicking lives in {@link AutoFairySoulsTab}; this tab only ever shows or tracks, never moves the player.
 *  <p>
 *  Layout (killer560, 2026-10-17): the feature switch, then Show Waypoints, Show Path with its Nearest / Whole Island
 *  mode, Dim Path and Path Color, the sliders, then HUD and Start On Island Join. The "Found 1/80" line and the Stop
 *  Navigation button are gone: turning the switch off stops the guide. */
public class PathfindingTab extends BaseTab {

    private boolean confirmResetIsland = false;
    private boolean confirmResetProfile = false;

    public PathfindingTab() {
        super("Fairy Souls");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        PathfindingConfig cfg = PathfindingConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        int y = contentY;
        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2bX = contentX + col2W + gap;

        widgets.add(SettingsButtonWidget.builder(onOff("Fairy Souls", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    if (!cfg.isEnabledRaw()) {
                        FairySoulsFeature.stopGuide(false); // off is the stop: there is no Stop button
                    }
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;
        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Show Waypoints", cfg.isSoulWaypoints()), btn -> {
                    cfg.setSoulWaypoints(!cfg.isSoulWaypoints());
                    cfg.save();
                    btn.setMessage(onOff("Show Waypoints", cfg.isSoulWaypoints()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 20;
        widgets.add(SettingsButtonWidget.builder(onOff("Show Path", cfg.isShowPath()), btn -> {
                    cfg.setShowPath(!cfg.isShowPath());
                    cfg.save();
                    btn.setMessage(onOff("Show Path", cfg.isShowPath()));
                }).bounds(contentX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.cycling(modeText(cfg), (btn, dir) -> {
                    cfg.setSoulMode(SettingsButtonWidget.step(cfg.getSoulMode(), dir));
                    cfg.save();
                    btn.setMessage(modeText(cfg));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 20;
        widgets.add(SettingsButtonWidget.builder(onOff("Dim Path", cfg.isDimPath()), btn -> {
                    cfg.setDimPath(!cfg.isDimPath());
                    cfg.save();
                    btn.setMessage(onOff("Dim Path", cfg.isDimPath()));
                }).bounds(contentX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Path Colour", cfg.getPathColor()), btn ->
                        McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), "Path Colour", cfg.getPathColor(),
                                PathfindingConfig.DEFAULT_PATH_COLOR, argb -> {
                                    cfg.setPathColor(argb);
                                    cfg.save();
                                })))
                .bounds(col2bX, y, col2W, 18).build());
        y += 20;
        widgets.add(new ThemedSliderButton(contentX, y, col2W, 18, lengthText(cfg),
                (cfg.getVisiblePathLength() - 8) / 88.0) {
            @Override
            protected void updateMessage() {
                setMessage(lengthText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setVisiblePathLength((int) Math.round(8 + this.value * 88));
                saveOnRelease(() -> cfg.save());
            }
        });
        widgets.add(new ThemedSliderButton(col2bX, y, col2W, 18, thicknessText(cfg),
                (cfg.getLineThickness() - 1.0) / 7.0) {
            @Override
            protected void updateMessage() {
                setMessage(thicknessText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setLineThickness((float) (1.0 + this.value * 7.0));
                saveOnRelease(() -> cfg.save());
            }
        });
        y += 20;
        widgets.add(new ThemedSliderButton(contentX, y, col2W, 18, recalcText(cfg),
                (cfg.getRecalcDistance() - 3.0) / 22.0) {
            @Override
            protected void updateMessage() {
                setMessage(recalcText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setRecalcDistance(Math.round(3.0 + this.value * 22.0));
                saveOnRelease(() -> cfg.save());
            }
        });
        widgets.add(new ThemedSliderButton(col2bX, y, col2W, 18, textScaleText(cfg), (cfg.getTextScale() - 0.5) / 2.5) {
            @Override
            protected void updateMessage() {
                setMessage(textScaleText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setTextScale((float) (0.5 + this.value * 2.5));
                saveOnRelease(() -> cfg.save());
            }
        });
        y += 20;
        widgets.add(SettingsButtonWidget.builder(onOff("HUD", cfg.isSoulHud()), btn -> {
                    cfg.setSoulHud(!cfg.isSoulHud());
                    cfg.save();
                    btn.setMessage(onOff("HUD", cfg.isSoulHud()));
                }).bounds(contentX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Start On Island Join", cfg.isAutoStartOnIsland()), btn -> {
                    cfg.setAutoStartOnIsland(!cfg.isAutoStartOnIsland());
                    cfg.save();
                    btn.setMessage(onOff("Start On Island Join", cfg.isAutoStartOnIsland()));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 26;

        // ---------------------------------------------------------------- the rest: label, chat, the found-soul log
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("More", false), client.font));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(onOff("Target Label", cfg.isShowTargetLabel()), btn -> {
                    cfg.setShowTargetLabel(!cfg.isShowTargetLabel());
                    cfg.save();
                    btn.setMessage(onOff("Target Label", cfg.isShowTargetLabel()));
                }).bounds(contentX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Target Colour", cfg.getTargetColor()), btn ->
                        McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), "Target Colour", cfg.getTargetColor(),
                                PathfindingConfig.DEFAULT_TARGET_COLOR, argb -> {
                                    cfg.setTargetColor(argb);
                                    cfg.save();
                                })))
                .bounds(col2bX, y, col2W, 18).build());
        y += 20;
        widgets.add(SettingsButtonWidget.builder(onOff("Chat Feedback", cfg.isChatFeedback()), btn -> {
                    cfg.setChatFeedback(!cfg.isChatFeedback());
                    cfg.save();
                    btn.setMessage(onOff("Chat Feedback", cfg.isChatFeedback()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 20;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Mark All Found (This Island)"), btn -> {
                    FairySoulsFeature.markIslandFound();
                    requestRebuild.run();
                }).bounds(contentX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal(confirmResetIsland
                        ? "§cSure? Reset Island" : "§cReset This Island"), btn -> {
                    if (!confirmResetIsland) {
                        confirmResetIsland = true;
                    } else {
                        FairySoulsFeature.resetIsland();
                        confirmResetIsland = false;
                    }
                    requestRebuild.run();
                }).bounds(col2bX, y, col2W, 18).build());
        y += 20;
        widgets.add(SettingsButtonWidget.builder(Component.literal(confirmResetProfile
                        ? "§cSure? Reset Every Island" : "§cReset Found Souls (Profile)"), btn -> {
                    if (!confirmResetProfile) {
                        confirmResetProfile = true;
                    } else {
                        FairySoulsFeature.resetProfile();
                        confirmResetProfile = false;
                    }
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        return widgets;
    }

    private static Component lengthText(PathfindingConfig cfg) {
        return Component.literal("Visible Path: §6" + cfg.getVisiblePathLength() + "m");
    }

    private static Component thicknessText(PathfindingConfig cfg) {
        return Component.literal(String.format(Locale.US, "Line Width: §6%.1f", cfg.getLineThickness()));
    }

    private static Component recalcText(PathfindingConfig cfg) {
        return Component.literal(String.format(Locale.US, "Re-Path After: §6%.0fm", cfg.getRecalcDistance()));
    }

    private static Component textScaleText(PathfindingConfig cfg) {
        return Component.literal(String.format(Locale.US, "Text Scale: §6%.2fx", cfg.getTextScale()));
    }

    private static Component modeText(PathfindingConfig cfg) {
        return Component.literal("Path Mode: §6" + cfg.getSoulMode().label);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

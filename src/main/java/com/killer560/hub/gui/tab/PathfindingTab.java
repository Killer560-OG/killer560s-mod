package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.pathfinding.AutoSoulRunner;
import com.killer560.hub.pathfinding.FairySoulStore;
import com.killer560.hub.pathfinding.FairySoulsFeature;
import com.killer560.hub.pathfinding.IslandDetector;
import com.killer560.hub.pathfinding.NavigationManager;
import com.killer560.hub.pathfinding.PathfindingConfig;
import com.killer560.hub.pathfinding.ProfileTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pathfinding + Fairy Souls settings - see {@link com.killer560.hub.pathfinding.PathfindingFeature}. The
 *  cheat-only "Auto Fairy Souls" walking/clicking logic lives in {@link AutoFairySoulsTab} (split out
 *  2026-09-21, see its own javadoc); this tab only ever shows or tracks, never moves the player. */
public class PathfindingTab extends BaseTab {

    private boolean confirmResetIsland = false;
    private boolean confirmResetProfile = false;

    public PathfindingTab() {
        super("Pathfinding");
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

        widgets.add(SettingsButtonWidget.builder(onOff("Pathfinding", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;
        if (!cfg.isEnabledRaw()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7Shows the fastest route to anywhere on the island, using"),
                    client.font));
            y += 12;
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7SkyHanni's public island graph data (downloaded, not bundled)."),
                    client.font));
            return widgets;
        }

        String island = IslandDetector.islandName();
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal("§7Island: §6"
                + (island.isEmpty() ? "unknown" : island) + "§7   Profile: §6" + ProfileTracker.displayName()),
                client.font));
        y += 16;

        // ---------------------------------------------------------------- display
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Display", false), client.font));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(onOff("Show Path", cfg.isShowPath()), btn -> {
                    cfg.setShowPath(!cfg.isShowPath());
                    cfg.save();
                    btn.setMessage(onOff("Show Path", cfg.isShowPath()));
                }).bounds(contentX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Dim Whole Route", cfg.isShowWholePath()), btn -> {
                    cfg.setShowWholePath(!cfg.isShowWholePath());
                    cfg.save();
                    btn.setMessage(onOff("Dim Whole Route", cfg.isShowWholePath()));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 20;
        widgets.add(SettingsButtonWidget.builder(onOff("Target Label", cfg.isShowTargetLabel()), btn -> {
                    cfg.setShowTargetLabel(!cfg.isShowTargetLabel());
                    cfg.save();
                    btn.setMessage(onOff("Target Label", cfg.isShowTargetLabel()));
                }).bounds(contentX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Chat Feedback", cfg.isChatFeedback()), btn -> {
                    cfg.setChatFeedback(!cfg.isChatFeedback());
                    cfg.save();
                    btn.setMessage(onOff("Chat Feedback", cfg.isChatFeedback()));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 20;
        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Path Color", cfg.getPathColor()), btn ->
                        client.setScreen(new ColorPickerScreen(client.screen, "Path Color", cfg.getPathColor(),
                                PathfindingConfig.DEFAULT_PATH_COLOR, argb -> {
                                    cfg.setPathColor(argb);
                                    cfg.save();
                                })))
                .bounds(contentX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Target Color", cfg.getTargetColor()), btn ->
                        client.setScreen(new ColorPickerScreen(client.screen, "Target Color", cfg.getTargetColor(),
                                PathfindingConfig.DEFAULT_TARGET_COLOR, argb -> {
                                    cfg.setTargetColor(argb);
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
                cfg.save();
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
                cfg.save();
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
                cfg.save();
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
                cfg.save();
            }
        });
        y += 24;

        widgets.add(SettingsButtonWidget.builder(Component.literal(NavigationManager.isActive()
                        ? "§cStop Navigation" : "Nothing To Stop"), btn -> {
                    NavigationManager.stop("stopped in settings", true);
                    FairySoulsFeature.stopGuide(false);
                    AutoSoulRunner.stop("stopped in settings", false);
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 26;

        // ---------------------------------------------------------------- fairy souls
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Fairy Souls", false), client.font));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(onOff("Fairy Souls", cfg.isFairySoulsRaw()), btn -> {
                    cfg.setFairySouls(!cfg.isFairySoulsRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(modeText(cfg), btn -> {
                    PathfindingConfig.SoulMode[] values = PathfindingConfig.SoulMode.values();
                    cfg.setSoulMode(values[(cfg.getSoulMode().ordinal() + 1) % values.length]);
                    cfg.save();
                    btn.setMessage(modeText(cfg));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 20;
        if (cfg.isFairySoulsRaw()) {
            widgets.add(SettingsButtonWidget.builder(onOff("Soul Waypoints", cfg.isSoulWaypoints()), btn -> {
                        cfg.setSoulWaypoints(!cfg.isSoulWaypoints());
                        cfg.save();
                        btn.setMessage(onOff("Soul Waypoints", cfg.isSoulWaypoints()));
                    }).bounds(contentX, y, col2W, 18).build());
            widgets.add(SettingsButtonWidget.builder(onOff("Soul Counter HUD", cfg.isSoulHud()), btn -> {
                        cfg.setSoulHud(!cfg.isSoulHud());
                        cfg.save();
                        btn.setMessage(onOff("Soul Counter HUD", cfg.isSoulHud()));
                    }).bounds(col2bX, y, col2W, 18).build());
            y += 20;
            widgets.add(SettingsButtonWidget.builder(onOff("Start On Island Join", cfg.isAutoStartOnIsland()), btn -> {
                        cfg.setAutoStartOnIsland(!cfg.isAutoStartOnIsland());
                        cfg.save();
                        btn.setMessage(onOff("Start On Island Join", cfg.isAutoStartOnIsland()));
                    }).bounds(contentX, y, col2W, 18).build());
            widgets.add(SettingsButtonWidget.builder(Component.literal("Guide Me Now"), btn -> {
                        FairySoulsFeature.start(cfg.getSoulMode());
                        requestRebuild.run();
                    }).bounds(col2bX, y, col2W, 18).build());
            y += 20;

            int[] counts = FairySoulsFeature.islandCounts();
            FairySoulStore.IslandRecord record = FairySoulsFeature.menuRecord();
            String status = counts == null ? "§7No graph for this island yet."
                    : "§7Found §6" + counts[0] + "/" + counts[1] + "§7 here"
                    + (record == null ? " §8(open Hypixel's Fairy Souls Guide to sync)"
                    : "§8 (menu: " + record.menuFound + "/" + record.menuTotal + ")");
            widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal(status), client.font));
            y += 16;

            widgets.add(SettingsButtonWidget.builder(Component.literal("Mark Island Found"), btn -> {
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
            y += 26;
        }

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
        return Component.literal("Guide: §6" + cfg.getSoulMode().label);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

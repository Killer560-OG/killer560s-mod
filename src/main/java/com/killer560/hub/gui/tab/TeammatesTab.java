package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.teammates.TeammatesConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Teammate Highlight settings - see {@link com.killer560.hub.teammates.TeammatesFeature}. Through Walls is the only
 *  cheat-build setting and lives under its own red header, same as every other cheat-only section. */
public class TeammatesTab extends BaseTab {

    public TeammatesTab() {
        super("Teammate Highlight");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        TeammatesConfig cfg = TeammatesConfig.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int col2X = contentX + colW + gap;
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Teammate Highlight", cfg.getEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.getEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (!cfg.getEnabledRaw()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(styleText(cfg), btn -> {
                    cfg.cycleStyle();
                    cfg.save();
                    btn.setMessage(styleText(cfg));
                }).bounds(contentX, y, colW, 18).build());
        float minWidth = TeammatesConfig.MIN_LINE_WIDTH;
        float maxWidth = TeammatesConfig.MAX_LINE_WIDTH;
        widgets.add(new ThemedSliderButton(col2X, y, colW, 18, lineWidthText(cfg),
                (cfg.getLineWidth() - minWidth) / (maxWidth - minWidth)) {
            @Override
            protected void updateMessage() {
                setMessage(lineWidthText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setLineWidth((float) (minWidth + this.value * (maxWidth - minWidth)));
                cfg.save();
            }
        });
        y += 22;

        double minRange = TeammatesConfig.MIN_RANGE;
        double maxRange = TeammatesConfig.MAX_RANGE;
        widgets.add(new ThemedSliderButton(contentX, y, colW, 18, rangeText(cfg),
                (cfg.getRange() - minRange) / (maxRange - minRange)) {
            @Override
            protected void updateMessage() {
                setMessage(rangeText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setRange(minRange + this.value * (maxRange - minRange));
                cfg.save();
            }
        });
        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Unknown Class Color", cfg.getUnknownColor()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, "Unknown Class Color",
                            cfg.getUnknownColor(), TeammatesConfig.DEFAULT_UNKNOWN_COLOR, argb -> {
                        cfg.setUnknownColor(argb);
                        cfg.save();
                    }));
                }).bounds(col2X, y, colW, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Show Name", cfg.isShowName()), btn -> {
                    cfg.setShowName(!cfg.isShowName());
                    cfg.save();
                    btn.setMessage(onOff("Show Name", cfg.isShowName()));
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Show Distance", cfg.isShowDistance()), btn -> {
                    cfg.setShowDistance(!cfg.isShowDistance());
                    cfg.save();
                    btn.setMessage(onOff("Show Distance", cfg.isShowDistance()));
                }).bounds(col2X, y, colW, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Highlight Self", cfg.isHighlightSelf()), btn -> {
                    cfg.setHighlightSelf(!cfg.isHighlightSelf());
                    cfg.save();
                    btn.setMessage(onOff("Highlight Self", cfg.isHighlightSelf()));
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Skip Dead", cfg.isSkipDead()), btn -> {
                    cfg.setSkipDead(!cfg.isSkipDead());
                    cfg.save();
                    btn.setMessage(onOff("Skip Dead", cfg.isSkipDead()));
                }).bounds(col2X, y, colW, 18).build());
        y += 26;

        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    SectionHeaders.header("Cheat", true), Minecraft.getInstance().font));
            y += 14;
            widgets.add(SettingsButtonWidget.builder(onOff("Through Walls", cfg.getThroughWallsRaw()), btn -> {
                        cfg.setThroughWalls(!cfg.getThroughWallsRaw());
                        cfg.save();
                        btn.setMessage(onOff("Through Walls", cfg.getThroughWallsRaw()));
                    }).bounds(contentX, y, colW, 18).build());
        }
        return widgets;
    }

    private static Component styleText(TeammatesConfig cfg) {
        return Component.literal("Style: §b" + cfg.getStyle().label);
    }

    private static Component lineWidthText(TeammatesConfig cfg) {
        return Component.literal(String.format(Locale.US, "Line Width: %.1f", cfg.getLineWidth()));
    }

    private static Component rangeText(TeammatesConfig cfg) {
        return Component.literal(String.format(Locale.US, "Range: %.0f", cfg.getRange()));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

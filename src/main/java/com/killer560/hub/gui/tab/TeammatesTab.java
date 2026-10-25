package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.teammates.TeammatesConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.killer560.hub.compat.McCompat;

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

        widgets.add(onOffButton("Teammate Highlight", cfg::getEnabledRaw, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, 220, 20));
        y += 26;

        if (!cfg.getEnabledRaw()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.cycling(styleText(cfg), (btn, dir) -> {
                    cfg.cycleStyle(dir);
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
                saveOnRelease(() -> cfg.save());
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
                saveOnRelease(() -> cfg.save());
            }
        });
        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Unknown Class Color", cfg.getUnknownColor()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), "Unknown Class Color",
                            cfg.getUnknownColor(), TeammatesConfig.DEFAULT_UNKNOWN_COLOR, argb -> {
                        cfg.setUnknownColor(argb);
                        cfg.save();
                    }));
                }).bounds(col2X, y, colW, 18).build());
        y += 22;

        widgets.add(onOffButton("Show Name", cfg::isShowName, cfg::setShowName, cfg::save,
                null, contentX, y, colW, 18));
        widgets.add(onOffButton("Show Distance", cfg::isShowDistance, cfg::setShowDistance, cfg::save,
                null, col2X, y, colW, 18));
        y += 22;

        widgets.add(onOffButton("Highlight Self", cfg::isHighlightSelf, cfg::setHighlightSelf, cfg::save,
                null, contentX, y, colW, 18));
        widgets.add(onOffButton("Skip Dead", cfg::isSkipDead, cfg::setSkipDead, cfg::save, null, col2X, y, colW, 18));
        y += 26;

        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Cheat", true));
            y += 14;
            widgets.add(onOffButton("Through Walls", cfg::getThroughWallsRaw, cfg::setThroughWalls, cfg::save,
                    null, contentX, y, colW, 18));
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
}

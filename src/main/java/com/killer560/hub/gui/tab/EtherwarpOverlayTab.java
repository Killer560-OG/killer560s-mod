package com.killer560.hub.gui.tab;

import com.killer560.hub.etherwarpoverlay.EtherwarpOverlayConfig;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Etherwarp Overlay settings - see {@link com.killer560.hub.etherwarpoverlay.EtherwarpOverlayFeature}'s
 *  class doc for the real Odin-ported landing-prediction this is built on, and why the client-side
 *  predicted-execution half of Odin's own feature was deliberately left out. */
public class EtherwarpOverlayTab extends BaseTab {

    public EtherwarpOverlayTab() {
        super("Etherwarp Overlay");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        EtherwarpOverlayConfig cfg = EtherwarpOverlayConfig.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int col2X = contentX + colW + gap;

        widgets.add(SettingsButtonWidget.builder(onOff("Etherwarp Overlay", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Show When Failed", cfg.isShowWhenFailed()), btn -> {
                    cfg.setShowWhenFailed(!cfg.isShowWhenFailed());
                    cfg.save();
                    // The Failed Color button only shows while failed spots are drawn at all.
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Full Block Box", cfg.isFullBlock()), btn -> {
                    cfg.setFullBlock(!cfg.isFullBlock());
                    cfg.save();
                    btn.setMessage(onOff("Full Block Box", cfg.isFullBlock()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        // Style + colours (2026-09-16, killer560's testing feedback: a colour option and a filled option).
        widgets.add(SettingsButtonWidget.builder(styleText(cfg), btn -> {
                    cfg.setStyle(cfg.getStyle().next());
                    cfg.save();
                    btn.setMessage(styleText(cfg));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        widgets.add(colorButton(contentX, y, colW, "Safe Color", cfg.getSafeColor(),
                EtherwarpOverlayConfig.DEFAULT_SAFE_COLOR, cfg::setSafeColor));
        if (cfg.isShowWhenFailed()) {
            widgets.add(colorButton(col2X, y, colW, "Failed Color", cfg.getFailedColor(),
                    EtherwarpOverlayConfig.DEFAULT_FAILED_COLOR, cfg::setFailedColor));
        }
        y += 26;

        // In-panel description moved into the "Etherwarp Overlay" tooltip (mod-wide in-panel-paragraph cleanup, 2026-09-21).
        return widgets;
    }

    private static AbstractWidget colorButton(int x, int y, int width, String name, int current, int defaultColor,
                                              IntConsumer setter) {
        return SettingsButtonWidget.builder(ColorSwatch.label(name, current), btn -> {
            Minecraft client = Minecraft.getInstance();
            client.setScreen(new ColorPickerScreen(client.screen, name, current, defaultColor, argb -> {
                setter.accept(argb);
                EtherwarpOverlayConfig.getInstance().save();
            }));
        }).bounds(x, y, width, 18).build();
    }

    private static Component styleText(EtherwarpOverlayConfig cfg) {
        return Component.literal("Style: §b" + cfg.getStyle().label);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

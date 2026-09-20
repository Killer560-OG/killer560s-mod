package com.killer560.hub.gui.tab;

import com.killer560.hub.doorkeys.DoorKeysConfig;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Door Keys settings - see {@link com.killer560.hub.doorkeys.DoorKeysFeature}'s class doc for the real
 *  noamm-ported highlight this is built on. */
public class DoorKeysTab extends BaseTab {

    public DoorKeysTab() {
        super("Door Keys");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        DoorKeysConfig cfg = DoorKeysConfig.getInstance();
        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2aX = contentX;
        int col2bX = contentX + col2W + gap;

        widgets.add(SettingsButtonWidget.builder(onOff("Door Keys", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Wither Key", cfg.isHighlightWither()), btn -> {
                    cfg.setHighlightWither(!cfg.isHighlightWither());
                    cfg.save();
                    btn.setMessage(onOff("Wither Key", cfg.isHighlightWither()));
                }).bounds(col2aX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Blood Key", cfg.isHighlightBlood()), btn -> {
                    cfg.setHighlightBlood(!cfg.isHighlightBlood());
                    cfg.save();
                    btn.setMessage(onOff("Blood Key", cfg.isHighlightBlood()));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Show Tracer", cfg.isShowTracer()), btn -> {
                    cfg.setShowTracer(!cfg.isShowTracer());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col2aX, y, col2W, 18).build());
        if (cfg.isShowTracer()) {
            float span = DoorKeysConfig.MAX_TRACER_THICKNESS - DoorKeysConfig.MIN_TRACER_THICKNESS;
            widgets.add(new ThemedSliderButton(col2bX, y, col2W, 18, thicknessText(cfg),
                    (cfg.getTracerThickness() - DoorKeysConfig.MIN_TRACER_THICKNESS) / span) {
                @Override
                protected void updateMessage() {
                    setMessage(thicknessText(cfg));
                }

                @Override
                protected void applyValue() {
                    float raw = DoorKeysConfig.MIN_TRACER_THICKNESS + (float) this.value * span;
                    cfg.setTracerThickness(Math.round(raw * 2f) / 2f);
                    cfg.save();
                }
            });
        }
        y += 26;

        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Cheat Build - ESP", true), Minecraft.getInstance().font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("ESP Through Walls", cfg.isThroughWallsRaw()), btn -> {
                    cfg.setThroughWalls(!cfg.isThroughWallsRaw());
                    cfg.save();
                    btn.setMessage(onOff("ESP Through Walls", cfg.isThroughWallsRaw()));
                }).bounds(contentX, y, contentWidth, 18).build());

        return widgets;
    }

    private static Component thicknessText(DoorKeysConfig cfg) {
        return Component.literal(String.format(Locale.US, "Tracer Thickness: %.1f", cfg.getTracerThickness()));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

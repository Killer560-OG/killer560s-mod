package com.killer560.hub.gui.tab;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.gyrooverlay.GyroOverlayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Gyrokinetic Wand Overlay ({@link com.killer560.hub.gyrooverlay.GyroOverlayFeature}): the switch, Style, Color and,
 *  for the outline, Line Width. */
public class GyroOverlayTab extends BaseTab {

    public GyroOverlayTab() {
        super("Gyrokinetic Wand Overlay");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        GyroOverlayConfig cfg = GyroOverlayConfig.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colBX = contentX + colW + gap;
        int y = contentY;

        if (masterClosed(widgets, "Gyrokinetic Wand Overlay", cfg::isEnabledRaw, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, y, contentWidth)) {
            return widgets;
        }
        y += 24;
        widgets.add(SettingsButtonWidget.cycling(styleText(cfg), (btn, dir) -> {
                    cfg.setStyle(SettingsButtonWidget.step(cfg.getStyle(), dir));
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Gyro Color", cfg.getColor()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), "Gyro Color", cfg.getColor(),
                            GyroOverlayConfig.DEFAULT_COLOR, argb -> {
                                cfg.setColor(argb);
                                cfg.save();
                            }));
                }).bounds(colBX, y, colW, 18).build());
        y += 20;
        if (cfg.getStyle() == GyroOverlayConfig.Style.CIRCLE_OUTLINE) {
            widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, widthText(cfg),
                    (cfg.getLineWidth() - 1.0) / 9.0) {
                @Override
                protected void updateMessage() {
                    setMessage(widthText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setLineWidth((float) (1.0 + Math.round(this.value * 18.0) / 2.0));
                    saveOnRelease(cfg::save);
                }
            });
        }
        return widgets;
    }

    private static Component styleText(GyroOverlayConfig cfg) {
        return Component.literal("Style: §6" + cfg.getStyle().label);
    }

    private static Component widthText(GyroOverlayConfig cfg) {
        return Component.literal("Line Width: " + String.format(Locale.ROOT, "%.1f", cfg.getLineWidth()));
    }
}

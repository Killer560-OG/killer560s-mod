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
        y += 22;

        // killer560, 2026-10-15: "have a toggle for sphere or radius" - how the pickup range is drawn.
        widgets.add(SettingsButtonWidget.cycling(rangeText(cfg), (btn, dir) -> {
                    cfg.setRangeStyle(SettingsButtonWidget.step(cfg.getRangeStyle(), dir,
                            DoorKeysConfig.RANGE_STYLE_NAMES.length));
                    cfg.save();
                    btn.setMessage(rangeText(cfg));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 26;

        // killer560, 2026-10-18: the circle's line thickness and height, and colours for the tracer, the circle and the
        // sphere's fill. A colour left on Auto follows the key (near-black Wither Key, red Blood Key); right-click resets.
        boolean sphere = cfg.getRangeStyle() == DoorKeysConfig.RANGE_SPHERE;
        float tSpan = DoorKeysConfig.MAX_CIRCLE_THICKNESS - DoorKeysConfig.MIN_CIRCLE_THICKNESS;
        widgets.add(new ThemedSliderButton(col2aX, y, col2W, 18, circleThicknessText(cfg),
                (cfg.getCircleThickness() - DoorKeysConfig.MIN_CIRCLE_THICKNESS) / tSpan) {
            @Override
            protected void updateMessage() {
                setMessage(circleThicknessText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setCircleThickness(Math.round((DoorKeysConfig.MIN_CIRCLE_THICKNESS + (float) this.value * tSpan) * 2f) / 2f);
                cfg.save();
            }
        });
        if (!sphere) {
            float lSpan = DoorKeysConfig.MAX_CIRCLE_LIFT - DoorKeysConfig.MIN_CIRCLE_LIFT;
            widgets.add(new ThemedSliderButton(col2bX, y, col2W, 18, circleLiftText(cfg),
                    (cfg.getCircleLift() - DoorKeysConfig.MIN_CIRCLE_LIFT) / lSpan) {
                @Override
                protected void updateMessage() {
                    setMessage(circleLiftText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setCircleLift(Math.round((DoorKeysConfig.MIN_CIRCLE_LIFT + (float) this.value * lSpan) * 10f) / 10f);
                    cfg.save();
                }
            });
        }
        y += 22;
        widgets.add(colorButton("Circle Color", cfg::getCircleColor, cfg::setCircleColor, 0xFFCC0D0D, 0xFFCC0D0D, cfg, col2aX, y, col2W));
        widgets.add(colorButton("Tracer Color", cfg::getTracerColor, cfg::setTracerColor, 0xFFCC0D0D, 0xFFCC0D0D, cfg, col2bX, y, col2W));
        y += 22;
        if (sphere) {
            widgets.add(colorButton("Sphere Fill", cfg::getFillColor, cfg::setFillColor, 0x38BFBFCC, 0x38BFBFCC, cfg, contentX, y, contentWidth));
            y += 22;
        }
        y += 4;

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

    /** A colour row: Auto until he picks one (the picker starts from {@code shownWhenAuto}); right-click puts it back to Auto. */
    private static SettingsButtonWidget colorButton(String label, java.util.function.IntSupplier get,
                                                    java.util.function.IntConsumer set, int shownWhenAuto, int pickerDefault,
                                                    DoorKeysConfig cfg, int x, int y, int w) {
        java.util.function.Supplier<Component> text = () -> get.getAsInt() == DoorKeysConfig.COLOR_AUTO
                ? Component.literal(label + ": §7Auto") : com.killer560.hub.gui.ColorSwatch.label(label, get.getAsInt());
        return SettingsButtonWidget.builder(text.get(), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    int current = get.getAsInt() == DoorKeysConfig.COLOR_AUTO ? shownWhenAuto : get.getAsInt();
                    com.killer560.hub.compat.McCompat.setScreen(client, new com.killer560.hub.gui.ColorPickerScreen(
                            com.killer560.hub.compat.McCompat.screen(client), label, current, pickerDefault, argb -> {
                        set.accept(argb);
                        cfg.save();
                    }));
                }).secondaryPress(btn -> {
                    set.accept(DoorKeysConfig.COLOR_AUTO);
                    cfg.save();
                    btn.setMessage(text.get());
                }).bounds(x, y, w, 18).build();
    }

    private static Component circleThicknessText(DoorKeysConfig cfg) {
        return Component.literal(String.format(Locale.US, "Circle Thickness: %.1f", cfg.getCircleThickness()));
    }

    private static Component circleLiftText(DoorKeysConfig cfg) {
        return Component.literal(String.format(Locale.US, "Circle Height: %+.1f", cfg.getCircleLift()));
    }

    private static Component rangeText(DoorKeysConfig cfg) {
        return Component.literal("Pickup Range: " + DoorKeysConfig.RANGE_STYLE_NAMES[cfg.getRangeStyle()]);
    }

    private static Component thicknessText(DoorKeysConfig cfg) {
        return Component.literal(String.format(Locale.US, "Tracer Thickness: %.1f", cfg.getTracerThickness()));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

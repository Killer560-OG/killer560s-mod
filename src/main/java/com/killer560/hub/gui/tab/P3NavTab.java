package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.p3nav.P3NavConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * P3 Nav settings - the F7/M7 Phase 3 gate highlight and terminal/device ESP
 * (see {@link com.killer560.hub.p3nav.P3NavFeature}). Everything here defaults OFF.
 * <p>
 * "Through Walls" only exists on the cheat build, and its section header is the red cheat-only one
 * ({@link SectionHeaders#header(String, boolean)}), same as Dungeon ESP's.
 */
public class P3NavTab extends BaseTab {

    public P3NavTab() {
        super("P3 Nav");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        P3NavConfig cfg = P3NavConfig.getInstance();
        Minecraft mc = Minecraft.getInstance();
        boolean cheat = com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED;
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int col2X = contentX + colW + gap;
        int y = contentY;

        // ---- Gate Highlight ----
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Gate Highlight", false), mc.font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Gate Highlight", cfg.getGateHighlightRaw()), btn -> {
                    cfg.setGateHighlight(!cfg.getGateHighlightRaw());
                    cfg.save();
                    btn.setMessage(onOff("Gate Highlight", cfg.getGateHighlightRaw()));
                }).bounds(contentX, y, colW, 18).build());
        colorButton(widgets, col2X, y, colW, "Gate", cfg::getGateColor, argb -> {
            cfg.setGateColor(argb);
            cfg.save();
        }, P3NavConfig.DEFAULT_GATE_COLOR);
        y += 22;

        widgets.add(SettingsButtonWidget.builder(styleText("Gate Style", cfg.getGateStyle()), btn -> {
                    cfg.cycleGateStyle();
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());
        if (cfg.getGateStyle() != P3NavConfig.Style.FILLED) {
            widthSlider(widgets, col2X, y, colW, "Gate Line Width", cfg.getGateLineWidth(), v -> {
                cfg.setGateLineWidth(v);
                cfg.save();
            }, cfg::getGateLineWidth);
        }
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Hide Once Destroyed", cfg.getGateHideDestroyedRaw()), btn -> {
                    cfg.setGateHideDestroyed(!cfg.getGateHideDestroyedRaw());
                    cfg.save();
                    btn.setMessage(onOff("Hide Once Destroyed", cfg.getGateHideDestroyedRaw()));
                }).bounds(contentX, y, colW, 18).build());
        y += 30;

        // ---- Terminal / Device Highlight (depth-tested; "ESP" was inaccurate AND cheat-sounding) ----
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Terminal / Device Highlight", false), mc.font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Terminal Highlight", cfg.getTerminalEspRaw()), btn -> {
                    cfg.setTerminalEsp(!cfg.getTerminalEspRaw());
                    cfg.save();
                    btn.setMessage(onOff("Terminal Highlight", cfg.getTerminalEspRaw()));
                }).bounds(contentX, y, colW, 18).build());
        colorButton(widgets, col2X, y, colW, "Terminal", cfg::getTerminalColor, argb -> {
            cfg.setTerminalColor(argb);
            cfg.save();
        }, P3NavConfig.DEFAULT_TERMINAL_COLOR);
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Device Highlight", cfg.getDeviceEspRaw()), btn -> {
                    cfg.setDeviceEsp(!cfg.getDeviceEspRaw());
                    cfg.save();
                    btn.setMessage(onOff("Device Highlight", cfg.getDeviceEspRaw()));
                }).bounds(contentX, y, colW, 18).build());
        colorButton(widgets, col2X, y, colW, "Device", cfg::getDeviceColor, argb -> {
            cfg.setDeviceColor(argb);
            cfg.save();
        }, P3NavConfig.DEFAULT_DEVICE_COLOR);
        y += 22;

        widgets.add(SettingsButtonWidget.builder(styleText("Highlight Style", cfg.getTerminalStyle()), btn -> {
                    cfg.cycleTerminalStyle();
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());
        if (cfg.getTerminalStyle() != P3NavConfig.Style.FILLED) {
            widthSlider(widgets, col2X, y, colW, "Highlight Line Width", cfg.getTerminalLineWidth(), v -> {
                cfg.setTerminalLineWidth(v);
                cfg.save();
            }, cfg::getTerminalLineWidth);
        }
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Current Section Only", cfg.getTerminalCurrentSectionOnlyRaw()), btn -> {
                    cfg.setTerminalCurrentSectionOnly(!cfg.getTerminalCurrentSectionOnlyRaw());
                    cfg.save();
                    btn.setMessage(onOff("Current Section Only", cfg.getTerminalCurrentSectionOnlyRaw()));
                }).bounds(contentX, y, colW, 18).build());
        y += 30;

        if (cheat) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    SectionHeaders.header("Through Walls", true), mc.font));
            y += 16;
            widgets.add(SettingsButtonWidget.builder(onOff("Through Walls", cfg.getTerminalThroughWallsRaw()), btn -> {
                        cfg.setTerminalThroughWalls(!cfg.getTerminalThroughWallsRaw());
                        cfg.save();
                        btn.setMessage(onOff("Through Walls", cfg.getTerminalThroughWallsRaw()));
                    }).bounds(contentX, y, colW, 18).build());
        }
        return widgets;
    }

    private static void colorButton(List<AbstractWidget> widgets, int x, int y, int width, String name,
                                    IntSupplier getter, IntConsumer setter, int defaultColor) {
        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Color", getter.getAsInt()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, name + " Color",
                            getter.getAsInt(), defaultColor, setter::accept));
                }).bounds(x, y, width, 18).build());
    }

    private interface FloatSetter {
        void accept(float value);
    }

    private interface FloatGetter {
        float get();
    }

    private static void widthSlider(List<AbstractWidget> widgets, int x, int y, int width, String label,
                                    float current, FloatSetter setter, FloatGetter getter) {
        float min = P3NavConfig.MIN_LINE_WIDTH;
        float max = P3NavConfig.MAX_LINE_WIDTH;
        widgets.add(new ThemedSliderButton(x, y, width, 18, widthText(label, current), (current - min) / (max - min)) {
            @Override
            protected void updateMessage() {
                setMessage(widthText(label, getter.get()));
            }

            @Override
            protected void applyValue() {
                setter.accept((float) (min + this.value * (max - min)));
            }
        });
    }

    private static Component widthText(String label, float value) {
        return Component.literal(String.format(Locale.US, "%s: %.1f", label, value));
    }

    private static Component styleText(String label, P3NavConfig.Style style) {
        return Component.literal(label + ": §b" + style.label);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

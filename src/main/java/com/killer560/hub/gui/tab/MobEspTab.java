package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.mobesp.MobEspConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Dungeon ESP settings - see {@link com.killer560.hub.mobesp.MobEspFeature}. Three target rows (toggle + colour), then
 *  render options. Wither Bosses and Through Walls only exist on the cheat build. */
public class MobEspTab extends BaseTab {

    public MobEspTab() {
        super("Dungeon ESP");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        MobEspConfig cfg = MobEspConfig.getInstance();
        boolean cheat = com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED;
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int col2X = contentX + colW + gap;
        int y = contentY;

        targetRow(widgets, contentX, col2X, y, colW, "Starred Mobs", cfg::getStarredMobsRaw, cfg::setStarredMobs,
                "Starred Mob", cfg::getStarredColor, cfg::setStarredColor, MobEspConfig.DEFAULT_STARRED_COLOR);
        y += 22;
        targetRow(widgets, contentX, col2X, y, colW, "Bats", cfg::getBatsRaw, cfg::setBats,
                "Bat", cfg::getBatColor, cfg::setBatColor, MobEspConfig.DEFAULT_BAT_COLOR);
        y += 22;
        if (cheat) {
            targetRow(widgets, contentX, col2X, y, colW, "Wither Bosses", cfg::getWithersRaw, cfg::setWithers,
                    "Wither", cfg::getWitherColor, cfg::setWitherColor, MobEspConfig.DEFAULT_WITHER_COLOR);
            y += 22;
        }
        y += 8;

        widgets.add(SettingsButtonWidget.builder(styleText(cfg), btn -> {
                    cfg.cycleStyle();
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());
        if (cfg.getStyle() != MobEspConfig.Style.GLOW) {
            float min = MobEspConfig.MIN_LINE_WIDTH;
            float max = MobEspConfig.MAX_LINE_WIDTH;
            widgets.add(new ThemedSliderButton(col2X, y, colW, 18, lineWidthText(cfg), (cfg.getLineWidth() - min) / (max - min)) {
                @Override
                protected void updateMessage() {
                    setMessage(lineWidthText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setLineWidth((float) (min + this.value * (max - min)));
                    cfg.save();
                }
            });
        }
        y += 22;

        double minRange = MobEspConfig.MIN_RANGE;
        double maxRange = MobEspConfig.MAX_RANGE;
        widgets.add(new ThemedSliderButton(contentX, y, colW, 18, rangeText(cfg), (cfg.getRange() - minRange) / (maxRange - minRange)) {
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
        if (cheat) {
            widgets.add(SettingsButtonWidget.builder(onOff("Through Walls", cfg.getThroughWallsRaw()), btn -> {
                        cfg.setThroughWalls(!cfg.getThroughWallsRaw());
                        cfg.save();
                        btn.setMessage(onOff("Through Walls", cfg.getThroughWallsRaw()));
                    }).bounds(col2X, y, colW, 18).build());
        }
        return widgets;
    }

    private static void targetRow(List<AbstractWidget> widgets, int x, int col2X, int y, int width, String name,
                                  BooleanSupplier getter, Consumer<Boolean> setter, String colorName,
                                  IntSupplier colorGetter, IntConsumer colorSetter, int defaultColor) {
        widgets.add(SettingsButtonWidget.builder(onOff(name, getter.getAsBoolean()), btn -> {
                    setter.accept(!getter.getAsBoolean());
                    MobEspConfig.getInstance().save();
                    btn.setMessage(onOff(name, getter.getAsBoolean()));
                }).bounds(x, y, width, 18).build());
        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Color", colorGetter.getAsInt()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, colorName + " Color",
                            colorGetter.getAsInt(), defaultColor, argb -> {
                        colorSetter.accept(argb);
                        MobEspConfig.getInstance().save();
                    }));
                }).bounds(col2X, y, width, 18).build());
    }

    private static Component styleText(MobEspConfig cfg) {
        return Component.literal("Style: §b" + cfg.getStyle().label);
    }

    private static Component lineWidthText(MobEspConfig cfg) {
        return Component.literal(String.format(Locale.US, "Line Width: %.1f", cfg.getLineWidth()));
    }

    private static Component rangeText(MobEspConfig cfg) {
        return Component.literal(String.format(Locale.US, "Range: %.0f", cfg.getRange()));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

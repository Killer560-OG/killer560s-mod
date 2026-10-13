package com.killer560.hub.gui.tab;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.trail.TrailConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Settings for the cosmetic movement trail (Style: the original Squares, or the 2026-10-13 Ribbon) - killer560's item 8.4: "one square per tick, 1-100, orange
 *  while grounded, blue while airborne, nothing while standing still." See
 *  {@link com.killer560.hub.trail.TrailFeature} for the actual tick/render logic. Master ships OFF;
 *  every change saves immediately. Purely visual with no gameplay effect, so no cheat-only (red)
 *  headers here. */
public class TrailTab extends BaseTab {

    public TrailTab() {
        super("Trail");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        TrailConfig cfg = TrailConfig.getInstance();
        int[] y = {contentY};
        // A supporter's shared trail follows these settings (Player Cosmetics' Share Trail); a no-op otherwise.
        com.killer560.hub.supporters.SupportersAutoShare.shareCurrent(null);

        header(w, contentX, y, contentWidth, "Trail");
        w.add(SettingsButtonWidget.builder(onOff("Trail", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y[0], contentWidth, 20).build());
        y[0] += 24;

        if (!cfg.isEnabledRaw()) {
            return w;
        }

        header(w, contentX, y, contentWidth, "Appearance");
        w.add(SettingsButtonWidget.cycling(styleLabel(cfg), (btn, dir) -> {
                    cfg.setStyle(SettingsButtonWidget.step(cfg.getStyle(), dir));
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y[0], contentWidth, 18).build());
        y[0] += 22;

        if (cfg.getStyle() == TrailConfig.Style.RIBBON) {
            ribbonWidgets(w, contentX, y, contentWidth, cfg, requestRebuild);
            return w;
        }

        w.add(new ThemedSliderButton(contentX, y[0], contentWidth, 18, lengthLabel(cfg),
                (cfg.getLength() - TrailConfig.MIN_LENGTH)
                        / (double) (TrailConfig.MAX_LENGTH - TrailConfig.MIN_LENGTH)) {
            @Override
            protected void updateMessage() {
                setMessage(lengthLabel(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setLength(TrailConfig.MIN_LENGTH
                        + (int) Math.round(this.value * (TrailConfig.MAX_LENGTH - TrailConfig.MIN_LENGTH)));
                cfg.save();
            }
        });
        y[0] += 22;

        w.add(new ThemedSliderButton(contentX, y[0], contentWidth, 18, sizeLabel(cfg),
                (cfg.getSquareSize() - TrailConfig.MIN_SQUARE_SIZE)
                        / (TrailConfig.MAX_SQUARE_SIZE - TrailConfig.MIN_SQUARE_SIZE)) {
            @Override
            protected void updateMessage() {
                setMessage(sizeLabel(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setSquareSize((float) (TrailConfig.MIN_SQUARE_SIZE
                        + this.value * (TrailConfig.MAX_SQUARE_SIZE - TrailConfig.MIN_SQUARE_SIZE)));
                cfg.save();
            }
        });
        y[0] += 22;

        w.add(new ThemedSliderButton(contentX, y[0], contentWidth, 18, opacityLabel(cfg),
                (cfg.getOpacityPercent() - TrailConfig.MIN_OPACITY_PERCENT)
                        / (double) (TrailConfig.MAX_OPACITY_PERCENT - TrailConfig.MIN_OPACITY_PERCENT)) {
            @Override
            protected void updateMessage() {
                setMessage(opacityLabel(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setOpacityPercent(TrailConfig.MIN_OPACITY_PERCENT + (int) Math.round(
                        this.value * (TrailConfig.MAX_OPACITY_PERCENT - TrailConfig.MIN_OPACITY_PERCENT)));
                cfg.save();
            }
        });
        y[0] += 22;

        toggle(w, contentX, y[0], contentWidth, "Fade Toward Tail", cfg::isFadeOut, v -> {
            cfg.setFadeOut(v);
            cfg.save();
        });
        y[0] += 22;
        firstPerson(w, contentX, y, contentWidth, cfg);

        return w;
    }

    private static void ribbonWidgets(List<AbstractWidget> w, int x, int[] y, int width, TrailConfig cfg,
                                      Runnable requestRebuild) {
        w.add(new ThemedSliderButton(x, y[0], width, 18, ribbonWidthLabel(cfg),
                (cfg.getRibbonWidth() - TrailConfig.MIN_RIBBON_WIDTH)
                        / (TrailConfig.MAX_RIBBON_WIDTH - TrailConfig.MIN_RIBBON_WIDTH)) {
            @Override
            protected void updateMessage() {
                setMessage(ribbonWidthLabel(cfg));
            }

            @Override
            protected void applyValue() {
                float v = (float) (TrailConfig.MIN_RIBBON_WIDTH
                        + this.value * (TrailConfig.MAX_RIBBON_WIDTH - TrailConfig.MIN_RIBBON_WIDTH));
                cfg.setRibbonWidth(Math.round(v * 20f) / 20f);
                cfg.save();
            }
        });
        y[0] += 22;

        w.add(new ThemedSliderButton(x, y[0], width, 18, ribbonLengthLabel(cfg),
                (cfg.getRibbonSeconds() - TrailConfig.MIN_RIBBON_SECONDS)
                        / (TrailConfig.MAX_RIBBON_SECONDS - TrailConfig.MIN_RIBBON_SECONDS)) {
            @Override
            protected void updateMessage() {
                setMessage(ribbonLengthLabel(cfg));
            }

            @Override
            protected void applyValue() {
                float v = (float) (TrailConfig.MIN_RIBBON_SECONDS
                        + this.value * (TrailConfig.MAX_RIBBON_SECONDS - TrailConfig.MIN_RIBBON_SECONDS));
                cfg.setRibbonSeconds(Math.round(v * 10f) / 10f);
                cfg.save();
            }
        });
        y[0] += 22;

        w.add(new ThemedSliderButton(x, y[0], width, 18, opacityLabel(cfg),
                (cfg.getOpacityPercent() - TrailConfig.MIN_OPACITY_PERCENT)
                        / (double) (TrailConfig.MAX_OPACITY_PERCENT - TrailConfig.MIN_OPACITY_PERCENT)) {
            @Override
            protected void updateMessage() {
                setMessage(opacityLabel(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setOpacityPercent(TrailConfig.MIN_OPACITY_PERCENT + (int) Math.round(
                        this.value * (TrailConfig.MAX_OPACITY_PERCENT - TrailConfig.MIN_OPACITY_PERCENT)));
                cfg.save();
            }
        });
        y[0] += 22;

        header(w, x, y, width, "Ribbon Colors");
        w.add(SettingsButtonWidget.cycling(colorModeLabel(cfg), (btn, dir) -> {
                    cfg.setColorMode(SettingsButtonWidget.step(cfg.getColorMode(), dir));
                    cfg.save();
                    requestRebuild.run();
                }).bounds(x, y[0], width, 18).build());
        y[0] += 22;

        if (cfg.getColorMode() == TrailConfig.ColorMode.GRADIENT) {
            w.add(SettingsButtonWidget.cycling(colorCountLabel(cfg), (btn, dir) -> {
                        cfg.setColorCount(cfg.getColorCount() == 2 ? 3 : 2);
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(x, y[0], width, 18).build());
            y[0] += 22;
            w.add(colorButton(x, y[0], width, "Head Color", cfg.getHeadColor(), TrailConfig.DEFAULT_HEAD_COLOR,
                    cfg::setHeadColor));
            y[0] += 22;
            if (cfg.getColorCount() >= 3) {
                w.add(colorButton(x, y[0], width, "Middle Color", cfg.getMidColor(), TrailConfig.DEFAULT_MID_COLOR,
                        cfg::setMidColor));
                y[0] += 22;
            }
            w.add(colorButton(x, y[0], width, "Tail Color", cfg.getTailColor(), TrailConfig.DEFAULT_TAIL_COLOR,
                    cfg::setTailColor));
            y[0] += 22;
        }

        firstPerson(w, x, y, width, cfg);
    }

    private static void firstPerson(List<AbstractWidget> w, int x, int[] y, int width, TrailConfig cfg) {
        toggle(w, x, y[0], width, "Show In First Person", cfg::isShowInFirstPerson, v -> {
            cfg.setShowInFirstPerson(v);
            cfg.save();
        });
        y[0] += 22;
    }

    private static AbstractWidget colorButton(int x, int y, int width, String name, int current, int defaultColor,
                                              IntConsumer setter) {
        return SettingsButtonWidget.builder(ColorSwatch.label(name, current), btn -> {
            Minecraft client = Minecraft.getInstance();
            McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), name, current, defaultColor,
                    argb -> {
                        setter.accept(argb);
                        TrailConfig.getInstance().save();
                    }));
        }).bounds(x, y, width, 18).build();
    }

    private static Component styleLabel(TrailConfig cfg) {
        return Component.literal("Style: " + cfg.getStyle().label());
    }

    private static Component ribbonWidthLabel(TrailConfig cfg) {
        return Component.literal(String.format(Locale.US, "Ribbon Width: %.2f", cfg.getRibbonWidth()));
    }

    private static Component ribbonLengthLabel(TrailConfig cfg) {
        return Component.literal(String.format(Locale.US, "Ribbon Length: %.1fs", cfg.getRibbonSeconds()));
    }

    private static Component colorModeLabel(TrailConfig cfg) {
        return Component.literal("Color Mode: " + cfg.getColorMode().label());
    }

    private static Component colorCountLabel(TrailConfig cfg) {
        return Component.literal("Colors: " + cfg.getColorCount());
    }

    private static Component lengthLabel(TrailConfig cfg) {
        return Component.literal("Length: " + cfg.getLength());
    }

    private static Component sizeLabel(TrailConfig cfg) {
        return Component.literal(String.format(Locale.US, "Square Size: %.2f", cfg.getSquareSize()));
    }

    private static Component opacityLabel(TrailConfig cfg) {
        return Component.literal("Opacity: " + cfg.getOpacityPercent() + "%");
    }

    private static void header(List<AbstractWidget> w, int x, int[] y, int width, String title) {
        y[0] += 4;
        w.add(new StringWidget(x, y[0], width, 12, SectionHeaders.header(title, false), Minecraft.getInstance().font));
        y[0] += 14;
    }

    private static void toggle(List<AbstractWidget> w, int x, int y, int width, String label,
                                Supplier<Boolean> get, Consumer<Boolean> set) {
        w.add(SettingsButtonWidget.builder(onOff(label, get.get()), btn -> {
                    boolean now = !get.get();
                    set.accept(now);
                    btn.setMessage(onOff(label, now));
                }).bounds(x, y, width, 18).build());
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

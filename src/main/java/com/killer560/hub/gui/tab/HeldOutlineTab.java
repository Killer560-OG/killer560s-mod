package com.killer560.hub.gui.tab;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.heldoutline.HeldOutlineConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Settings for the Held Item Outline (see {@link com.killer560.hub.heldoutline.HeldOutlineRenderer}). Purely visual,
 *  legit, ships OFF; every change saves immediately. */
public class HeldOutlineTab extends BaseTab {

    public HeldOutlineTab() {
        super("Held Item Outline");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        HeldOutlineConfig cfg = HeldOutlineConfig.getInstance();
        int[] y = {contentY};

        header(w, contentX, y, contentWidth, "Held Item Outline");
        w.add(onOffButton("Held Item Outline", cfg::isEnabledRaw, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y[0], contentWidth, 20));
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

        HeldOutlineConfig.Style style = cfg.getStyle();
        if (style.outline) {
            w.add(SettingsButtonWidget.cycling(Component.literal("Outline Side: " + cfg.getSide().label), (btn, dir) -> {
                        cfg.setSide(SettingsButtonWidget.step(cfg.getSide(), dir));
                        cfg.save();
                        btn.setMessage(Component.literal("Outline Side: " + cfg.getSide().label));
                    }).bounds(contentX, y[0], contentWidth, 18).build());
            y[0] += 22;
            color(w, contentX, y[0], contentWidth, "Outline Color", cfg::getOutlineColor, cfg::setOutlineColor,
                    HeldOutlineConfig.DEFAULT_OUTLINE_COLOR, cfg);
            y[0] += 22;
            w.add(new ThemedSliderButton(contentX, y[0], contentWidth, 18, widthLabel(cfg),
                    (cfg.getWidth() - HeldOutlineConfig.MIN_WIDTH)
                            / (HeldOutlineConfig.MAX_WIDTH - HeldOutlineConfig.MIN_WIDTH)) {
                @Override
                protected void updateMessage() {
                    setMessage(widthLabel(cfg));
                }

                @Override
                protected void applyValue() {
                    float raw = (float) (HeldOutlineConfig.MIN_WIDTH
                            + this.value * (HeldOutlineConfig.MAX_WIDTH - HeldOutlineConfig.MIN_WIDTH));
                    float before = cfg.getWidth();
                    cfg.setWidth(Math.round(raw * 2f) / 2f);
                    if (cfg.getWidth() != before) {
                        saveOnRelease(() -> cfg.save()); // a drag repeats the same half-pixel step many times
                    }
                }
            });
            y[0] += 22;
        }
        if (style.fill) {
            color(w, contentX, y[0], contentWidth, "Fill Color", cfg::getFillColor, cfg::setFillColor,
                    HeldOutlineConfig.DEFAULT_FILL_COLOR, cfg);
            y[0] += 22;
        }

        header(w, contentX, y, contentWidth, "Hands");
        toggle(w, contentX, y[0], contentWidth, "Main Hand", cfg.isMainHand(), v -> {
            cfg.setMainHand(v);
            cfg.save();
        });
        y[0] += 20;
        toggle(w, contentX, y[0], contentWidth, "Off Hand", cfg.isOffHand(), v -> {
            cfg.setOffHand(v);
            cfg.save();
        });
        y[0] += 20;
        return w;
    }

    private static Component styleLabel(HeldOutlineConfig cfg) {
        return Component.literal("Style: " + cfg.getStyle().label);
    }

    private static Component widthLabel(HeldOutlineConfig cfg) {
        return Component.literal(String.format(Locale.US, "Outline Width: %.1f px", cfg.getWidth()));
    }

    private static void color(List<AbstractWidget> w, int x, int y, int width, String label, IntSupplier get,
                              IntConsumer set, int def, HeldOutlineConfig cfg) {
        w.add(SettingsButtonWidget.builder(ColorSwatch.label(label, get.getAsInt()), btn -> {
            Minecraft client = Minecraft.getInstance();
            McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), label, get.getAsInt(), def,
                    argb -> {
                        set.accept(argb);
                        cfg.save();
                    }));
        }).bounds(x, y, width, 18).build());
    }

    private static void header(List<AbstractWidget> w, int x, int[] y, int width, String title) {
        y[0] += 4;
        w.add(SectionHeaders.row(x, y[0], width, title, false));
        y[0] += 14;
    }

    private static void toggle(List<AbstractWidget> w, int x, int y, int width, String label, boolean initial,
                               java.util.function.Consumer<Boolean> set) {
        boolean[] state = {initial};
        w.add(SettingsButtonWidget.builder(onOff(label, initial), btn -> {
            state[0] = !state[0];
            set.accept(state[0]);
            btn.setMessage(onOff(label, state[0]));
        }).bounds(x, y, width, 18).build());
    }
}

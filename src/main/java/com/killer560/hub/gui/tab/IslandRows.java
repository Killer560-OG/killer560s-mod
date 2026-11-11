package com.killer560.hub.gui.tab;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The rows every Kuudra, Dojo and Other Islands tab is built from (staging-islands, 2026-10-25), so the tabs stay one
 * shape: a 20-high switch first, then 18-high toggles, color buttons, sliders, buttons, headers and gray notes, each
 * row's setter followed by {@code save}. Returns the next row's y.
 */
final class IslandRows {

    /** The note every cheat-only island tab shows under its switch (and its tooltips repeat). */
    static final String AUTOMATION_NOTE = "Automation: can get you banned; keep it off on the main server";

    private IslandRows() {
    }

    static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    /** The tab's on/off switch ({@link BaseTab#onOffButton}), the first row; rebuilds the tab so its other rows show or hide. */
    static int master(List<AbstractWidget> w, int x, int y, int width, String label, Supplier<Boolean> get,
                      Consumer<Boolean> set, Runnable save, Runnable rebuild) {
        w.add(BaseTab.onOffButton(label, get::get, set, save, rebuild, x, y, width, 20));
        return y + 24;
    }

    static int toggle(List<AbstractWidget> w, int x, int y, int width, String label, Supplier<Boolean> get,
                      Consumer<Boolean> set, Runnable save) {
        w.add(SettingsButtonWidget.builder(onOff(label, get.get()), btn -> {
            set.accept(!get.get());
            save.run();
            btn.setMessage(onOff(label, get.get()));
        }).bounds(x, y, width, 18).build());
        return y + 22;
    }

    static int color(List<AbstractWidget> w, int x, int y, int width, String label, IntSupplier get, IntConsumer set,
                     int defaultArgb, Runnable save) {
        Minecraft mc = Minecraft.getInstance();
        w.add(SettingsButtonWidget.builder(ColorSwatch.label(label, get.getAsInt()), btn ->
                McCompat.setScreen(mc, new ColorPickerScreen(McCompat.screen(mc), label, get.getAsInt(), defaultArgb,
                        argb -> {
                            set.accept(argb);
                            save.run();
                        }))).bounds(x, y, width, 18).build());
        return y + 22;
    }

    static int button(List<AbstractWidget> w, int x, int y, int width, String label, Runnable action) {
        w.add(SettingsButtonWidget.builder(Component.literal(label), btn -> action.run())
                .bounds(x, y, width, 18).build());
        return y + 22;
    }

    /** An integer slider from {@code min} to {@code max} in {@code step}s; {@code label} renders the current value. */
    static int slider(List<AbstractWidget> w, int x, int y, int width, int min, int max, int step, IntSupplier get,
                      IntConsumer set, IntFunction<String> label, Runnable save) {
        int steps = Math.max(1, (max - min) / step);
        w.add(new ThemedSliderButton(x, y, width, 18, Component.literal(label.apply(get.getAsInt())),
                (get.getAsInt() - min) / (double) (max - min)) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal(label.apply(get.getAsInt())));
            }

            @Override
            protected void applyValue() {
                set.accept(min + (int) Math.round(this.value * steps) * step);
                saveOnRelease(save);
            }
        });
        return y + 22;
    }

    static int header(List<AbstractWidget> w, int x, int y, int width, String title) {
        w.add(new StringWidget(x, y, width, 12, SectionHeaders.header(title, false), Minecraft.getInstance().font));
        return y + 14;
    }

    static int note(List<AbstractWidget> w, int x, int y, int width, String text) {
        w.add(new StringWidget(x, y, width, 12, Component.literal("§7" + text), Minecraft.getInstance().font));
        return y + 14;
    }

    /** The red note a cheat-only tab shows under its switch. */
    static int automationNote(List<AbstractWidget> w, int x, int y, int width) {
        w.add(new StringWidget(x, y, width, 12, Component.literal("§c" + AUTOMATION_NOTE), Minecraft.getInstance().font));
        return y + 14;
    }
}

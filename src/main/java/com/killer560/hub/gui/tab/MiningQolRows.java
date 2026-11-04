package com.killer560.hub.gui.tab;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** The rows the pre-2.0 Mining tabs are built from (toggle, cycling, color, header), saved at once through {@code save}. */
final class MiningQolRows {

    static final int ROW = 20;
    static final int STEP = 24;

    private MiningQolRows() {
    }

    static void toggle(List<AbstractWidget> w, int x, int y, int width, String label, BooleanSupplier get,
                       Consumer<Boolean> set, Runnable save) {
        w.add(SettingsButtonWidget.builder(BaseTab.onOff(label, get.getAsBoolean()), btn -> {
            boolean now = !get.getAsBoolean();
            set.accept(now);
            save.run();
            btn.setMessage(BaseTab.onOff(label, now));
        }).bounds(x, y, width, ROW).build());
    }

    /** One toggle of a {@link #toggles} run. */
    record T(String label, BooleanSupplier get, Consumer<Boolean> set) {
    }

    static T t(String label, BooleanSupplier get, Consumer<Boolean> set) {
        return new T(label, get, set);
    }

    /** A column of toggles, one {@link #STEP} apart; returns the y below the last one's step. */
    static int toggles(List<AbstractWidget> w, int x, int y, int width, Runnable save, T... rows) {
        for (T row : rows) {
            toggle(w, x, y, width, row.label(), row.get(), row.set(), save);
            y += STEP;
        }
        return y;
    }

    static void cycle(List<AbstractWidget> w, int x, int y, int width, String label, Supplier<String> value,
                      IntConsumer step, Runnable save) {
        w.add(SettingsButtonWidget.cycling(Component.literal(label + ": " + value.get()), (btn, dir) -> {
            step.accept(dir);
            save.run();
            btn.setMessage(Component.literal(label + ": " + value.get()));
        }).bounds(x, y, width, ROW).build());
    }

    static void color(List<AbstractWidget> w, int x, int y, int width, String label, IntSupplier get, IntConsumer set,
                      int def, Runnable save) {
        w.add(SettingsButtonWidget.builder(ColorSwatch.label(label, get.getAsInt()), btn -> {
            Minecraft client = Minecraft.getInstance();
            McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), label, get.getAsInt(), def,
                    argb -> {
                        set.accept(argb);
                        save.run();
                    }));
        }).bounds(x, y, width, ROW).build());
    }

    static void cheatHeader(List<AbstractWidget> w, int x, int y, int width) {
        w.add(new StringWidget(x, y, width, 12, SectionHeaders.header("Cheat", true), Minecraft.getInstance().font));
    }
}

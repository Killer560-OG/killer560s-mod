package com.killer560.hub.gui.tab;

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

/**
 * The rows every Mining tab is built from (staging-mining): the same switch, toggle, cycling, color and header shapes
 * {@link LagDisplayTab} and the other feature tabs build by hand, in one place so the Mining tabs cannot drift apart.
 * Every change saves at once through the {@code save} the caller passes.
 */
final class MiningRows {

    /** Shown in red at the top of every automation tab, and in its switch's tooltip. */
    static final String AUTOMATION_WARNING = "Automation: can get you banned; keep it off on the main server";

    private MiningRows() {
    }

    static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    /** The tab's on/off switch, full width, always its first row; flipping it rebuilds the tab. */
    static void master(List<AbstractWidget> w, int x, int[] y, int width, String label, BooleanSupplier get,
                       Consumer<Boolean> set, Runnable save, Runnable requestRebuild) {
        w.add(SettingsButtonWidget.builder(onOff(label, get.getAsBoolean()), btn -> {
            set.accept(!get.getAsBoolean());
            save.run();
            requestRebuild.run();
        }).bounds(x, y[0], width, 20).build());
        y[0] += 24;
    }

    static void header(List<AbstractWidget> w, int x, int[] y, int width, String title, boolean cheat) {
        y[0] += 4;
        w.add(new StringWidget(x, y[0], width, 12, SectionHeaders.header(title, cheat), Minecraft.getInstance().font));
        y[0] += 14;
    }

    /** A gray line of text (a readout or an explanation). */
    static void note(List<AbstractWidget> w, int x, int[] y, int width, String text) {
        w.add(new StringWidget(x, y[0], width, 10, Component.literal("§7" + text), Minecraft.getInstance().font));
        y[0] += 12;
    }

    /** The red automation warning line. */
    static void automationWarning(List<AbstractWidget> w, int x, int[] y, int width) {
        w.add(new StringWidget(x, y[0], width, 10, Component.literal("§c" + AUTOMATION_WARNING),
                Minecraft.getInstance().font));
        y[0] += 14;
    }

    static void toggle(List<AbstractWidget> w, int x, int y, int width, String label, BooleanSupplier get,
                       Consumer<Boolean> set, Runnable save) {
        w.add(SettingsButtonWidget.builder(onOff(label, get.getAsBoolean()), btn -> {
            boolean now = !get.getAsBoolean();
            set.accept(now);
            save.run();
            btn.setMessage(onOff(label, now));
        }).bounds(x, y, width, 18).build());
    }

    /** A stepping row: left click calls {@code step(+1)}, right click {@code step(-1)}; {@code value} is redrawn. */
    static void cycle(List<AbstractWidget> w, int x, int y, int width, String label, Supplier<String> value,
                      IntConsumer step, Runnable save) {
        w.add(SettingsButtonWidget.cycling(Component.literal(label + ": " + value.get()), (btn, dir) -> {
            step.accept(dir);
            save.run();
            btn.setMessage(Component.literal(label + ": " + value.get()));
        }).bounds(x, y, width, 18).build());
    }

    static void color(List<AbstractWidget> w, int x, int y, int width, String feature, String label, IntSupplier get,
                      IntConsumer set, int def, Runnable save) {
        w.add(SettingsButtonWidget.builder(com.killer560.hub.gui.ColorSwatch.label(label, get.getAsInt()), btn -> {
            Minecraft client = Minecraft.getInstance();
            com.killer560.hub.compat.McCompat.setScreen(client, new com.killer560.hub.gui.ColorPickerScreen(
                    com.killer560.hub.compat.McCompat.screen(client), feature + " " + label, get.getAsInt(), def,
                    argb -> {
                        set.accept(argb);
                        save.run();
                    }));
        }).bounds(x, y, width, 18).build());
    }

    /** A plain action button. */
    static void button(List<AbstractWidget> w, int x, int y, int width, String label, Runnable action) {
        w.add(SettingsButtonWidget.builder(Component.literal(label), btn -> action.run()).bounds(x, y, width, 18).build());
    }
}

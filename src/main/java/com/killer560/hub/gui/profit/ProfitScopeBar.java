package com.killer560.hub.gui.profit;

import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The row of switches every profit screen shows under its header (see {@link ProfitScope}): "All Profit", then
 * Account, Profile and Mod Profile, each cycling All and every value that tracker has data for (left click forward,
 * right click back). And the two-click reset every profit screen uses, which turns red while it waits for the
 * confirming click and resets only what the switches select.
 */
public final class ProfitScopeBar {

    /** Height of the row, so a screen can lay out below it. */
    public static final int HEIGHT = 16;
    /** How long the reset waits for its confirming click. */
    public static final long CONFIRM_MS = 3000L;

    private ProfitScopeBar() {
    }

    /** Adds the four switches across {@code x .. x + w}; {@code onChange} runs after any of them changes the filter
     *  (save it and rebuild). */
    public static void add(Consumer<AbstractWidget> adder, int x, int y, int w, ProfitScope.Filter filter,
                           Supplier<Collection<ProfitScope.Key>> keys, Runnable onChange) {
        var font = Minecraft.getInstance().font;
        int gap = 4;
        int allW = Math.min(64, Math.max(48, w / 6));
        int each = (w - allW - gap * 3) / 3;
        adder.accept(SettingsButtonWidget.builder(
                Component.literal((filter.isAll() ? "§6" : "§f") + "All Profit"), btn -> {
                    filter.clear();
                    onChange.run();
                }).bounds(x, y, allW, HEIGHT).build());
        int bx = x + allW + gap;
        for (ProfitScope.Field field : ProfitScope.Field.values()) {
            String text = font.plainSubstrByWidth(ProfitScope.label(field, filter), each - 6);
            boolean picked = field.get(filter) != null;
            adder.accept(SettingsButtonWidget.builder(Component.literal((picked ? "§6" : "§f") + text), btn -> {
                        field.cycle(filter, keys.get(), 1);
                        onChange.run();
                    }).secondaryPress(btn -> {
                        field.cycle(filter, keys.get(), -1);
                        onChange.run();
                    }).bounds(bx, y, each, HEIGHT).build());
            bx += each + gap;
        }
    }

    /** A reset button: the first click arms it (red, "Reset <scope>?"), a second within {@link #CONFIRM_MS} runs
     *  {@code reset}. {@code armedAt[0]} holds the arming time across rebuilds; {@code rebuild} redraws the screen. */
    public static SettingsButtonWidget resetButton(int x, int y, int w, int h, long[] armedAt,
                                                   ProfitScope.Filter filter, Runnable reset, Runnable rebuild) {
        boolean armed = isArmed(armedAt);
        String label = armed
                ? Minecraft.getInstance().font.plainSubstrByWidth("Reset " + filter.describe() + "?", w - 6)
                : "Reset Totals";
        return SettingsButtonWidget.builder(Component.literal(label), btn -> {
            if (isArmed(armedAt)) {
                armedAt[0] = 0L;
                reset.run();
            } else {
                armedAt[0] = System.currentTimeMillis();
            }
            rebuild.run();
        }).danger(armed).bounds(x, y, w, h).build();
    }

    public static boolean isArmed(long[] armedAt) {
        return armedAt[0] != 0L && System.currentTimeMillis() - armedAt[0] < CONFIRM_MS;
    }

    /** For a screen's tick: true once an armed reset has timed out (the screen rebuilds to turn it back). */
    public static boolean expired(long[] armedAt) {
        if (armedAt[0] != 0L && !isArmed(armedAt)) {
            armedAt[0] = 0L;
            return true;
        }
        return false;
    }
}

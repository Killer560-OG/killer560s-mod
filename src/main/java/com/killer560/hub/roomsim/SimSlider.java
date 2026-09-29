package com.killer560.hub.roomsim;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/**
 * A whole-number slider for the generate panel.
 *
 * <p>killer560 (2026-09-28): "Max rooms to blood should be 8 and it should be a sliding bar between 2-8 [...]
 * Make puzzles a bar as well from 2-5."
 *
 * <p>Built on vanilla's own slider rather than drawn here, so it drags, keyboards and narrates like every other
 * slider in the game. The only thing added is that it snaps to integers and reports them: vanilla's slider is a
 * 0..1 double, and a "3.4 puzzles" is not a thing a floor can have.
 */
public final class SimSlider extends AbstractSliderButton {

    private final String label;
    private final int min;
    private final int max;
    private final IntConsumer onChange;

    public SimSlider(int x, int y, int width, String label, int min, int max, int value, IntConsumer onChange) {
        super(x, y, width, 20, Component.empty(), fraction(value, min, max));
        this.label = label;
        this.min = min;
        this.max = max;
        this.onChange = onChange;
        updateMessage();
    }

    /** Where the handle sits for a given value. Guards against min == max, which would divide by zero. */
    private static double fraction(int value, int min, int max) {
        if (max <= min) {
            return 0.0;
        }
        return (double) (Math.max(min, Math.min(max, value)) - min) / (max - min);
    }

    public int intValue() {
        return (int) Math.round(min + this.value * (max - min));
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal(label + ": §6" + intValue()));
    }

    @Override
    protected void applyValue() {
        int v = intValue();
        // Snapped back onto the integer it reports, so the handle cannot sit between two values while the
        // label claims one of them.
        this.value = fraction(v, min, max);
        onChange.accept(v);
    }
}

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
 *
 * <p>The far right is RANDOM. killer560 (2026-09-29): "have it so if i go all the way right for a slider then
 * it is on random. but still uses the min and max values and cannot go beyond them." So the track carries ONE
 * EXTRA STOP past {@code max} rather than stealing the top value for it - "all the way right" is random, and
 * {@code max} itself is still reachable, one notch in. Whoever resolves {@link #RANDOM} draws inside
 * {@code min..max}, so random can never produce a floor the slider could not have asked for directly.
 */
public final class SimSlider extends AbstractSliderButton {

    /** What {@link #intValue()} reports when the handle is at the far right. */
    public static final int RANDOM = Integer.MIN_VALUE;

    private final String label;
    private final int min;
    private final int max;
    private final IntConsumer onChange;

    /** The top of the TRACK, which is one past the top of the range - that last stop is {@link #RANDOM}. */
    private final int track;

    public SimSlider(int x, int y, int width, String label, int min, int max, int value, IntConsumer onChange) {
        super(x, y, width, 20, Component.empty(), fraction(value, min, max + 1));
        this.label = label;
        this.min = min;
        this.max = max;
        this.track = max + 1;
        this.onChange = onChange;
        updateMessage();
    }

    /** Where the handle sits for a given value. {@link #RANDOM} is the far right. Guards against min == top. */
    private static double fraction(int value, int min, int top) {
        if (top <= min) {
            return 0.0;
        }
        if (value == RANDOM) {
            return 1.0;
        }
        return (double) (Math.max(min, Math.min(top, value)) - min) / (top - min);
    }

    /** @return the chosen number, or {@link #RANDOM} when the handle is at the far right */
    public int intValue() {
        int raw = (int) Math.round(min + this.value * (track - min));
        return raw > max ? RANDOM : raw;
    }

    @Override
    protected void updateMessage() {
        int v = intValue();
        setMessage(v == RANDOM
                ? Component.literal(label + ": §6Random §7(" + min + "-" + max + ")")
                : Component.literal(label + ": §6" + v));
    }

    @Override
    protected void applyValue() {
        int v = intValue();
        // Snapped back onto the stop it reports, so the handle cannot sit between two values while the
        // label claims one of them.
        this.value = fraction(v, min, track);
        onChange.accept(v);
    }
}

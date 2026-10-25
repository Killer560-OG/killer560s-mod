package com.killer560.hub.gui;

/**
 * The mod menu's animation clock and easing (killer560, 2026-10-15: "for the mod menu can you make animations for it
 * smooth").
 *
 * <p>Every animation is a {@link Tween}: a value moving from where it was to a target over a fixed duration, read as a
 * pure function of the frame clock. Nothing steps per frame, so a hover, a switch or the drawer looks the same at 30 and
 * at 300 FPS - the only input is how much time has passed since the target changed. A tween that has arrived costs one
 * comparison; a tween is a handful of primitive fields allocated with whatever owns it (a card, a row), never per frame.
 *
 * <p>{@link #frame()} samples the clock once per drawn frame (ModScreen's draw, and the closing menu's fade). The
 * Animations setting ({@link Mode}, Menu Theme drawer) picks the durations: Smooth, Reduced (color and opacity fades
 * only, nothing moves or grows) or Off (every tween is already at its target).
 *
 * <p>Test hooks (docs/TESTING-HOOKS.md): {@link #setClockForTest} fixes the clock; {@code -Dkiller560.test.menuAnimClock=instant}
 * makes every animation instant (as Off) while no case is driving the clock. The gametest lockstep renders one frame per
 * tick as fast as it can, and many cases read the very frame after a click (its text, its pixels); the animation cases
 * drive the clock themselves and get the real durations.
 */
public final class MenuAnim {

    private MenuAnim() {
    }

    /** The Animations setting. */
    public enum Mode {
        SMOOTH("Smooth"),
        REDUCED("Reduced"),
        OFF("Off");

        public final String label;

        Mode(String label) {
            this.label = label;
        }

        public static Mode parse(String s) {
            for (Mode m : values()) {
                if (m.name().equalsIgnoreCase(s) || m.label.equalsIgnoreCase(s)) {
                    return m;
                }
            }
            return SMOOTH;
        }
    }

    /** The Animation Speed setting (killer560, 2026-10-15: "have that speed be fast, then make an extra fast, a normal
     *  and a slow"): a multiplier on every duration. Fast is the original timing and the default. */
    public enum Speed {
        SLOW("Slow", 2.2f),
        NORMAL("Normal", 1.5f),
        FAST("Fast", 1.0f),
        EXTRA_FAST("Extra Fast", 0.55f);

        public final String label;
        public final float scale;

        Speed(String label, float scale) {
            this.label = label;
            this.scale = scale;
        }

        public static Speed parse(String s) {
            for (Speed v : values()) {
                if (v.name().equalsIgnoreCase(s) || v.label.equalsIgnoreCase(s)) {
                    return v;
                }
            }
            return FAST;
        }
    }

    // ---- durations (ms, at Fast) --------------------------------------------------------------------------------------
    public static final int HOVER_MS = 120;
    public static final int TOGGLE_MS = 150;
    public static final int PRESS_MS = 160;
    public static final int DRAWER_MS = 180;
    public static final int SCROLL_MS = 160;
    public static final int OPEN_MS = 160;
    public static final int CLOSE_MS = 120;
    public static final int CATEGORY_MS = 170;
    public static final int TOOLTIP_MS = 120;
    public static final int VALUE_MS = 140;
    public static final int LAYOUT_MS = 180;

    // ---- clock ------------------------------------------------------------------------------------------------------
    private static final boolean INSTANT_UNLESS_DRIVEN = "instant".equalsIgnoreCase(
            System.getProperty("killer560.test.menuAnimClock"));
    private static long testClockNanos = -1L;
    private static long frameNanos = clockNanos();
    private static long eventNanos = Long.MIN_VALUE;
    /** How many times the clock has moved, and its count when the last input came. */
    private static long clockMoves;
    private static long eventMove = -10L; // no input yet (never MIN_VALUE: the difference would overflow)

    /** Samples the clock for this frame. Call once at the top of a frame that draws animated parts. */
    public static void frame() {
        long now = clockNanos();
        if (now != frameNanos) {
            // only a frame that moved the clock moves "the previous frame" (two draws in one instant are one frame)
            frameNanos = now;
            clockMoves++;
        }
    }

    /**
     * Marks the moment of an input event (a click, a key, the wheel). A change it causes starts animating from THAT
     * moment, not from the next frame: at 30 FPS the next frame can be 33 ms later, and an animation that started there
     * would be 33 ms behind the same one at 300 FPS (testkit 1590 measured exactly that before this existed).
     */
    public static void input() {
        eventNanos = clockNanos();
        eventMove = clockMoves;
    }

    /** When a change seen now happened: the input event that caused it (during that input, or at the first frame
     *  after it), or else this frame. */
    public static long changeTime() {
        return clockMoves - eventMove <= 1 ? eventNanos : frameNanos;
    }

    private static long clockNanos() {
        if (testClockNanos >= 0) {
            return testClockNanos;
        }
        return System.nanoTime();
    }

    /** The current frame's time in nanoseconds (only differences mean anything). */
    public static long now() {
        return frameNanos;
    }

    /** Fixes the clock at {@code ms} milliseconds (any origin), or back to the real clock with a negative value. Also
     *  re-samples the current frame's time, so a test can read tweens right after setting it. */
    public static void setClockForTest(double ms) {
        testClockNanos = ms < 0 ? -1L : Math.round(ms * 1_000_000.0);
        long now = clockNanos();
        if (now != frameNanos) {
            frameNanos = now;
            clockMoves++;
        }
    }

    public static Mode mode() {
        return MenuConfig.getInstance().getAnimations();
    }

    /** Duration of a tween in nanoseconds under the current setting: 0 when Off, and 0 under Reduced for one that MOVES
     *  something (a slide, a knob, a scale) - Reduced keeps only color and opacity fades, a little shorter. */
    public static long durationNanos(int ms, boolean motion) {
        Mode m = mode();
        if (m == Mode.OFF || (motion && m == Mode.REDUCED) || (INSTANT_UNLESS_DRIVEN && testClockNanos < 0)) {
            return 0L;
        }
        float scale = MenuConfig.getInstance().getAnimationSpeed().scale;
        return Math.round((m == Mode.REDUCED ? ms * 0.75f : ms) * scale) * 1_000_000L;
    }

    /** A start further than this in the future means the clock itself went back: treat it as finished. (A start a little
     *  ahead is normal: a click stamped after the last frame.) */
    private static final long BACKWARDS = 1_000_000_000L;

    /** Ease-out cubic: fast start, gentle stop. */
    public static float easeOut(float t) {
        if (t <= 0f) {
            return 0f;
        }
        if (t >= 1f) {
            return 1f;
        }
        float u = 1f - t;
        return 1f - u * u * u;
    }

    /** Eased progress (0..1) of something that started at {@code startNanos} and lasts {@code ms} ({@code motion} as in
     *  {@link #durationNanos}); 1 once it is done, when the setting makes it instant, or when {@code startNanos} is
     *  {@link Long#MIN_VALUE} (never started). */
    public static float progress(long startNanos, int ms, boolean motion) {
        if (startNanos == Long.MIN_VALUE) {
            return 1f; // never started: settled (and frameNanos - MIN_VALUE would overflow)
        }
        long d = durationNanos(ms, motion);
        if (d <= 0L) {
            return 1f;
        }
        long e = frameNanos - startNanos;
        if (e < -BACKWARDS) {
            return 1f; // the clock went back (a test switched clocks): nothing is still on its way
        }
        return e >= d ? 1f : easeOut(e / (float) d);
    }

    // ---- fade multiplier ----------------------------------------------------------------------------------------------

    /**
     * Opacity every color drawn right now is multiplied by (the menu fading in or out, a tooltip fading in). Read by
     * {@code gui/mixin/MenuFadeMixin} for every fill and text, and by {@code GuiRects}. 1 almost always.
     */
    public static float fade = 1f;

    /** Multiplies the fade by {@code f}; returns the previous fade for {@link #popFade}. */
    public static float pushFade(float f) {
        float prev = fade;
        fade = prev * Math.max(0f, Math.min(1f, f));
        return prev;
    }

    public static void popFade(float prev) {
        fade = prev;
    }

    /** {@code argb} with its alpha times the current fade. */
    public static int faded(int argb) {
        float f = fade;
        if (f >= 1f) {
            return argb;
        }
        int a = Math.round(((argb >>> 24) & 0xFF) * f);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    // ---- tween ------------------------------------------------------------------------------------------------------

    /** One animated value. Allocated with its owner; reading and retargeting never allocate. */
    public static final class Tween {
        private final int ms;
        private final boolean motion;
        private float from;
        private float to;
        private long start;
        private long duration;
        private boolean placed;

        /** @param ms its full duration under Smooth; @param motion true when it moves something (instant under Reduced) */
        public Tween(int ms, boolean motion) {
            this.ms = ms;
            this.motion = motion;
        }

        /** The value now. */
        public float get() {
            long e = frameNanos - start;
            if (e < -BACKWARDS) {
                return to;
            }
            if (duration <= 0L || e >= duration) {
                return to;
            }
            if (e <= 0L) {
                return from;
            }
            return from + (to - from) * easeOut(e / (float) duration);
        }

        public float target() {
            return to;
        }

        /** Heads for {@code target} from wherever it is now (no-op when that is already the target). The first call
         *  places it there at once. */
        public void set(float target) {
            if (!placed) {
                snap(target);
                return;
            }
            if (target == to) {
                return;
            }
            from = get();
            to = target;
            start = changeTime();
            duration = durationNanos(ms, motion);
        }

        /** Jumps to {@code value} with no animation. */
        public void snap(float value) {
            from = value;
            to = value;
            duration = 0L;
            placed = true;
        }

        /** Starts at {@code from} and heads for {@code target} now (a one-shot: a flash, a fade-in). */
        public void play(float fromValue, float target) {
            placed = true;
            from = fromValue;
            to = target;
            start = changeTime();
            duration = durationNanos(ms, motion);
        }

        public boolean placed() {
            return placed;
        }

        /** Takes over another tween's state (the same row rebuilt as a new widget keeps animating where it was). */
        public void copyFrom(Tween o) {
            from = o.from;
            to = o.to;
            start = o.start;
            duration = o.duration;
            placed = o.placed;
        }
    }

    /**
     * The animation state of one menu row (a switch, a pill, a button, a slider): hover, the switch's knob, the press
     * flash, the slider's shown value and a changed value's fade. Rows are rebuilt as new widgets on every change, so
     * ModScreen hands each new row its predecessor's state ({@link #copyFrom}) by label.
     */
    public static final class RowAnim {
        public final Tween hover = new Tween(HOVER_MS, false);
        public final Tween on = new Tween(TOGGLE_MS, true);
        public final Tween onColour = new Tween(TOGGLE_MS, false);
        public final Tween press = new Tween(PRESS_MS, false);
        public final Tween value = new Tween(VALUE_MS, true);
        /** A cycling value's text fading in after it changed (0 just changed .. 1 settled) and from which side. */
        public final Tween valueIn = new Tween(VALUE_MS, false);
        public int valueDir;
        /** The value text last drawn, compared by equals (no allocation) to notice a change. */
        public String lastValue;

        public void copyFrom(RowAnim o) {
            hover.copyFrom(o.hover);
            on.copyFrom(o.on);
            onColour.copyFrom(o.onColour);
            press.copyFrom(o.press);
            value.copyFrom(o.value);
            valueIn.copyFrom(o.valueIn);
            valueDir = o.valueDir;
            lastValue = o.lastValue;
        }

        /** Marks a press: the row flashes and settles. */
        public void pressed() {
            press.play(1f, 0f);
        }

        /** Notices a changed value text and starts its fade-in (from the side {@link #valueDir} names: +1 a step
         *  forward, -1 back, 0 none). */
        public void value(String v) {
            if (lastValue != null && !lastValue.equals(v)) {
                valueIn.play(0f, 1f);
            } else if (lastValue == null) {
                valueIn.snap(1f);
            }
            lastValue = v;
        }
    }

    /** Implemented by a menu widget that animates: its state, and the key a rebuilt copy of it is found by. */
    public interface Animated {
        RowAnim anim();

        /** Stable across a rebuild for the same setting (its label, not its value). */
        String animKey();
    }
}

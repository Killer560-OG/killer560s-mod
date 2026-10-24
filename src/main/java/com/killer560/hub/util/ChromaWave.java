package com.killer560.hub.util;

import net.minecraft.network.chat.TextColor;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The one place the mod's animated "chroma" color is worked out (killer560, 2026-10-13: "for chroma for things like
 * the enchant colors make it so it waterfalls the color from top left to bottom right in a wavy manner").
 *
 * <p>The color at a point depends on where the point is on screen and on the time: the cycle position is
 * {@code (x + y + wobble) / span - t / period}, so a line of equal color is a diagonal ({@code x + y} constant) that
 * slides toward the bottom right as time passes, and the {@code wobble} - a small sine of the position ACROSS the
 * band, {@code x - y}, which itself drifts with time - bends that straight stripe into a wave. Every chroma user
 * passes its real screen position, so a multi-line text block (a whole tooltip) or a border is one continuous
 * waterfall rather than a stripe restarting on each line.
 *
 * <p>Two ways in:
 * <ul>
 *   <li>Shapes (the scoreboard border, the crosshair) call {@link #rainbow}/{@link #gradient} with their own
 *       position, speed and size.</li>
 *   <li>Text gets a {@link Spec}: {@link Spec#textColor()} is a {@link TextColor} put on a {@code Style} like any
 *       other color, and {@code chroma/mixin/ChromaGlyphColorMixin} recolors every glyph drawn in it from the
 *       glyph's own position as the font lays the text out (GUI text is prepared every frame at its GUI
 *       coordinates, so this animates and needs no rebuilt components). Its RGB value is the static color shown
 *       if that hook is ever missing. A spec is either the rainbow or a looping gradient between color stops, which
 *       is what an animated fade name (supporters' "Wave" mode) needs: make ONE spec per gradient and keep it.</li>
 * </ul>
 *
 * <p>Nothing here allocates per call: the glyph path looks the color up in a 256-step table per spec.
 */
public final class ChromaWave {

    /** GUI units along {@code x + y} for one whole cycle at Size 1. A tooltip is about 150 x 100, so 1.5 rainbows. */
    public static final float BASE_SPAN = 160f;
    /** Milliseconds for one whole cycle to pass a point at Speed 1 (the old Enchant Colors / crosshair period). */
    public static final float BASE_PERIOD_MS = 3000f;
    /** How far (GUI units, along the diagonal) the wave bends the band, at Size 1. */
    public static final float WOBBLE_AMPLITUDE = 6f;
    /** Length of one wave of the bend, measured across the band ({@code x - y}), at Size 1. */
    public static final float WOBBLE_LENGTH = 80f;
    /** Milliseconds for the bend itself to drift by one wavelength. */
    public static final float WOBBLE_PERIOD_MS = 2600f;

    private static final int TABLE = 256;
    private static final double TWO_PI = Math.PI * 2;

    /** Registered text specs by their sentinel {@link TextColor} INSTANCE (copy on write; read on the render thread). */
    private static volatile Map<TextColor, Spec> specs = new IdentityHashMap<>();
    /**
     * The same specs as a plain array while there are few ({@link #LINEAR_MAX} or fewer; null past that). The glyph
     * hook asks for every glyph the game draws, and the Enchant Colors spec is registered as soon as that class loads,
     * so the map was never empty: a reference compare or two is cheaper than an identity-hash lookup per glyph
     * (mixin FPS pass, 2026-10-15). Both are replaced together, under the same lock, on every register.
     */
    private static volatile Spec[] few = new Spec[0];
    private static final int LINEAR_MAX = 8;

    /** Test hook: a fixed clock in ms, or -1 for the real one. Lets a test take two frames a known time apart. */
    private static volatile long clockOverride = -1L;

    private ChromaWave() {
    }

    public static long now() {
        long o = clockOverride;
        return o >= 0 ? o : System.currentTimeMillis();
    }

    /** Test hook (testkit): freeze chroma at {@code ms}, or pass -1 to go back to the real clock. */
    public static void setClockOverride(long ms) {
        clockOverride = ms;
    }

    /**
     * Cycle position in [0, 1) at screen point ({@code x}, {@code y}) at time {@code nowMs}. {@code speed} multiplies
     * how fast the bands move (1 = {@link #BASE_PERIOD_MS} per cycle), {@code size} how wide they are (1 =
     * {@link #BASE_SPAN}).
     */
    public static float phase(float x, float y, long nowMs, float speed, float size) {
        float sz = Math.max(0.05f, size);
        float sp = Math.max(0.01f, speed);
        long periodMs = Math.max(1L, Math.round(BASE_PERIOD_MS / sp));
        double t = (nowMs % periodMs) / (double) periodMs;
        double tw = (nowMs % (long) WOBBLE_PERIOD_MS) / (double) WOBBLE_PERIOD_MS;
        double bend = WOBBLE_AMPLITUDE * sz * Math.sin((x - y) * TWO_PI / (WOBBLE_LENGTH * sz) + tw * TWO_PI);
        double p = (x + y + bend) / (BASE_SPAN * sz) - t;
        return (float) (p - Math.floor(p));
    }

    /** The rainbow at a point: hue = {@link #phase}, at the given saturation and brightness. Returns 0xRRGGBB. */
    public static int rainbow(float x, float y, long nowMs, float speed, float size, float saturation, float brightness) {
        return hsv(phase(x, y, nowMs, speed, size), saturation, brightness);
    }

    /** {@link #rainbow} at full saturation and brightness, real clock. */
    public static int rainbow(float x, float y, float speed, float size) {
        return hsv(phase(x, y, now(), speed, size), 1f, 1f);
    }

    /** A looping gradient through {@code stops} (0xRRGGBB, back to the first after the last) at a point. */
    public static int gradient(float x, float y, long nowMs, float speed, float size, int[] stops) {
        return gradientAt(phase(x, y, nowMs, speed, size), stops);
    }

    /** Color {@code phase} (0..1) of the way round a looping gradient through {@code stops}. Returns 0xRRGGBB. */
    public static int gradientAt(float phase, int[] stops) {
        if (stops == null || stops.length == 0) {
            return 0xFFFFFF;
        }
        if (stops.length == 1) {
            return stops[0] & 0xFFFFFF;
        }
        float f = (phase - (float) Math.floor(phase)) * stops.length;
        int i = Math.min(stops.length - 1, (int) f);
        float k = f - i;
        int a = stops[i];
        int b = stops[(i + 1) % stops.length];
        int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * k);
        int g = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * k);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * k);
        return r << 16 | g << 8 | bl;
    }

    /** Hue/saturation/value in [0,1] to 0xRRGGBB (no AWT on the render thread). */
    public static int hsv(float h, float s, float v) {
        float hp = (h - (float) Math.floor(h)) * 6f;
        int sector = Math.min(5, (int) hp);
        float f = hp - sector;
        float p = v * (1 - s);
        float q = v * (1 - s * f);
        float t = v * (1 - s * (1 - f));
        float r;
        float g;
        float b;
        switch (sector) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
    }

    // ------------------------------------------------------------------ text

    /** The spec whose {@link Spec#textColor()} is exactly this instance, or null (the per-glyph check). */
    public static Spec specFor(TextColor color) {
        if (color == null) {
            return null;
        }
        Spec[] a = few;
        if (a != null) {
            for (Spec spec : a) {
                if (spec.textColor == color) {
                    return spec;
                }
            }
            return null;
        }
        Map<TextColor, Spec> m = specs;
        return m.isEmpty() ? null : m.get(color);
    }

    /** A rainbow spec for text. Create once and keep it; every spec stays registered for the session. */
    public static Spec rainbowSpec(int fallbackRgb) {
        return register(new Spec(null, fallbackRgb));
    }

    /** A looping-gradient spec for text through {@code stops} (0xRRGGBB). Create once per gradient and keep it. */
    public static Spec gradientSpec(int[] stops) {
        int[] copy = stops == null || stops.length == 0 ? new int[]{0xFFFFFF} : stops.clone();
        return register(new Spec(copy, copy[0]));
    }

    private static synchronized Spec register(Spec spec) {
        Map<TextColor, Spec> next = new IdentityHashMap<>(specs);
        next.put(spec.textColor, spec);
        // Array first, then the map: a reader that sees the new array has every spec in it; one that still sees the
        // old array misses only the spec being registered now, exactly as one reading the old map would.
        few = next.size() <= LINEAR_MAX ? next.values().toArray(new Spec[0]) : null;
        specs = next;
        return spec;
    }

    /** One animated text color: rainbow or a looping gradient, with its own speed and size. */
    public static final class Spec {
        private final TextColor textColor;
        private final int[] table = new int[TABLE];
        private final TextColor[] textTable = new TextColor[TABLE];
        private volatile float speed = 1f;
        private volatile float size = 1f;

        private Spec(int[] stops, int fallbackRgb) {
            // A fresh instance (fromRgb never interns, javap 26.1.2 and 26.2): the font hook matches it by identity,
            // so a real color that happens to share the RGB value is never mistaken for it.
            this.textColor = TextColor.fromRgb(fallbackRgb & 0xFFFFFF);
            for (int i = 0; i < TABLE; i++) {
                float ph = i / (float) TABLE;
                table[i] = stops == null ? hsv(ph, 1f, 1f) : gradientAt(ph, stops);
                textTable[i] = TextColor.fromRgb(table[i]);
            }
        }

        /** Put this on a {@code Style}; every glyph drawn in it is recolored from its own position. */
        public TextColor textColor() {
            return textColor;
        }

        public Spec speed(float speed) {
            this.speed = speed;
            return this;
        }

        public Spec size(float size) {
            this.size = size;
            return this;
        }

        /** 0xRRGGBB at a point, real clock. */
        public int colorAt(float x, float y) {
            return colorAt(x, y, now());
        }

        public int colorAt(float x, float y, long nowMs) {
            return table[index(x, y, nowMs)];
        }

        /** The glyph hook's answer: a pre-made {@link TextColor} for this point (no allocation per glyph). */
        public TextColor textColorAt(float x, float y) {
            return textTable[index(x, y, now())];
        }

        private int index(float x, float y, long nowMs) {
            int i = (int) (phase(x, y, nowMs, speed, size) * TABLE);
            return Math.min(TABLE - 1, Math.max(0, i));
        }
    }
}

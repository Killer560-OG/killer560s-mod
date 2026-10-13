package com.killer560.hub.namechanger;

import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Animated faded names (killer560, 2026-10-13: "if someone has our custom name with a fade color, they can have it
 * pulsate and kind of do a wave ... where the color shifts right"). A name with a fade or letter colours can be
 * {@link Mode#WAVE} (the colours flow to the right along the name, looping on a mirrored copy of the fade so there is
 * no seam) or {@link Mode#PULSE} (the whole name dims and brightens), at a {@link #MIN_SPEED}-{@link #MAX_SPEED}
 * speed. Your own setting lives in {@link NameChangerConfig}; another player's arrives with their supporter entry.
 * <p>
 * <b>Where it is applied.</b> Not where names are built, but where text is drawn: {@code NameChangerFontMixin} passes
 * every {@code Font.prepareText} through {@link #animate} / {@link #iterate}. A painted name is recognised by its exact
 * letters in their exact base colours (every glyph of the run must match, so ordinary text cannot), and only those
 * glyphs are recoloured for the current time. So chat lines baked minutes ago, the tab list's cached rows, nametags,
 * the menu preview and anything else that paints the name all animate, without one hook per place.
 * <p>
 * Cost: with no animated name known, {@link #animate} is one volatile read. Otherwise each prepared text is walked
 * once into a reused per-thread buffer; a text without a glyph in a registered starting colour is returned as it was,
 * and only a text that contains an animated name allocates (its copy and one style per animated letter). The colour is
 * computed from the wall clock each frame, so it is smooth at any frame rate and two clients show roughly the same
 * phase.
 */
public final class NameAnimation {

    /** The animation of one name. {@link #wire} is the relay's {@code animation.mode} value. */
    public enum Mode {
        OFF("off", "Off"),
        WAVE("wave", "Wave"),
        PULSE("pulse", "Pulse");

        public final String wire;
        public final String label;

        Mode(String wire, String label) {
            this.wire = wire;
            this.label = label;
        }

        /** The mode for a config/relay string, {@link #OFF} for anything unknown. */
        public static Mode fromWire(String s) {
            if (s != null) {
                for (Mode m : values()) {
                    if (m.wire.equals(s.trim().toLowerCase(Locale.ROOT))) {
                        return m;
                    }
                }
            }
            return OFF;
        }
    }

    /** A name's animation as shared over the relay; never {@link Mode#OFF} (that is {@code null}). */
    public record Spec(Mode mode, double speed) {
        public Spec {
            speed = clampSpeed(speed);
        }

        /** {@code null} for {@link Mode#OFF} or no mode. */
        public static Spec of(Mode mode, double speed) {
            return mode == null || mode == Mode.OFF ? null : new Spec(mode, speed);
        }
    }

    public static final double MIN_SPEED = 0.25;
    public static final double MAX_SPEED = 3.0;
    public static final double SPEED_STEP = 0.25;
    public static final double DEFAULT_SPEED = 1.0;
    /** Wave at 1x: letters per second the colours move (250 ms per letter). */
    static final double WAVE_LETTERS_PER_SEC = 4.0;
    /** Pulse at 1x: seconds per bright-dim-bright cycle. */
    static final double PULSE_PERIOD_SEC = 2.0;
    /** Pulse: the darkest point, as a fraction of the name's own colours. */
    static final double PULSE_MIN = 0.55;

    /** {@code speed} snapped onto the {@link #SPEED_STEP} grid inside the bounds; {@link #DEFAULT_SPEED} for NaN. */
    public static double clampSpeed(double speed) {
        if (!Double.isFinite(speed)) {
            return DEFAULT_SPEED;
        }
        double c = Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
        return Math.round(c / SPEED_STEP) * SPEED_STEP;
    }

    /** "1x", "1.5x", "0.25x". */
    public static String speedLabel(double speed) {
        double s = clampSpeed(speed);
        String t = s == Math.rint(s) ? String.valueOf((long) s) : String.valueOf(s);
        return t + "x";
    }

    // ---- registry ---------------------------------------------------------------------------------------------------

    /** One animated name: its letters (code points) and their base colours, plus the wave's looping palette. */
    private static final class Entry {
        final int[] cps;
        final int[] rgb;
        final Mode mode;
        final double speed;
        final int[] palette;

        Entry(int[] cps, int[] rgb, Mode mode, double speed) {
            this.cps = cps;
            this.rgb = rgb;
            this.mode = mode;
            this.speed = speed;
            int n = rgb.length;
            if (n < 2) {
                palette = rgb.clone();
            } else {
                // The fade, then back again without repeating either end: a loop with no jump.
                palette = new int[2 * n - 2];
                for (int i = 0; i < n; i++) {
                    palette[i] = rgb[i];
                }
                for (int i = n - 2; i >= 1; i--) {
                    palette[2 * n - 2 - i] = rgb[i];
                }
            }
        }

        int colourAt(int letter, double tSec) {
            if (mode == Mode.PULSE) {
                double k = PULSE_MIN + (1 - PULSE_MIN) * (0.5 + 0.5 * Math.cos(2 * Math.PI * tSec * speed / PULSE_PERIOD_SEC));
                return scale(rgb[letter], k);
            }
            int len = palette.length;
            if (len < 2) {
                return rgb[letter];
            }
            double x = letter - tSec * speed * WAVE_LETTERS_PER_SEC;
            x = x - Math.floor(x / len) * len;
            int j = (int) Math.floor(x);
            double f = x - j;
            j = Math.floorMod(j, len);
            return lerp(palette[j], palette[(j + 1) % len], f);
        }
    }

    private static final Entry[] NONE = new Entry[0];

    private static volatile Entry[] entries = NONE;
    /** Sorted distinct first-letter colours of {@link #entries}: the quick reject. */
    private static volatile int[] starts = new int[0];
    private static volatile Entry ownEntry;
    private static volatile List<Entry> shared = List.of();
    private static int builtOwnVersion = Integer.MIN_VALUE;

    /** Test hook (testkit 960-965): a fixed clock in epoch ms, or -1 for the real one. */
    private static volatile long frozenMs = -1L;

    private NameAnimation() {
    }

    /** Testkit only: freezes the animation clock at {@code epochMs} (so two screenshots are exact), -1 to unfreeze. */
    public static void freezeClockForTest(long epochMs) {
        frozenMs = epochMs;
    }

    static double nowSec() {
        long f = frozenMs;
        return (f >= 0 ? f : System.currentTimeMillis()) / 1000.0;
    }

    /**
     * Replaces every other player's animated name (from the supporters list): each item is a painted display text
     * ({@code §} codes, hex escapes included) and its animation. Names without a colour on every letter are skipped.
     */
    public static void setShared(List<Object[]> textAndSpec) {
        List<Entry> out = new ArrayList<>();
        for (Object[] item : textAndSpec) {
            Entry e = entryFor((String) item[0], (Spec) item[1]);
            if (e != null) {
                out.add(e);
            }
        }
        shared = List.copyOf(out);
        rebuild();
    }

    private static Entry entryFor(String painted, Spec spec) {
        if (painted == null || spec == null || spec.mode() == Mode.OFF) {
            return null;
        }
        String plain = NameColor.plain(painted);
        int[] cps = plain.codePoints().toArray();
        int[] rgb = NameColor.letterColours(painted);
        if (cps.length == 0 || cps.length != rgb.length) {
            return null;
        }
        for (int c : rgb) {
            if (c < 0) {
                return null;
            }
        }
        return new Entry(cps, rgb, spec.mode(), spec.speed());
    }

    /** Re-reads your own name's animation when the Name Changer config changed. Cheap when it did not. */
    private static void refreshOwn() {
        int v = NameChangerConfig.version();
        if (v == builtOwnVersion) {
            return;
        }
        builtOwnVersion = v;
        NameChangerConfig cfg = NameChangerConfig.getInstance();
        Spec spec = NameChangerFeature.ownAnimation();
        ownEntry = spec == null ? null : entryFor(NameChangerFeature.ownStyled(cfg), spec);
        rebuild();
    }

    private static synchronized void rebuild() {
        List<Entry> all = new ArrayList<>();
        Entry own = ownEntry;
        if (own != null) {
            all.add(own);
        }
        all.addAll(shared);
        int[] s = new int[all.size()];
        for (int i = 0; i < s.length; i++) {
            s[i] = all.get(i).rgb[0];
        }
        Arrays.sort(s);
        starts = s;
        entries = all.toArray(NONE);
    }

    private static Entry[] registry() {
        refreshOwn();
        return entries;
    }

    // ---- the Font hook ----------------------------------------------------------------------------------------------

    /** {@code seq} with every animated name in it recoloured for now, or {@code seq} itself when there is none. */
    public static FormattedCharSequence animate(FormattedCharSequence seq) {
        if (seq == null) {
            return null;
        }
        Entry[] reg = registry();
        if (reg.length == 0) {
            return seq;
        }
        Buf b = BUF.get();
        if (b.busy) {
            return seq;
        }
        b.busy = true;
        try {
            b.reset(starts);
            seq.accept(b);
            if (!b.candidate || !b.recolour(reg, nowSec())) {
                return seq;
            }
            int n = b.count;
            int[] pos = Arrays.copyOf(b.pos, n);
            int[] cps = Arrays.copyOf(b.cps, n);
            Style[] st = Arrays.copyOf(b.styles, n);
            return sink -> {
                for (int i = 0; i < n; i++) {
                    if (!sink.accept(pos[i], st[i], cps[i])) {
                        return false;
                    }
                }
                return true;
            };
        } catch (RuntimeException e) {
            return seq; // a cosmetic must never break text rendering
        } finally {
            b.busy = false;
        }
    }

    /** {@link NameColor#iterateFormatted} with every animated name recoloured for now (the String path of the Font). */
    public static boolean iterate(String text, Style base, FormattedCharSink sink) {
        Entry[] reg = registry();
        if (reg.length == 0 || text == null) {
            return NameColor.iterateFormatted(text, base, sink);
        }
        Buf b = BUF.get();
        if (b.busy) {
            return NameColor.iterateFormatted(text, base, sink);
        }
        boolean replay;
        b.busy = true;
        try {
            b.reset(starts);
            NameColor.iterateFormatted(text, base, b);
            replay = b.candidate && b.recolour(reg, nowSec());
            if (replay) {
                int n = b.count;
                for (int i = 0; i < n; i++) {
                    if (!sink.accept(b.pos[i], b.styles[i], b.cps[i])) {
                        return false;
                    }
                }
                return true;
            }
        } catch (RuntimeException e) {
            replay = false;
        } finally {
            b.busy = false;
        }
        return NameColor.iterateFormatted(text, base, sink);
    }

    /** {@code text}'s glyph colours for now (0xRRGGBB, -1 for none): what the Font hook paints. For the testkit. */
    public static int[] coloursNow(FormattedCharSequence text) {
        List<Integer> out = new ArrayList<>();
        animate(text).accept((pos, style, cp) -> {
            TextColor c = style.getColor();
            out.add(c == null ? -1 : c.getValue() & 0xFFFFFF);
            return true;
        });
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static final ThreadLocal<Buf> BUF = ThreadLocal.withInitial(Buf::new);

    /** The glyphs of one text, reused between calls on a thread. */
    private static final class Buf implements FormattedCharSink {
        int[] pos = new int[128];
        int[] cps = new int[128];
        Style[] styles = new Style[128];
        int count;
        boolean candidate;
        boolean busy;
        int[] startColours;

        void reset(int[] startColours) {
            this.startColours = startColours;
            Arrays.fill(styles, 0, count, null);
            count = 0;
            candidate = false;
        }

        @Override
        public boolean accept(int position, Style style, int codepoint) {
            if (count == cps.length) {
                pos = Arrays.copyOf(pos, count * 2);
                cps = Arrays.copyOf(cps, count * 2);
                styles = Arrays.copyOf(styles, count * 2);
            }
            pos[count] = position;
            cps[count] = codepoint;
            styles[count] = style;
            count++;
            if (!candidate) {
                TextColor c = style.getColor();
                candidate = c != null && Arrays.binarySearch(startColours, c.getValue() & 0xFFFFFF) >= 0;
            }
            return true;
        }

        /** Recolours every registered name found in the buffer. True when at least one was. */
        boolean recolour(Entry[] reg, double tSec) {
            boolean any = false;
            int i = 0;
            outer:
            while (i < count) {
                TextColor c = styles[i].getColor();
                if (c != null) {
                    int first = c.getValue() & 0xFFFFFF;
                    for (Entry e : reg) {
                        if (e.rgb[0] == first && matches(e, i)) {
                            for (int k = 0; k < e.cps.length; k++) {
                                styles[i + k] = styles[i + k].withColor(TextColor.fromRgb(e.colourAt(k, tSec)));
                            }
                            i += e.cps.length;
                            any = true;
                            continue outer;
                        }
                    }
                }
                i++;
            }
            return any;
        }

        private boolean matches(Entry e, int at) {
            int m = e.cps.length;
            if (at + m > count) {
                return false;
            }
            for (int k = 0; k < m; k++) {
                if (cps[at + k] != e.cps[k]) {
                    return false;
                }
                TextColor c = styles[at + k].getColor();
                if (c == null || (c.getValue() & 0xFFFFFF) != e.rgb[k]) {
                    return false;
                }
            }
            return true;
        }
    }

    // ---- colour maths -----------------------------------------------------------------------------------------------

    static int lerp(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    static int scale(int rgb, double k) {
        int r = (int) Math.round(((rgb >> 16) & 255) * k);
        int g = (int) Math.round(((rgb >> 8) & 255) * k);
        int b = (int) Math.round((rgb & 255) * k);
        return (r << 16) | (g << 8) | b;
    }
}

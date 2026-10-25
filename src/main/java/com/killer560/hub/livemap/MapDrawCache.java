package com.killer560.hub.livemap;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The Dungeon Map HUD's painted output, kept between frames (FPS pass, 2026-10-15).
 *
 * <p>What the map draws is a function of state that changes a few times a second at most - rooms, doors, map item
 * states, labels, settings - but it used to be worked out and submitted from scratch every frame: every room's state
 * and color, every door's color from its neighbors, every label and checkmark run, a fresh render-state object per
 * fill and per line of text, and for every line a fresh glyph layout in the GUI renderer. The heaviest HUD element
 * under ALL ON (95-perf-features: ~55 us and ~19.5 KB a frame).
 *
 * <p>So the render-state elements one part of the map hands to {@link GuiRenderState} are RECORDED
 * ({@code livemap/mixin/MapDrawCacheGuiRenderStateMixin} hands every {@code add*} call here while a {@link Segment}
 * records), and on a later frame whose {@link Key} is identical the same elements are handed to the render state again,
 * in the same order, through the same methods - so they are layered against the rest of the HUD exactly as a fresh
 * draw would be, and the pixels are the same. A {@link GuiTextRenderState} keeps its glyph layout once prepared, so a
 * replayed line of text is not laid out again either; that layout belongs to the font's glyph textures, which is why
 * {@link #fontGeneration} (bumped by {@code FontSet.reload}) is part of every key.
 *
 * <p>The keys are the inputs, compared exactly (no hashes): see {@code LiveMapHudElement.render}. With
 * {@code -Dkiller560.livemap.verifyCache=true} (testkit) every replayed frame is ALSO painted fresh into a scratch
 * segment that is not submitted, and compared element by element with what was replayed; {@link #stats()} counts the
 * frames that differed, split by whether the cached copy was built in the same client tick (state that changed between
 * two frames of one tick: shown at the next tick, at most 50 ms later) or in an earlier tick (an input the key misses,
 * i.e. a bug).
 *
 * <p>Render thread only.
 */
public final class MapDrawCache {

    static final int ELEMENT = 0;
    static final int TEXT = 1;
    static final int ITEM = 2;
    static final int PIP = 3;
    static final int BLIT = 4;
    static final int GLYPH = 5;
    static final int UP = 6;
    static final int NEXT_STRATUM = 7;
    static final int BLUR = 8;

    private static boolean VERIFY = Boolean.getBoolean("killer560.livemap.verifyCache");

    /** The segment recording right now, or null. */
    private static Segment recording;
    /** While true the recorded calls do not reach the render state (verify mode's scratch paint). */
    private static boolean swallow;
    /** Bumped by {@code FontSet.reload}: a prepared glyph layout from before it must never be drawn again. */
    private static int fontGeneration;

    /** hits, builds, verified, mismatch built this tick, mismatch built an earlier tick. */
    private static final long[] STATS = new long[5];

    private MapDrawCache() {
    }

    /** From the mixin, for every {@code GuiRenderState.add*}/{@code up}/{@code nextStratum}/{@code blurBeforeThisStratum}.
     *  @return true when the call must not reach the render state (verify scratch paint) */
    public static boolean onAdd(int kind, Object arg) {
        Segment s = recording;
        if (s == null) {
            return false;
        }
        if (depth > 0) {
            // Called from inside a recorded call (addGuiElement opens a new layer with up() when the element overlaps
            // one below): that is the render state's own doing and happens again when the outer call is replayed.
            depth++;
            return false;
        }
        s.add(kind, arg);
        if (swallow) {
            return true;
        }
        depth = 1;
        return false;
    }

    /** From the mixin at every return of the same methods. */
    public static void onReturn() {
        if (depth > 0) {
            depth--;
        }
    }

    /** How deep inside a recorded render-state call the render thread is (0 outside one). */
    private static int depth;

    public static void onFontReload() {
        fontGeneration++;
    }

    /** Bumped on every {@code FontSet.reload}; also keys the overlay message's wrapped lines (hud/GuiOverlays). */
    public static int fontGeneration() {
        return fontGeneration;
    }

    /** Test hook: {hits, builds, verified, mismatchSameTick, mismatchStale}. */
    public static long[] stats() {
        return STATS.clone();
    }

    public static void resetStats() {
        Arrays.fill(STATS, 0L);
    }

    /** Test hook: whether verify mode is on in this client. */
    public static boolean verifying() {
        return VERIFY;
    }

    /** Test hook: verify mode on or off (the bench measures with it off, then checks with it on). */
    public static void setVerify(boolean on) {
        VERIFY = on;
    }

    // ------------------------------------------------------------------------------------------------ keys

    /** The inputs a segment was painted from, compared value for value. Reused: {@link #begin} then the adds. */
    static final class Key {
        private long[] values = new long[64];
        private Object[] refs = new Object[16];
        private int n;
        private int r;

        Key begin() {
            n = 0;
            Arrays.fill(refs, 0, r, null);
            r = 0;
            return this;
        }

        Key add(long v) {
            if (n == values.length) {
                values = Arrays.copyOf(values, n * 2);
            }
            values[n++] = v;
            return this;
        }

        Key add(boolean v) {
            return add(v ? 1L : 0L);
        }

        Key add(float v) {
            return add((long) Float.floatToIntBits(v));
        }

        /** An object compared with {@link Objects#equals} (strings, lists of strings, records). */
        Key ref(Object o) {
            if (r == refs.length) {
                refs = Arrays.copyOf(refs, r * 2);
            }
            refs[r++] = o;
            return this;
        }

        /** Appends every value and object of {@code other}, in order. */
        Key addAll(Key other) {
            for (int i = 0; i < other.n; i++) {
                add(other.values[i]);
            }
            for (int i = 0; i < other.r; i++) {
                ref(other.refs[i]);
            }
            return this;
        }

        boolean sameAs(Key other) {
            if (n != other.n || r != other.r) {
                return false;
            }
            for (int i = 0; i < n; i++) {
                if (values[i] != other.values[i]) {
                    return false;
                }
            }
            for (int i = 0; i < r; i++) {
                if (!Objects.equals(refs[i], other.refs[i])) {
                    return false;
                }
            }
            return true;
        }

        void copyFrom(Key other) {
            values = other.values.length > values.length ? new long[other.values.length] : values;
            System.arraycopy(other.values, 0, values, 0, other.n);
            n = other.n;
            Arrays.fill(refs, 0, r, null);
            refs = other.refs.length > refs.length ? new Object[other.refs.length] : refs;
            System.arraycopy(other.refs, 0, refs, 0, other.r);
            r = other.r;
        }
    }

    /** The frame-level inputs every segment shares: where and how it lands on screen, and what text looks like. */
    static Key frameKey(Key k, GuiGraphicsExtractor graphics) {
        org.joml.Matrix3x2f pose = graphics.pose();
        k.add(pose.m00()).add(pose.m01()).add(pose.m10()).add(pose.m11()).add(pose.m20()).add(pose.m21());
        k.ref(graphics.scissorStack.peek());
        k.add(com.killer560.hub.gui.MenuAnim.fade);
        k.add(fontGeneration);
        k.ref(net.minecraft.client.Minecraft.getInstance().font);   // compared by identity
        k.ref(net.minecraft.locale.Language.getInstance());   // no equals override: compared by identity
        // Mixins on GuiGraphicsExtractor.text that change what a line looks like (objecthider, namechanger).
        com.killer560.hub.objecthider.ObjectHiderConfig oh = com.killer560.hub.objecthider.ObjectHiderConfig.getInstance();
        k.add(oh.isDisableTextShadow()).add(oh.isContainerTextShadow());
        if (oh.isContainerTextShadow()) {
            k.add(com.killer560.hub.compat.McCompat.screen(net.minecraft.client.Minecraft.getInstance())
                    instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>);
        }
        k.add(com.killer560.hub.namechanger.NameChangerConfig.getInstance().isActive());
        return k;
    }

    // ------------------------------------------------------------------------------------------------ segments

    /** One recorded part of the map: its elements in order, and the key and tick it was painted for. */
    static final class Segment {
        private int[] kinds = new int[128];
        private Object[] args = new Object[128];
        private int count;
        private boolean valid;
        private final Key key = new Key();
        private int builtTick;
        private int lastHitTick;
        private Segment scratch;
        private final String name;

        Segment(String name) {
            this.name = name;
        }

        private void add(int kind, Object arg) {
            if (count == kinds.length) {
                kinds = Arrays.copyOf(kinds, count * 2);
                args = Arrays.copyOf(args, count * 2);
            }
            kinds[count] = kind;
            args[count] = arg;
            count++;
        }

        private void clear() {
            Arrays.fill(args, 0, count, null);
            count = 0;
            valid = false;
        }

        /**
         * Draws this part: the recorded elements again when {@code key} matches what they were painted from, else
         * {@code paint} afresh while recording it. {@code key} is not kept; it is copied when a new paint is recorded.
         */
        void draw(GuiGraphicsExtractor graphics, Key key, Runnable paint) {
            int tick = LiveMapFeature.tickCount();
            if (valid && this.key.sameAs(key) && recording == null) {
                replay(graphics.guiRenderState);
                STATS[0]++;
                lastHitTick = tick;
                if (VERIFY) {
                    verify(paint, tick);
                }
                return;
            }
            if (recording != null) {
                // Never nest: a part drawn inside another's recording simply belongs to the outer one.
                paint.run();
                return;
            }
            clear();
            recording = this;
            swallow = false;
            depth = 0;
            try {
                paint.run();
                valid = true;
            } finally {
                recording = null;
                depth = 0;
            }
            this.key.copyFrom(key);
            builtTick = tick;
            STATS[1]++;
        }

        void invalidate() {
            clear();
        }

        private void replay(GuiRenderState state) {
            for (int i = 0; i < count; i++) {
                Object a = args[i];
                switch (kinds[i]) {
                    case ELEMENT -> state.addGuiElement((GuiElementRenderState) a);
                    case TEXT -> state.addText((GuiTextRenderState) a);
                    case ITEM -> state.addItem((GuiItemRenderState) a);
                    case PIP -> state.addPicturesInPictureState((PictureInPictureRenderState) a);
                    case BLIT -> state.addBlitToCurrentLayer((BlitRenderState) a);
                    case GLYPH -> state.addGlyphToCurrentLayer((GuiElementRenderState) a);
                    case UP -> state.up();
                    case NEXT_STRATUM -> state.nextStratum();
                    case BLUR -> state.blurBeforeThisStratum();
                    default -> {
                    }
                }
            }
        }

        private void verify(Runnable paint, int tick) {
            if (scratch == null) {
                scratch = new Segment(name + " (fresh)");
            }
            scratch.clear();
            recording = scratch;
            swallow = true;
            depth = 0;
            try {
                paint.run();
            } catch (RuntimeException e) {
                lastMismatch = name + ": the fresh paint threw " + e;
            } finally {
                recording = null;
                swallow = false;
                depth = 0;
            }
            STATS[2]++;
            if (!sameElements(this, scratch)) {
                STATS[builtTick == tick ? 3 : 4]++;
                if (builtTick != tick) {
                    lastMismatch = name + ": " + describeMismatch(this, scratch);
                } else {
                    lastSameTickMismatch = name + ": " + describeMismatch(this, scratch);
                }
            }
            scratch.clear();
        }
    }

    private static volatile String lastMismatch = "";
    private static volatile String lastSameTickMismatch = "";

    /** Test hook: what the last replay that differed within its own tick differed in, or "". */
    public static String lastSameTickMismatch() {
        return lastSameTickMismatch;
    }

    /** Test hook: what the last stale replay differed in, or "". */
    public static String lastMismatch() {
        return lastMismatch;
    }

    // ------------------------------------------------------------------------------------------------ verify

    private static boolean sameElements(Segment a, Segment b) {
        if (a.count != b.count) {
            return false;
        }
        for (int i = 0; i < a.count; i++) {
            if (a.kinds[i] != b.kinds[i] || !sameElement(a.args[i], b.args[i])) {
                return false;
            }
        }
        return true;
    }

    private static String describeMismatch(Segment a, Segment b) {
        if (a.count != b.count) {
            return "count " + a.count + " " + outline(a) + " vs fresh " + b.count + " " + outline(b);
        }
        for (int i = 0; i < a.count; i++) {
            if (a.kinds[i] != b.kinds[i] || !sameElement(a.args[i], b.args[i])) {
                return "element " + i + ": " + describe(a.args[i]) + " vs fresh " + describe(b.args[i]);
            }
        }
        return "";
    }

    /** The first elements of a segment, kind and class, for a mismatch report. */
    private static String outline(Segment s) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(s.count, 12); i++) {
            sb.append(i == 0 ? "" : ", ").append(s.kinds[i]).append(':')
                    .append(s.args[i] == null ? "-" : s.args[i].getClass().getSimpleName());
        }
        return sb.append(s.count > 12 ? ", ...]" : "]").toString();
    }

    private static String describe(Object o) {
        if (o instanceof GuiTextRenderState t) {
            StringBuilder sb = new StringBuilder("text");
            for (java.lang.reflect.Field f : textFields()) {
                try {
                    Object v = f.get(t);
                    sb.append(' ').append(v instanceof FormattedCharSequence seq ? "'" + plain(seq) + "'"
                            : v instanceof Integer i ? Integer.toHexString(i) : String.valueOf(v));
                } catch (IllegalAccessException e) {
                    sb.append(" ?");
                }
            }
            return sb.toString();
        }
        return String.valueOf(o);
    }

    static boolean sameElement(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.getClass() != b.getClass()) {
            return false;
        }
        if (a instanceof GuiTextRenderState ta) {
            // By reflection, every field but the lazily built layout: 26.2 made most of them private, and a release
            // jar runs remapped, so they are found by type rather than by name. Test-only (verify mode).
            GuiTextRenderState tb = (GuiTextRenderState) b;
            ta.ensurePrepared();
            tb.ensurePrepared();
            for (java.lang.reflect.Field f : textFields()) {
                try {
                    Object va = f.get(ta);
                    Object vb = f.get(tb);
                    boolean same = va instanceof FormattedCharSequence sa && vb instanceof FormattedCharSequence sb
                            ? sameText(sa, sb)
                            : va instanceof net.minecraft.client.gui.Font ? va == vb : Objects.equals(va, vb);
                    if (!same) {
                        return false;
                    }
                } catch (IllegalAccessException e) {
                    return false;
                }
            }
            return true;
        }
        GuiElementRenderState[] pa = com.killer560.hub.hud.GuiRects.partsOf(a);
        if (pa != null) {
            GuiElementRenderState[] pb = com.killer560.hub.hud.GuiRects.partsOf(b);
            if (pb == null || pa.length != pb.length) {
                return false;
            }
            for (int i = 0; i < pa.length; i++) {
                if (!Objects.equals(pa[i], pb[i])) {
                    return false;
                }
            }
            return true;
        }
        // Vanilla's rectangle, blit and item states are records: equals is value for value.
        return a.equals(b);
    }

    private static java.util.List<java.lang.reflect.Field> textFields;

    /** GuiTextRenderState's instance fields except its prepared glyph layout. */
    private static java.util.List<java.lang.reflect.Field> textFields() {
        if (textFields == null) {
            java.util.List<java.lang.reflect.Field> out = new ArrayList<>();
            for (java.lang.reflect.Field f : GuiTextRenderState.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        || net.minecraft.client.gui.Font.PreparedText.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                f.setAccessible(true);
                out.add(f);
            }
            textFields = out;
        }
        return textFields;
    }

    private static boolean sameText(FormattedCharSequence a, FormattedCharSequence b) {
        if (a == b) {
            return true;
        }
        List<Object> ca = chars(a);
        List<Object> cb = chars(b);
        return ca.equals(cb);
    }

    private static List<Object> chars(FormattedCharSequence seq) {
        List<Object> out = new ArrayList<>();
        seq.accept((index, style, codePoint) -> {
            out.add(style);
            out.add(codePoint);
            return true;
        });
        return out;
    }

    private static String plain(FormattedCharSequence seq) {
        StringBuilder sb = new StringBuilder();
        seq.accept((index, style, codePoint) -> {
            sb.appendCodePoint(codePoint);
            return true;
        });
        return sb.toString();
    }

}

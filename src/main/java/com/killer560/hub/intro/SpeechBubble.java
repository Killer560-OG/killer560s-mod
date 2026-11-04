package com.killer560.hub.intro;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.hud.GuiRects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * The tour's speech bubble (killer560, 2026-11-01: "make the text box more dynamic and fit the mod", then "less square"):
 * a rounded, theme-colored bubble with a curved tail that reaches for the guide's head and follows him, a soft shadow,
 * a squash-and-stretch pop-in, a slow float, a smooth resize between scenes, and its text typed in with key words in the
 * theme's accent.
 *
 * <p>The shape is rasterized here: every row of the rounded rectangle and the tail (a quadratic curve that narrows to a
 * point) is unioned into spans and drawn as fills, in device pixels ({@link #res} rows per GUI unit, the real pixel
 * density, so the curves are as smooth as the screen allows at every GUI scale). Rows that come out the same are
 * merged, and the whole bubble goes out as one {@link GuiRects} batch per layer.
 *
 * <p>Text markup: {@code {words}} draws in the accent color, {@code [key]} as a key cap, {@code <words>} in red (the
 * cheat-version warning), {@code \n} starts a new paragraph.
 */
final class SpeechBubble {

    /** Padding inside the bubble, wide enough that no line meets a curved corner. */
    static int padX() {
        return 15;
    }

    static int padY() {
        return 10;
    }
    static final int LINE_H = 11;
    static final int RADIUS = 22;
    /** Characters typed per second. */
    private static final float TYPE_CPS = 60f;
    static final long POP_NANOS = 300_000_000L;
    static final long RESIZE_NANOS = 260_000_000L;

    /** A run of text in one style. */
    record Seg(String text, int style) {
        static final int PLAIN = 0;
        static final int HI = 1;
        static final int KEY = 2;
        static final int WARN = 3;
    }

    /** One laid-out line: its segments and where each starts (x from the text's left edge). */
    record Line(List<Seg> segs, int[] xs, int width) {
    }

    private String source = "";
    private List<Line> lines = List.of();
    private int textW;
    private int chars;
    private long typeStart;

    /** Parses the markup into styled words and wraps them to {@code maxW}. */
    void setText(String text, int maxW, long now) {
        if (text.equals(source) && !lines.isEmpty()) {
            return;
        }
        source = text;
        typeStart = now;
        Font font = MenuStyle.font();
        List<Line> out = new ArrayList<>();
        int widest = 0;
        int total = 0;
        for (String para : text.split("\n", -1)) {
            List<Seg> words = parse(para);
            List<Seg> cur = new ArrayList<>();
            List<Integer> curX = new ArrayList<>();
            int x = 0;
            for (Seg w : words) {
                boolean space = w.text.equals(" ");
                int ww = width(font, w);
                if (space && cur.isEmpty()) {
                    continue;
                }
                if (!space && x + ww > maxW && !cur.isEmpty()) {
                    // drop a trailing space before wrapping
                    if (cur.get(cur.size() - 1).text.equals(" ")) {
                        x -= width(font, cur.remove(cur.size() - 1));
                        curX.remove(curX.size() - 1);
                    }
                    out.add(line(cur, curX, x));
                    widest = Math.max(widest, x);
                    cur = new ArrayList<>();
                    curX = new ArrayList<>();
                    x = 0;
                }
                cur.add(w);
                curX.add(x);
                x += ww;
                total += w.text.length();
            }
            if (!cur.isEmpty() && cur.get(cur.size() - 1).text.equals(" ")) {
                x -= width(font, cur.remove(cur.size() - 1));
                curX.remove(curX.size() - 1);
            }
            out.add(line(cur, curX, x));
            widest = Math.max(widest, x);
        }
        lines = out;
        textW = widest;
        chars = total;
    }

    private static Line line(List<Seg> segs, List<Integer> xs, int width) {
        int[] a = new int[xs.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = xs.get(i);
        }
        return new Line(List.copyOf(segs), a, width);
    }

    private static int width(Font font, Seg s) {
        int w = font.width(s.text);
        return s.style == Seg.KEY ? w + 8 : w;
    }

    /** Splits a paragraph into words, spaces and marked runs (a marked run is never split across lines). */
    static List<Seg> parse(String para) {
        List<Seg> out = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        int i = 0;
        while (i < para.length()) {
            char c = para.charAt(i);
            int close = -1;
            int style = Seg.PLAIN;
            if (c == '{') {
                close = para.indexOf('}', i + 1);
                style = Seg.HI;
            } else if (c == '[') {
                close = para.indexOf(']', i + 1);
                style = Seg.KEY;
            } else if (c == '<') {
                close = para.indexOf('>', i + 1);
                style = Seg.WARN;
            }
            if (close > i) {
                if (word.length() > 0) {
                    out.add(new Seg(word.toString(), Seg.PLAIN));
                    word.setLength(0);
                }
                // A marked run of several words wraps as words, each in the run's style.
                String inner = para.substring(i + 1, close);
                if (style == Seg.KEY) {
                    out.add(new Seg(inner, style));
                } else {
                    String[] parts = inner.split(" ", -1);
                    for (int p = 0; p < parts.length; p++) {
                        if (p > 0) {
                            out.add(new Seg(" ", Seg.PLAIN));
                        }
                        if (!parts[p].isEmpty()) {
                            out.add(new Seg(parts[p], style));
                        }
                    }
                }
                i = close + 1;
                continue;
            }
            if (c == ' ') {
                if (word.length() > 0) {
                    out.add(new Seg(word.toString(), Seg.PLAIN));
                    word.setLength(0);
                }
                out.add(new Seg(" ", Seg.PLAIN));
            } else {
                word.append(c);
            }
            i++;
        }
        if (word.length() > 0) {
            out.add(new Seg(word.toString(), Seg.PLAIN));
        }
        // Glue punctuation that follows a marked run onto it ("{Mod}!" must not wrap the "!" alone): merge a plain
        // word with no space before it into the previous segment's line by keeping order; wrapping treats them apart,
        // which is acceptable for single characters, so nothing more is done here.
        return out;
    }

    int textWidth() {
        return textW;
    }

    int textHeight() {
        return Math.max(1, lines.size()) * LINE_H - 2;
    }

    /** Plain text of what the bubble says (for the testkit). */
    String plainText() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                b.append('\n');
            }
            for (Seg s : lines.get(i).segs) {
                b.append(s.text);
            }
        }
        return b.toString();
    }

    boolean typed(long now) {
        return typedChars(now) >= chars;
    }

    void finishTyping() {
        typeStart = Long.MIN_VALUE / 4;
    }

    private int typedChars(long now) {
        if (instant()) {
            return chars;
        }
        double sec = (now - typeStart) / 1_000_000_000.0;
        return sec <= 0 ? 0 : (int) Math.min(chars, sec * TYPE_CPS);
    }

    /** The rectangle as drawn now (animated toward {@link #setTarget}). */
    private float x;
    private float y;
    private float w;
    private float h;
    private float fx;
    private float fy;
    private float fw;
    private float fh;
    private float tx;
    private float ty;
    private float tw;
    private float th;
    private long resizeStart = Long.MIN_VALUE;
    private boolean hasRect;
    private long popStart = Long.MIN_VALUE;

    void setTarget(float nx, float ny, float nw, float nh, long now) {
        if (!hasRect) {
            x = fx = tx = nx;
            y = fy = ty = ny;
            w = fw = tw = nw;
            h = fh = th = nh;
            hasRect = true;
            return;
        }
        if (nx == tx && ny == ty && nw == tw && nh == th) {
            return;
        }
        rect(now);
        fx = x;
        fy = y;
        fw = w;
        fh = h;
        tx = nx;
        ty = ny;
        tw = nw;
        th = nh;
        resizeStart = now;
    }

    /** Snaps to the target (a new pop-in starts there). */
    void snap() {
        x = fx = tx;
        y = fy = ty;
        w = fw = tw;
        h = fh = th;
        resizeStart = Long.MIN_VALUE;
    }

    void pop(long now) {
        popStart = now;
        snap();
    }

    /** Animations: Off (or the testkit's instant clock): the bubble appears at once with all its text. */
    static boolean instant() {
        return com.killer560.hub.gui.MenuAnim.durationNanos(100, false) == 0L;
    }

    boolean settled(long now) {
        if (instant()) {
            return true;
        }
        return now - popStart >= POP_NANOS && (resizeStart == Long.MIN_VALUE || now - resizeStart >= RESIZE_NANOS);
    }

    private void rect(long now) {
        if (resizeStart == Long.MIN_VALUE || now - resizeStart >= RESIZE_NANOS) {
            x = tx;
            y = ty;
            w = tw;
            h = th;
            return;
        }
        float t = (now - resizeStart) / (float) RESIZE_NANOS;
        float e = 1 - (1 - t) * (1 - t) * (1 - t);
        x = fx + (tx - fx) * e;
        y = fy + (ty - fy) * e;
        w = fw + (tw - fw) * e;
        h = fh + (th - fh) * e;
    }

    /** The bubble's float (GUI units). */
    static float floatY(long now) {
        return (float) Math.sin(now / 1_000_000_000.0 * 1.6 + 1.0) * 1.1f;
    }

    private final GuiRects rects = GuiRects.reusable();

    /** Where the tail's point was drawn last (GUI units of the screen; read by the testkit). */
    float lastTipX = Float.NaN;
    float lastTipY = Float.NaN;

    /**
     * Draws the bubble with its tail coming from his mouth at ({@code mouthX}, {@code mouthY}) (killer560, 2026-11-01:
     * "the tail must come from the character's mouth"), and its text. {@code inside} draws anything that must move with
     * the bubble (its buttons), under the bubble's pose.
     */
    void draw(GuiGraphicsExtractor g, long now, float mouthX, float mouthY, Runnable inside) {
        rect(now);
        float pt = Math.min(1f, Math.max(0f, (now - popStart) / (float) POP_NANOS));
        if (popStart == Long.MIN_VALUE) {
            pt = 1f;
        }
        if (instant()) {
            pt = 1f;
        }
        // easeOutBack for the size, a squash that is wide first and tall after (ends exactly at 1, 1)
        float c1 = 1.70158f;
        float c3 = c1 + 1;
        float s = pt >= 1f ? 1f : 1 + c3 * (float) Math.pow(pt - 1, 3) + c1 * (float) Math.pow(pt - 1, 2);
        float squash = (float) (Math.sin(Math.PI * pt) * (1 - pt) * 0.22);
        float sx = Math.max(0.01f, s * (1 + squash));
        float sy = Math.max(0.01f, s * (1 - squash));
        int alpha = Math.round(Math.min(1f, pt * 3f) * 255);
        if (alpha <= 0) {
            return;
        }
        float fl = floatY(now);

        // The tail's point sits at the mouth: the shape's point is 1.5 units short of it on the bubble's side, so the
        // border drawn around the point ends right at the mouth.
        boolean guideLeft = mouthX < x + w / 2f;
        float tipX = guideLeft ? mouthX + 1.5f : mouthX - 1.5f;
        float tipY = mouthY;
        lastTipX = mouthX;
        lastTipY = mouthY;

        Minecraft mc = Minecraft.getInstance();
        float units = (float) mc.getWindow().getGuiScale() * com.killer560.hub.hud.AutoScale.openScreenFactor();
        int res = Math.max(1, Math.min(4, Math.round(units)));

        g.pose().pushMatrix();
        // Scale about the tail's tip: the bubble grows out of what he is saying.
        g.pose().translate(tipX, tipY + fl);
        g.pose().scale(sx, sy);
        g.pose().translate(-tipX, -tipY);

        // Round (killer560 picked it over the first, squarer bubble, 2026-11-01): pill-round corners, a curved tail.
        float radius = Math.min(h / 2f - 1f, RADIUS);
        Shape shape = new Shape(x, y, w, h, radius, guideLeft, tipX, tipY - fl, 18f, 9.5f);
        int fillTop = MenuStyle.CARD_TOP;
        int fillBottom = MenuStyle.CARD_BOTTOM;
        if (MenuStyle.LIGHT) {
            fillTop = 0xFFFFFFFF;
            fillBottom = MenuStyle.CARD_TOP;
        }
        int border = MenuStyle.OR;
        int shadow = 0x000000;

        g.pose().pushMatrix();
        g.pose().scale(1f / res, 1f / res);
        GuiRects r = rects.start(g);
        // Soft shadow: two offset, grown layers, faint.
        layer(r, shape, res, 2.5f, 1.5f, 3.2f, mul(0x26000000 | shadow, alpha), 0);
        layer(r, shape, res, 1.5f, 1.0f, 2.0f, mul(0x30000000 | shadow, alpha), 0);
        // Border (accent), then the fill as a gentle vertical gradient, then a highlight just inside the top.
        // A soft glow in the accent around the edge, then the border.
        layer(r, shape, res, 0f, 0f, 3.2f, mul(MenuStyle.alpha(border, 0x22), alpha), 0);
        layer(r, shape, res, 0f, 0f, 2.3f, mul(MenuStyle.alpha(border, 0x3A), alpha), 0);
        layer(r, shape, res, 0f, 0f, 1.5f, mul(border, alpha), 0);
        layerGradient(r, shape, res, mul(fillTop, alpha), mul(fillBottom, alpha), y, y + h);
        int hi = MenuStyle.LIGHT ? 0x50FFFFFF : 0x22FFFFFF;
        r.fill(Math.round((x + radius) * res), Math.round((y + 1.25f) * res),
                Math.round((x + w - radius) * res), Math.round((y + 1.25f) * res) + Math.max(1, res / 2), mul(hi, alpha));
        r.submit();
        g.pose().popMatrix();

        // Text, typed in.
        if (alpha >= 200) {
            drawText(g, now, Math.round(x + padX()), Math.round(y + padY()), alpha);
            if (inside != null) {
                inside.run();
            }
        }
        g.pose().popMatrix();
    }

    private void drawText(GuiGraphicsExtractor g, long now, int left, int top, int alpha) {
        Font font = MenuStyle.font();
        int budget = typedChars(now);
        int text = MenuStyle.TXT;
        int hi = MenuStyle.OR;
        int warn = MenuStyle.LIGHT ? 0xFFC03030 : MenuStyle.RED_TITLE;
        boolean shadow = MenuStyle.TEXT_SHADOW;
        int yy = top;
        for (Line line : lines) {
            for (int i = 0; i < line.segs.size() && budget > 0; i++) {
                Seg s = line.segs.get(i);
                String shown = s.text.length() <= budget ? s.text : s.text.substring(0, budget);
                budget -= s.text.length();
                int sx = left + line.xs[i];
                switch (s.style) {
                    case Seg.HI -> g.text(font, shown, sx, yy, mul(hi, alpha), shadow);
                    case Seg.WARN -> g.text(font, shown, sx, yy, mul(warn, alpha), shadow);
                    case Seg.KEY -> {
                        int kw = font.width(s.text) + 8;
                        GuiRects r = GuiRects.begin(g);
                        r.fill(sx, yy - 3, sx + kw - 1, yy + 10, mul(MenuStyle.OR_DARK, alpha));
                        r.fill(sx + 1, yy - 2, sx + kw - 2, yy + 8, mul(MenuStyle.WELL, alpha));
                        r.fill(sx + 1, yy + 8, sx + kw - 2, yy + 9, mul(MenuStyle.OR, alpha));
                        r.submit();
                        g.text(font, shown, sx + 4, yy, mul(MenuStyle.LIGHT ? 0xFFFFFFFF : hi, alpha), false);
                    }
                    default -> g.text(font, shown, sx, yy, mul(text, alpha), shadow);
                }
            }
            if (budget <= 0) {
                break;
            }
            yy += LINE_H;
        }
    }

    private static final GuiRects BOX_RECTS = GuiRects.reusable();

    /**
     * A rounded box in the bubble's own look - soft shadow, accent border, the theme's card gradient (or the accent
     * itself for a primary or chosen box) - drawn in device pixels like the bubble. The tour's buttons, its time box and
     * its page dots all use it, so they match the bubble (killer560, 2026-11-01: "the bottom buttons don't fit the
     * bubble").
     */
    static void roundBox(GuiGraphicsExtractor g, float x, float y, float w, float h, float radius, int fillTop,
                         int fillBottom, int border, float borderW, boolean shadow) {
        Minecraft mc = Minecraft.getInstance();
        float units = (float) mc.getWindow().getGuiScale() * com.killer560.hub.hud.AutoScale.openScreenFactor();
        int res = Math.max(1, Math.min(4, Math.round(units)));
        Shape shape = new Shape(x, y, w, h, radius);
        g.pose().pushMatrix();
        g.pose().scale(1f / res, 1f / res);
        GuiRects r = BOX_RECTS.start(g);
        if (shadow) {
            layer(r, shape, res, 1.0f, 1.2f, 0.8f, 0x30000000, 0);
        }
        if (borderW > 0) {
            layer(r, shape, res, 0f, 0f, borderW, border, 0);
        }
        layerGradient(r, shape, res, fillTop, fillBottom, y, y + h);
        r.submit();
        g.pose().popMatrix();
    }

    /** The bubble's fill colors under the current theme: {top, bottom}. */
    static int[] fill() {
        return MenuStyle.LIGHT ? new int[]{0xFFFFFFFF, MenuStyle.CARD_TOP} : new int[]{MenuStyle.CARD_TOP, MenuStyle.CARD_BOTTOM};
    }

    private static int mul(int argb, int alpha) {
        int a = (argb >>> 24) * alpha / 255;
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** One solid layer: the shape grown by {@code grow} units, shifted by (dx, dy) units. Equal rows are merged. */
    private static void layer(GuiRects r, Shape s, int res, float dx, float dy, float grow, int color, int unused) {
        int top = (int) Math.floor((s.minY() - grow) * res);
        int bottom = (int) Math.ceil((s.maxY() + grow) * res);
        int runStart = top;
        float[] runSpans = null;
        for (int row = top; row <= bottom; row++) {
            float[] spans = row < bottom ? s.spans((row + 0.5f) / res, grow) : null;
            if (runSpans != null && !sameSpans(runSpans, spans, res)) {
                emit(r, runSpans, runStart, row, res, dx, dy, color);
                runSpans = null;
            }
            if (runSpans == null && spans != null && spans.length > 0) {
                runSpans = spans;
                runStart = row;
            }
        }
    }

    /** The fill: like {@link #layer} with grow 0, its color graded from {@code top} to {@code bottom} in 12 bands. */
    private static void layerGradient(GuiRects r, Shape s, int res, int topColor, int bottomColor, float y0, float y1) {
        int top = (int) Math.floor(s.minY() * res);
        int bottom = (int) Math.ceil(s.maxY() * res);
        int runStart = top;
        float[] runSpans = null;
        int runColor = 0;
        for (int row = top; row <= bottom; row++) {
            float[] spans = row < bottom ? s.spans((row + 0.5f) / res, 0f) : null;
            float t = Math.max(0f, Math.min(1f, ((row + 0.5f) / res - y0) / Math.max(1f, y1 - y0)));
            int color = MenuStyle.mix(topColor, bottomColor, Math.round(t * 12) / 12f);
            if (runSpans != null && (!sameSpans(runSpans, spans, res) || color != runColor)) {
                emit(r, runSpans, runStart, row, res, 0, 0, runColor);
                runSpans = null;
            }
            if (runSpans == null && spans != null && spans.length > 0) {
                runSpans = spans;
                runStart = row;
                runColor = color;
            }
        }
    }

    private static boolean sameSpans(float[] a, float[] b, int res) {
        if (b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (Math.round(a[i] * res) != Math.round(b[i] * res)) {
                return false;
            }
        }
        return true;
    }

    private static void emit(GuiRects r, float[] spans, int row0, int row1, int res, float dx, float dy, int color) {
        int ox = Math.round(dx * res);
        int oy = Math.round(dy * res);
        for (int i = 0; i + 1 < spans.length; i += 2) {
            int a = Math.round(spans[i] * res);
            int b = Math.round(spans[i + 1] * res);
            if (b > a) {
                r.fill(a + ox, row0 + oy, b + ox, row1 + oy, color);
            }
        }
    }

    /**
     * The bubble outline in GUI units: a rounded rectangle unioned with a tail, a quadratic curve from the side facing
     * the guide to a point near his face, drooping a little like a comic-book tail.
     */
    static final class Shape {
        final float x;
        final float y;
        final float w;
        final float h;
        final float r;
        final float[] cx;
        final float[] cy;
        final float[] nx;
        final float[] ny;
        final float[] hw;
        static final int STEPS = 18;

        Shape(float x, float y, float w, float h, float r, boolean guideLeft, float tipX, float tipY, float droop,
              float baseHalf) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.r = Math.min(r, Math.min(w, h) / 2f);
            // Base: inside the bubble's facing side, at the tip's height clamped to the straight part of that side.
            float edge = guideLeft ? x : x + w;
            float baseX = guideLeft ? edge + 6 + r * 0.3f : edge - 6 - r * 0.3f;
            float baseY = Math.max(y + this.r + 7, Math.min(y + h - this.r - 7, tipY + 10));
            float midX = (baseX + tipX) / 2f;
            float midY = (baseY + tipY) / 2f + droop; // the droop
            cx = new float[STEPS + 1];
            cy = new float[STEPS + 1];
            nx = new float[STEPS + 1];
            ny = new float[STEPS + 1];
            hw = new float[STEPS + 1];
            for (int i = 0; i <= STEPS; i++) {
                float t = i / (float) STEPS;
                float a = (1 - t) * (1 - t);
                float b = 2 * (1 - t) * t;
                float c = t * t;
                cx[i] = a * baseX + b * midX + c * tipX;
                cy[i] = a * baseY + b * midY + c * tipY;
                float dx = 2 * (1 - t) * (midX - baseX) + 2 * t * (tipX - midX);
                float dy = 2 * (1 - t) * (midY - baseY) + 2 * t * (tipY - midY);
                float len = (float) Math.max(1e-4, Math.sqrt(dx * dx + dy * dy));
                nx[i] = -dy / len;
                ny[i] = dx / len;
                hw[i] = baseHalf * (float) Math.pow(1 - t, 1.25) + 0.3f;
            }
        }

        /** A rounded rectangle with no tail (the tour's buttons and its small boxes). */
        Shape(float x, float y, float w, float h, float r) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.r = Math.min(r, Math.min(w, h) / 2f);
            cx = new float[0];
            cy = new float[0];
            nx = new float[0];
            ny = new float[0];
            hw = new float[0];
        }

        float minY() {
            float m = y;
            for (float v : cy) {
                m = Math.min(m, v - 8);
            }
            return m;
        }

        float maxY() {
            float m = y + h;
            for (float v : cy) {
                m = Math.max(m, v + 8);
            }
            return m;
        }

        /** Sorted, merged [from, to) spans of the shape grown by {@code g} units, on the line at height {@code yc}. */
        float[] spans(float yc, float g) {
            List<float[]> parts = new ArrayList<>(4);
            // rounded rectangle grown by g
            float rx0 = x - g;
            float ry0 = y - g;
            float rx1 = x + w + g;
            float ry1 = y + h + g;
            float rr = r + g;
            if (yc >= ry0 && yc < ry1) {
                float inset = 0f;
                float d = -1f;
                if (yc < ry0 + rr) {
                    d = ry0 + rr - yc;
                } else if (yc > ry1 - rr) {
                    d = yc - (ry1 - rr);
                }
                if (d > 0) {
                    inset = rr - (float) Math.sqrt(Math.max(0f, rr * rr - d * d));
                }
                parts.add(new float[]{rx0 + inset, rx1 - inset});
            }
            // tail polygon grown by g: left edge out, right edge back
            int n = cx.length;
            if (n == 0) {
                return parts.isEmpty() ? null : new float[]{parts.get(0)[0], parts.get(0)[1]};
            }
            float[] px = new float[n * 2];
            float[] py = new float[n * 2];
            for (int i = 0; i < n; i++) {
                float k = hw[i] + g;
                px[i] = cx[i] + nx[i] * k;
                py[i] = cy[i] + ny[i] * k;
                px[2 * n - 1 - i] = cx[i] - nx[i] * k;
                py[2 * n - 1 - i] = cy[i] - ny[i] * k;
            }
            List<Float> xs = new ArrayList<>(8);
            int m = 2 * n;
            for (int i = 0; i < m; i++) {
                float ax = px[i];
                float ay = py[i];
                float bx = px[(i + 1) % m];
                float by = py[(i + 1) % m];
                if ((ay <= yc && by > yc) || (by <= yc && ay > yc)) {
                    xs.add(ax + (yc - ay) / (by - ay) * (bx - ax));
                }
            }
            xs.sort(null);
            for (int i = 0; i + 1 < xs.size(); i += 2) {
                parts.add(new float[]{xs.get(i), xs.get(i + 1)});
            }
            if (parts.isEmpty()) {
                return null;
            }
            parts.sort((p, q) -> Float.compare(p[0], q[0]));
            List<float[]> merged = new ArrayList<>(parts.size());
            for (float[] p : parts) {
                if (!merged.isEmpty() && p[0] <= merged.get(merged.size() - 1)[1]) {
                    float[] last = merged.get(merged.size() - 1);
                    last[1] = Math.max(last[1], p[1]);
                } else {
                    merged.add(new float[]{p[0], p[1]});
                }
            }
            float[] out = new float[merged.size() * 2];
            for (int i = 0; i < merged.size(); i++) {
                out[2 * i] = merged.get(i)[0];
                out[2 * i + 1] = merged.get(i)[1];
            }
            return out;
        }
    }

    // ---- read by the screen and the testkit ------------------------------------------------------------------------

    float x() {
        return x;
    }

    float y() {
        return y;
    }

    float w() {
        return w;
    }

    float h() {
        return h;
    }
}

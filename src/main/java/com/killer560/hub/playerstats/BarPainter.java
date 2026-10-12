package com.killer560.hub.playerstats;

import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.playerstats.PlayerStatsConfig.BarStyle;
import com.killer560.hub.playerstats.StatElements.Readout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Draws the Health and Mana Bars in the Sleek and Bold styles (killer560, 2026-10-12: "Look at how skyblocker does theirs
 * with how they actually look custom and try to make your own type of style of it"), and keeps their animation.
 * <p>
 * Skyblocker's fancy bars (read, not copied) are two nine-slice sprites, a 7-pixel back and a 5-pixel fill tinted per
 * stat, a 9-pixel icon sprite at the left, the number drawn outlined just above the bar, the overflow (absorption,
 * overflow mana) as a second tinted fill over the first, and no easing. Ours draws everything from coloured quads (so
 * there is no texture to upload and it scales to any length and thickness the editor gives it), uses the real item for
 * the icon, and adds what Skyblocker does not have: easing, a damage "ghost" and the mod's orange rim on Bold.
 * <p>
 * Everything happens inside the element's box: the icon takes a square at its left, a Beside number takes room at its
 * right, and the track is what is left, so the HUD editor box, the Predefined layout and an edge drag all measure the
 * same rectangle they always did.
 * <p>
 * Per frame nothing here is allocated except the GUI render states each draw call hands to vanilla (one batch for all
 * the quads of a bar, the icon item, the text): the numbers' strings are built only when a value changes
 * ({@link StatElements}), the quads go through one reused {@link GuiRects}, the animation lives in arrays.
 */
public final class BarPainter {

    private BarPainter() {
    }

    private static final int N = Readout.values().length;

    // ---- animation, per readout (render thread) ----
    private static final float[] shown = new float[N];
    private static final float[] shownOver = new float[N];
    private static final float[] ghost = new float[N];
    private static final long[] ghostHoldUntil = new long[N];
    private static final long[] lastNanos = new long[N];

    /** How long the ghost holds still after a hit before it drains, and how fast it drains (share of the bar per s). */
    static final long GHOST_HOLD_NANOS = 400_000_000L;
    static final float GHOST_DRAIN_PER_SECOND = 0.9f;
    /** Easing rate: the gap to the target shrinks by e every 1/RATE seconds (about 99% in a third of a second). */
    static final float EASE_RATE = 14f;
    /** Not drawn for this long: the next draw starts at the value instead of easing from a stale one. */
    private static final long STALE_NANOS = 1_000_000_000L;

    // ---- what the last draw of each bar measured, in the bar's own units; read by the testkit ----
    /** Length of the track (the part of the box that fills). */
    public static final int[] lastTrack = new int[N];
    /** Filled length of the main value. */
    public static final int[] lastFill = new int[N];
    /** Length of the overflow layer (absorption on health, overflow mana on mana); 0 when none is drawn. */
    public static final int[] lastOverflow = new int[N];
    /** Length of the damage ghost past the fill; 0 when none is drawn. */
    public static final int[] lastGhost = new int[N];
    /** System.nanoTime of the last draw. */
    public static final long[] lastDrawNanos = new long[N];
    /** Units from each edge of the element's box to the fill area (icon, frame and Beside number on their sides). */
    public static final int[] lastInsetLeft = new int[N];
    public static final int[] lastInsetTop = new int[N];
    public static final int[] lastInsetRight = new int[N];
    public static final int[] lastInsetBottom = new int[N];
    /** The element's box as drawn, in GUI units through the current pose: {x0, y0, x1, y1} per readout, 4 apart. */
    public static final float[] lastScreenBox = new float[N * 4];

    private static final GuiRects RECTS = GuiRects.reusable();

    // Icons: real items, made once.
    private static ItemStack[] icons;

    /** Fill fraction 0..1, overflow fraction 0..1, from the last {@link #animate} call (render thread scratch). */
    private static float outFill;
    private static float outOver;
    private static float outGhost;

    /**
     * Advances bar {@code r}'s animation to {@code target} / {@code over} (fractions of the bar, 0..1) and leaves the
     * fractions to draw in {@link #outFill}, {@link #outOver}, {@link #outGhost}. {@code snap} draws the value as it is
     * (the HUD editor, smoothing off).
     */
    static void animate(Readout r, float target, float over, boolean smooth, boolean ghostOn, boolean snap, long now) {
        int i = r.ordinal();
        long last = lastNanos[i];
        lastNanos[i] = now;
        boolean stale = last == 0L || now - last > STALE_NANOS || now < last;
        float dt = stale ? 0f : (now - last) / 1_000_000_000f;
        float prev = shown[i];
        if (stale || snap || !smooth) {
            shown[i] = target;
            shownOver[i] = over;
        } else {
            float k = 1f - (float) Math.exp(-EASE_RATE * dt);
            shown[i] = ease(prev, target, k);
            shownOver[i] = ease(shownOver[i], over, k);
        }
        if (!ghostOn || stale || snap) {
            ghost[i] = shown[i];
            ghostHoldUntil[i] = 0L;
        } else {
            if (target < prev - 0.002f) {
                // A hit: the ghost marks where the bar was and holds there a moment.
                ghost[i] = Math.max(ghost[i], prev);
                ghostHoldUntil[i] = now + GHOST_HOLD_NANOS;
            }
            if (now >= ghostHoldUntil[i]) {
                ghost[i] -= GHOST_DRAIN_PER_SECOND * dt;
            }
            if (ghost[i] < shown[i]) {
                ghost[i] = shown[i];
            }
        }
        outFill = shown[i];
        outOver = shownOver[i];
        outGhost = ghost[i];
    }

    private static float ease(float from, float to, float k) {
        float v = from + (to - from) * k;
        return Math.abs(to - v) < 0.0005f ? to : v;
    }

    /**
     * Draws bar {@code r} in {@code style} in the box {@code (x, y, w, h)}. {@code fill} and {@code over} are fractions of
     * the bar (0..1): the value, and the overflow layer's length (0 for none) that sits straight after the value and
     * ends at the bar's end at most. {@code value} is the number to show (or null), {@code besideReserve} the room kept
     * for it after the bar when it goes Beside (0 = Inside).
     */
    static void draw(GuiGraphicsExtractor g, PlayerStatsConfig cfg, Readout r, BarStyle style, int x, int y, int w,
                     int h, float fill, float over, int overColor, String value, int besideReserve, float textScale,
                     boolean preview, long now) {
        animate(r, fill, over, cfg.isBarSmooth(), cfg.isBarDamageGhost(), preview, now);
        int i = r.ordinal();
        lastDrawNanos[i] = now;

        int iconRoom = iconRoom(cfg, w, h);
        boolean icon = iconRoom > 0;
        int tx0 = x + iconRoom;
        int tx1 = x + w - besideReserve;
        if (tx1 - tx0 < 12) {
            // Never reached through StatElements, which keeps a 24-unit track before it puts a number beside.
            besideReserve = 0;
            value = null;
            tx1 = x + w;
        }
        int tw = tx1 - tx0;
        boolean bold = style == BarStyle.BOLD;
        int frame = bold ? (h >= 8 ? 2 : h >= 5 ? 1 : 0) : (h >= 5 ? 1 : 0);
        boolean round = cfg.isBarRounded() && frame > 0 && tw >= 6;
        int ix0 = tx0 + frame;
        int ix1 = tx1 - frame;
        int iy0 = y + frame;
        int iy1 = y + h - frame;
        int iw = Math.max(0, ix1 - ix0);
        int ih = Math.max(0, iy1 - iy0);
        int color = cfg.getReadoutColor(r);

        int filled = Math.round(iw * clamp01(outFill));
        int ghostEnd = Math.round(iw * clamp01(outGhost));
        int seg = 0;
        int segX = 0;
        if (outOver > 0f) {
            seg = Math.max(1, Math.min(iw, Math.round(iw * clamp01(outOver))));
            segX = Math.min(filled, iw - seg);
        }
        lastTrack[i] = iw;
        lastFill[i] = filled;
        lastOverflow[i] = seg;
        lastGhost[i] = Math.max(0, ghostEnd - filled);
        lastInsetLeft[i] = ix0 - x;
        lastInsetTop[i] = iy0 - y;
        lastInsetRight[i] = x + w - ix1;
        lastInsetBottom[i] = y + h - iy1;
        var pm = g.pose();
        lastScreenBox[i * 4] = pm.m00() * x + pm.m20();
        lastScreenBox[i * 4 + 1] = pm.m11() * y + pm.m21();
        lastScreenBox[i * 4 + 2] = pm.m00() * (x + w) + pm.m20();
        lastScreenBox[i * 4 + 3] = pm.m11() * (y + h) + pm.m21();

        GuiRects q = RECTS.start(g);
        // ---- frame ----
        if (frame > 0) {
            int outer = bold ? 0xFF000000 : 0xF0141010;
            ring(q, tx0, y, tx1, y + h, outer, round);
            if (frame == 2) {
                // The mod's orange, darkened so the fill stays the brightest thing on the bar.
                int rim = 0xFFB85C0A;
                ring(q, tx0 + 1, y + 1, tx1 - 1, y + h - 1, rim, false);
                if (round) {
                    // The rim's corners go black, which turns the 2-unit corner into a curve.
                    q.fill(tx0 + 1, y + 1, tx0 + 2, y + 2, outer);
                    q.fill(tx1 - 2, y + 1, tx1 - 1, y + 2, outer);
                    q.fill(tx0 + 1, y + h - 2, tx0 + 2, y + h - 1, outer);
                    q.fill(tx1 - 2, y + h - 2, tx1 - 1, y + h - 1, outer);
                }
            }
        }
        if (iw > 0 && ih > 0) {
            // ---- empty track ----
            q.fill(ix0, iy0, ix1, iy1, cfg.getBarBackground());
            // ---- ghost: where the bar was before the hit, draining towards the value ----
            if (ghostEnd > filled) {
                q.fill(ix0 + filled, iy0, ix0 + ghostEnd, iy1, ghostColor(color));
            }
            // ---- value ----
            if (filled > 0) {
                shade(q, ix0, iy0, ix0 + filled, iy1, color, bold);
            }
            // ---- overflow layer ----
            if (seg > 0) {
                shade(q, ix0 + segX, iy0, ix0 + segX + seg, iy1, overColor, bold);
            }
            boolean deep = ih >= 4;
            int lit = Math.max(filled, seg > 0 ? segX + seg : 0);
            // ---- light top edge on what is filled ----
            if (deep && lit > 0) {
                q.fill(ix0, iy0 + 1, ix0 + lit, iy0 + 2, bold ? 0x80FFFFFF : 0x60FFFFFF);
            }
            // ---- inner shadow: the track sits below the frame ----
            if (deep) {
                q.fill(ix0, iy0, ix1, iy0 + 1, 0x70000000);
                q.fill(ix0, iy0 + 1, ix0 + 1, iy1, 0x40000000);
                q.fill(ix0 + 1, iy1 - 1, ix1, iy1, 0x30000000);
            }
            // ---- Bold: quarter notches ----
            if (bold && iw >= 24) {
                for (int k = 1; k < 4; k++) {
                    int nx = ix0 + iw * k / 4;
                    q.fill(nx, iy0 + 1, nx + 1, iy1, 0x48000000);
                }
            }
        }
        q.submit();

        if (icon) {
            drawIcon(g, r, x, y, h);
        }
        if (value != null) {
            Font font = Minecraft.getInstance().font;
            if (besideReserve > 0) {
                StatElements.drawAt(g, font, value, tx1 + StatElements.BESIDE_GAP, y, h, textScale, color | 0xFF000000);
            } else {
                StatElements.drawCentred(g, font, value, tx0, y, tw, h, textScale, 0xFFFFFFFF);
            }
        }
    }

    /** Units the icon takes at the box's left (its square plus a 2-unit gap), 0 when icons are off or the bar is too
     *  thin (under 6) or too short (the track keeps at least 2h + 20) for one. */
    static int iconRoom(PlayerStatsConfig cfg, int w, int h) {
        return cfg.isBarIcons() && h >= 6 && w >= 3 * h + 20 ? h + 2 : 0;
    }

    /** A one-unit ring just inside {@code [x0,x1) x [y0,y1)}; {@code round} leaves its four corner units out. */
    private static void ring(GuiRects q, int x0, int y0, int x1, int y1, int argb, boolean round) {
        int c = round ? 1 : 0;
        q.fill(x0 + c, y0, x1 - c, y0 + 1, argb);
        q.fill(x0 + c, y1 - 1, x1 - c, y1, argb);
        q.fill(x0, y0 + 1, x0 + 1, y1 - 1, argb);
        q.fill(x1 - 1, y0 + 1, x1, y1 - 1, argb);
    }

    /** The fill's shading: Sleek one gradient, lighter at the top; Bold a bright upper band over a deeper lower one. */
    private static void shade(GuiRects q, int x0, int y0, int x1, int y1, int color, boolean bold) {
        int h = y1 - y0;
        if (!bold || h < 4) {
            q.gradient(x0, y0, x1, y1, mix(color, 0xFFFFFFFF, 0.28f), mix(color, 0xFF000000, 0.32f));
            return;
        }
        int mid = y0 + h / 2;
        q.gradient(x0, y0, x1, mid, mix(color, 0xFFFFFFFF, 0.42f), mix(color, 0xFFFFFFFF, 0.08f));
        q.gradient(x0, mid, x1, y1, mix(color, 0xFF000000, 0.12f), mix(color, 0xFF000000, 0.45f));
    }

    private static int ghostColor(int color) {
        return (mix(color, 0xFFFFFFFF, 0.6f) & 0x00FFFFFF) | 0xC8000000;
    }

    /** {@code a} moved {@code t} of the way to {@code b}, per channel; alpha is {@code a}'s (at least opaque-ish). */
    static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int gg = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        int alpha = (a >>> 24) == 0 ? 0xFF : a >>> 24;
        return alpha << 24 | r << 16 | gg << 8 | bl;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** The stat's item, drawn into a {@code size}-unit square at {@code (x, y)} (items are 16 units; scaled to fit). */
    private static void drawIcon(GuiGraphicsExtractor g, Readout r, int x, int y, int size) {
        ItemStack stack = icon(r);
        if (stack == null) {
            return;
        }
        var pose = g.pose();
        pose.pushMatrix();
        try {
            pose.translate(x, y);
            pose.scale(size / 16f, size / 16f);
            g.item(stack, 0, 0);
        } finally {
            pose.popMatrix();
        }
    }

    /** Hypixel's own stat icons where it has one (the SkyBlock menu's Health golden apple, Defense iron chestplate,
     *  Intelligence enchanted book), vanilla's obvious ones for the rest. */
    static ItemStack icon(Readout r) {
        if (icons == null) {
            ItemStack[] a = new ItemStack[N];
            a[Readout.HEALTH_BAR.ordinal()] = new ItemStack(Items.GOLDEN_APPLE);
            a[Readout.MANA_BAR.ordinal()] = new ItemStack(Items.ENCHANTED_BOOK);
            a[Readout.DEFENCE_BAR.ordinal()] = new ItemStack(Items.IRON_CHESTPLATE);
            a[Readout.VITALITY_BAR.ordinal()] = new ItemStack(Items.GLISTERING_MELON_SLICE);
            a[Readout.XP_BAR.ordinal()] = new ItemStack(Items.EXPERIENCE_BOTTLE);
            a[Readout.OTHER_BAR.ordinal()] = new ItemStack(Items.CLOCK);
            icons = a;
        }
        return icons[r.ordinal()];
    }
}

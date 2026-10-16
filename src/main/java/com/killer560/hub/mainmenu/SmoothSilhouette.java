package com.killer560.hub.mainmenu;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.hud.GuiRects;

/**
 * The slime boss scene's character as a smooth, natural silhouette (the theme's designer, 2026-10-16: "un-minecraft-ify my
 * skin and put that in there. It looks weird all pixelated."): slender human proportions, long flowing hair after the
 * AGreenCatgirl skin's long back hair, a clean blade held low toward the boss, the edges facing the boss lit.
 * <p>
 * The shape is a union of soft primitives (ellipses and tapered capsules) measured as a signed distance in real screen
 * pixels. Once per screen size it is rasterised into spans at screen resolution - inside pixels dark, the one-pixel edge at
 * half alpha (antialiasing), and the pixels within a few of the edge facing the boss in the rim colour - and runs that repeat
 * on the next row are merged into one rectangle. A frame then only replays those rectangles under a pose scaled to real
 * pixels; it allocates nothing beyond the batch's own parts.
 */
final class SmoothSilhouette {

    private SmoothSilhouette() {
    }

    private static final int SHADOW = 0xFF030704;

    // ---- the shape, in head heights; x toward the boss (right), y down from the top of the head -------------------
    /** Ellipses: cx, cy, rx, ry. */
    private static final float[][] ELLIPSES = {
            {0.03f, 0.50f, 0.36f, 0.50f},   // head
            {-0.07f, 0.44f, 0.42f, 0.50f},  // hair over the crown and the back of the head
            {1.13f, 2.96f, 0.10f, 0.10f},   // the sword hand
    };
    /** Tapered capsules: ax, ay, bx, by, ra, rb. */
    private static final float[][] CAPSULES = {
            // neck, shoulders, torso, hips
            {0.02f, 0.85f, 0.00f, 1.25f, 0.12f, 0.12f},
            {-0.40f, 1.38f, 0.38f, 1.38f, 0.15f, 0.15f},
            {0.00f, 1.35f, 0.00f, 2.55f, 0.36f, 0.26f},
            {0.00f, 2.50f, 0.00f, 3.05f, 0.27f, 0.36f},
            // legs, running off the bottom of the screen
            {-0.16f, 3.00f, -0.34f, 4.60f, 0.20f, 0.14f},
            {-0.34f, 4.60f, -0.48f, 6.40f, 0.14f, 0.09f},
            {0.17f, 3.00f, 0.36f, 4.60f, 0.20f, 0.14f},
            {0.36f, 4.60f, 0.34f, 6.40f, 0.14f, 0.09f},
            // the back arm, hanging
            {-0.40f, 1.42f, -0.52f, 2.35f, 0.12f, 0.10f},
            {-0.52f, 2.35f, -0.46f, 3.10f, 0.10f, 0.08f},
            // the sword arm, reaching forward and down
            {0.38f, 1.42f, 0.68f, 2.28f, 0.12f, 0.10f},
            {0.68f, 2.28f, 1.10f, 2.92f, 0.10f, 0.08f},
            // long hair down the back, a strand lifting behind, the ends tapering, a fringe in front
            {-0.22f, 0.60f, -0.42f, 1.60f, 0.30f, 0.24f},
            {-0.42f, 1.60f, -0.56f, 2.45f, 0.24f, 0.13f},
            {-0.56f, 2.45f, -0.78f, 2.95f, 0.13f, 0.015f},
            {-0.28f, 1.45f, -0.36f, 2.60f, 0.15f, 0.02f},
            {-0.34f, 0.85f, -0.98f, 1.85f, 0.13f, 0.015f},
            {-0.44f, 1.20f, -0.90f, 2.30f, 0.10f, 0.012f},
            {0.18f, 0.12f, 0.34f, 0.52f, 0.11f, 0.02f},
    };
    /** The blade's direction (down and forward, toward the lake in front of the boss), unit length. */
    private static final float BX = 0.60f;
    private static final float BY = 0.80f;

    private static double sdf(double x, double y) {
        double d = Double.MAX_VALUE;
        for (float[] e : ELLIPSES) {
            double qx = (x - e[0]) / e[2];
            double qy = (y - e[1]) / e[3];
            d = Math.min(d, (Math.sqrt(qx * qx + qy * qy) - 1) * Math.min(e[2], e[3]));
        }
        for (float[] c : CAPSULES) {
            d = Math.min(d, capsule(x, y, c[0], c[1], c[2], c[3], c[4], c[5]));
        }
        // The sword: grip and pommel behind the hand, a crossguard across it, the blade tapering to a point.
        double hx = 1.13;
        double hy = 2.96;
        d = Math.min(d, capsule(x, y, hx - BX * 0.28, hy - BY * 0.28, hx + BX * 0.12, hy + BY * 0.12, 0.045, 0.045));
        double px = hx - BX * 0.32;
        double py = hy - BY * 0.32;
        d = Math.min(d, Math.hypot(x - px, y - py) - 0.07);
        double gx = hx + BX * 0.16;
        double gy = hy + BY * 0.16;
        d = Math.min(d, capsule(x, y, gx - BY * 0.30, gy + BX * 0.30, gx + BY * 0.30, gy - BX * 0.30, 0.04, 0.04));
        double sx = hx + BX * 0.18;
        double sy = hy + BY * 0.18;
        d = Math.min(d, capsule(x, y, sx, sy, sx + BX * 2.7, sy + BY * 2.7, 0.055, 0.004));
        return d;
    }

    /** Distance to a capsule whose radius runs from {@code ra} at a to {@code rb} at b. */
    private static double capsule(double x, double y, double ax, double ay, double bx, double by, double ra, double rb) {
        double vx = bx - ax;
        double vy = by - ay;
        double t = ((x - ax) * vx + (y - ay) * vy) / (vx * vx + vy * vy);
        t = Math.max(0, Math.min(1, t));
        double dx = x - (ax + vx * t);
        double dy = y - (ay + vy * t);
        return Math.sqrt(dx * dx + dy * dy) - (ra + (rb - ra) * t);
    }

    // ---- the rasterised spans (rebuilt only when the screen size or GUI scale changes) -----------------------------
    private static int builtW = -1;
    private static int builtH = -1;
    private static int builtScale = -1;
    private static int n;
    private static int[] x0 = new int[0];
    private static int[] y0 = new int[0];
    private static int[] x1 = new int[0];
    private static int[] y1 = new int[0];
    private static int[] cls = new int[0];

    private static final int DARK = 1;
    private static final int DARK_EDGE = 2;
    private static final int LIT = 3;
    private static final int LIT_EDGE = 4;

    /** Rectangles the last frame drew (for the testkit's note). */
    static int rects() {
        return n;
    }

    private static void build(int w, int h, int scale) {
        builtW = w;
        builtH = h;
        builtScale = scale;
        int sw = w * scale;
        int sh = h * scale;
        double unit = sh * 0.115;          // one head height in real pixels
        double ox = sw * 0.13;             // the head's centre
        double oy = sh * 0.33;             // the top of the head
        double rimPx = Math.max(2.0, sh / 360.0);
        int left = Math.max(0, (int) Math.floor(ox - 1.15 * unit));
        int right = Math.min(sw, (int) Math.ceil(ox + 2.95 * unit));
        int top = Math.max(0, (int) Math.floor(oy - 0.1 * unit));
        int bottom = sh;
        int cols = right - left;
        int[] row = new int[Math.max(1, cols)];
        // Open runs from the row above: start x, end x, class, the rect index they extend.
        int cap = 4096;
        int[] ax0 = new int[cap];
        int[] ay0 = new int[cap];
        int[] ax1 = new int[cap];
        int[] ay1 = new int[cap];
        int[] acl = new int[cap];
        int count = 0;
        int[] open = new int[0];
        int openN = 0;
        for (int y = top; y < bottom; y++) {
            double fy = (y + 0.5 - oy) / unit;
            for (int i = 0; i < cols; i++) {
                double fx = (left + i + 0.5 - ox) / unit;
                double d = sdf(fx, fy) * unit; // in pixels
                if (d >= 0.5) {
                    row[i] = 0;
                    continue;
                }
                boolean edge = d > -0.5;
                boolean lit = sdf(fx + rimPx / unit, fy - rimPx * 0.4 / unit) * unit >= 0.5;
                row[i] = lit ? (edge ? LIT_EDGE : LIT) : (edge ? DARK_EDGE : DARK);
            }
            int[] nextOpen = new int[cols + 1];
            int nextN = 0;
            int i = 0;
            while (i < cols) {
                int c = row[i];
                if (c == 0) {
                    i++;
                    continue;
                }
                int j = i;
                while (j < cols && row[j] == c) {
                    j++;
                }
                int sx0 = left + i;
                int sx1 = left + j;
                int found = -1;
                for (int k = 0; k < openN; k++) {
                    int idx = open[k];
                    if (ax0[idx] == sx0 && ax1[idx] == sx1 && acl[idx] == c && ay1[idx] == y) {
                        found = idx;
                        break;
                    }
                }
                if (found >= 0) {
                    ay1[found] = y + 1;
                    nextOpen[nextN++] = found;
                } else {
                    if (count == ax0.length) {
                        ax0 = java.util.Arrays.copyOf(ax0, count * 2);
                        ay0 = java.util.Arrays.copyOf(ay0, count * 2);
                        ax1 = java.util.Arrays.copyOf(ax1, count * 2);
                        ay1 = java.util.Arrays.copyOf(ay1, count * 2);
                        acl = java.util.Arrays.copyOf(acl, count * 2);
                    }
                    ax0[count] = sx0;
                    ay0[count] = y;
                    ax1[count] = sx1;
                    ay1[count] = y + 1;
                    acl[count] = c;
                    nextOpen[nextN++] = count;
                    count++;
                }
                i = j;
            }
            open = nextOpen;
            openN = nextN;
        }
        n = count;
        x0 = ax0;
        y0 = ay0;
        x1 = ax1;
        y1 = ay1;
        cls = acl;
    }

    /** Draws the character into {@code r} for a {@code w} x {@code h} GUI at GUI scale {@code scale}. */
    static void draw(GuiRects r, int w, int h, int scale, int hot) {
        if (w != builtW || h != builtH || scale != builtScale) {
            build(w, h, scale);
        }
        int lit = MenuStyle.alpha(hot, 0xC0);
        int litEdge = MenuStyle.alpha(hot, 0x60);
        int darkEdge = MenuStyle.alpha(SHADOW, 0x80);
        var pose = r.graphics().pose();
        pose.pushMatrix();
        pose.scale(1f / scale, 1f / scale);
        for (int i = 0; i < n; i++) {
            int c = switch (cls[i]) {
                case DARK -> SHADOW;
                case DARK_EDGE -> darkEdge;
                case LIT -> lit;
                default -> litEdge;
            };
            r.fill(x0[i], y0[i], x1[i], y1[i], c);
        }
        pose.popMatrix();
    }
}

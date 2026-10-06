package com.killer560.hub.crosshair;

import com.killer560.hub.crosshair.CustomCrosshairConfig.Style;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * Draws a {@link CustomCrosshairConfig} crosshair - shared by the in-game HUD layer and the editor's live preview, so
 * the preview is exactly what the game draws.
 * <p>
 * <b>Pixel-perfect.</b> Everything is drawn in SCREEN pixels: the pose is reset and scaled by {@code 1 / guiScale}, so
 * an int passed to {@code fill} is one framebuffer pixel, and every size is rounded to whole pixels before drawing. A
 * one-pixel line is therefore one crisp pixel at any GUI Scale, never a smeared GUI pixel or a half-covered one. The
 * shape is laid out from the corner of its centre square (thickness x thickness), so with integer sizes every edge is
 * an integer and the arms are exactly symmetric; quarter-turn rotations are done in integers too. Only a free rotation
 * (anything not a multiple of 90, including the X style's 45) goes through the pose and is not grid-aligned - a
 * diagonal line cannot be.
 * <p>
 * <b>Cheap.</b> No allocation per frame: the ring is a cached list of rectangles (one per run of identical pixel rows,
 * like the Custom Scoreboard border - docs/LESSONS.md "Every GUI fill costs more than its pixels"), rebuilt only when
 * its radius or thickness changes, into a buffer that only grows. A plain cross is at most eight fills plus the dot.
 */
public final class CrosshairRenderer {

    private CrosshairRenderer() {
    }

    /** A cached ring: the rectangles covering every pixel whose centre lies in the annulus, rows merged into runs. */
    private static final class Ring {
        int radius = Integer.MIN_VALUE;
        int width;
        int anchor;
        int[] rects = new int[64];
        int n;

        void ensure(int radius, int width, int anchor) {
            if (radius == this.radius && width == this.width && anchor == this.anchor) {
                return;
            }
            this.radius = radius;
            this.width = width;
            this.anchor = anchor;
            build();
        }

        private void build() {
            n = 0;
            float c = anchor / 2f;
            float ro = radius + width / 2f;
            float ri = radius - width / 2f;
            int yTop = (int) Math.floor(c - ro) - 1;
            int yBottom = (int) Math.ceil(c + ro) + 1;
            int prevStart = -1;
            int prevCount = 0;
            for (int y = yTop; y <= yBottom; y++) {
                float py = y + 0.5f - c;
                if (Math.abs(py) >= ro) {
                    prevCount = 0;
                    continue;
                }
                float wo = (float) Math.sqrt(ro * ro - py * py);
                int xl = (int) Math.ceil(c - wo - 0.5f + 1e-4f);
                int xr = (int) Math.floor(c + wo - 0.5f - 1e-4f);
                int il = Integer.MAX_VALUE;
                int ir = Integer.MIN_VALUE;
                if (ri > 0 && Math.abs(py) < ri) {
                    float wi = (float) Math.sqrt(ri * ri - py * py);
                    il = (int) Math.ceil(c - wi - 0.5f + 1e-4f);
                    ir = (int) Math.floor(c + wi - 0.5f - 1e-4f);
                }
                // Spans of this row as half-open [x0, x1).
                int s0a, s0b, s1a = 0, s1b = 0, count;
                if (il > ir) {
                    s0a = xl;
                    s0b = xr + 1;
                    count = 1;
                } else {
                    s0a = xl;
                    s0b = il;
                    s1a = ir + 1;
                    s1b = xr + 1;
                    count = 2;
                    if (s0b <= s0a) {
                        s0a = s1a;
                        s0b = s1b;
                        count = 1;
                    } else if (s1b <= s1a) {
                        count = 1;
                    }
                }
                if (s0b <= s0a) {
                    prevCount = 0;
                    continue;
                }
                if (prevCount == count && rects[prevStart] == s0a && rects[prevStart + 2] == s0b
                        && (count == 1 || (rects[prevStart + 4] == s1a && rects[prevStart + 6] == s1b))) {
                    rects[prevStart + 3] = y + 1;
                    if (count == 2) {
                        rects[prevStart + 7] = y + 1;
                    }
                    continue;
                }
                grow(n + 8);
                prevStart = n * 4;
                rects[n * 4] = s0a;
                rects[n * 4 + 1] = y;
                rects[n * 4 + 2] = s0b;
                rects[n * 4 + 3] = y + 1;
                n++;
                if (count == 2) {
                    rects[n * 4] = s1a;
                    rects[n * 4 + 1] = y;
                    rects[n * 4 + 2] = s1b;
                    rects[n * 4 + 3] = y + 1;
                    n++;
                }
                prevCount = count;
            }
        }

        private void grow(int rectsNeeded) {
            if (rectsNeeded * 4 > rects.length) {
                int[] bigger = new int[Math.max(rects.length * 2, rectsNeeded * 4)];
                System.arraycopy(rects, 0, bigger, 0, n * 4);
                rects = bigger;
            }
        }
    }

    private static final Ring RING = new Ring();
    private static final Ring RING_OUTLINE = new Ring();

    /** How many fills the last {@link #draw} made (testkit/diagnostics). */
    public static int lastFillCount;

    /**
     * Draws {@code c} centred on screen pixel ({@code centreX}, {@code centreY}).
     *
     * @param guiScale the window's GUI scale (screen pixels per GUI pixel), to reach screen pixels
     * @param unit     screen pixels per crosshair unit ({@link CustomCrosshairFeature#unit})
     * @param spread   extra gap/radius in crosshair units (dynamic spread and recoil), 0 when still
     */
    public static void draw(GuiGraphicsExtractor g, CustomCrosshairConfig c, float centreX, float centreY,
                            int guiScale, float unit, float spread, int mainColor, int dotColor) {
        Style style = c.getStyle();
        boolean arms = style.hasArms();
        boolean circle = style.hasCircle();
        boolean dot = style == Style.DOT || c.isDot();
        int t = Math.max(1, Math.round(c.getThickness() * unit));
        int len = Math.max(0, Math.round(c.getLength() * unit));
        int gap = Math.round((c.getGap() + spread) * unit);
        int d = Math.max(1, Math.round(c.getDotSize() * unit));
        // The centre square everything is laid out from. With arms it is the line thickness, and the dot keeps the
        // same parity so it sits dead centre between them.
        int a = arms ? t : d;
        if (arms && ((d - a) & 1) != 0) {
            d++;
        }
        int o = c.isOutline() ? Math.max(1, Math.round(c.getOutlineThickness() * unit)) : 0;
        boolean invert = c.isInvertBlend();
        boolean drawMain = (mainColor >>> 24) != 0;
        boolean drawDot = dot && (dotColor >>> 24) != 0;
        int outlineColor = c.getOutlineColor();
        boolean drawOutline = o > 0 && (outlineColor >>> 24) != 0;

        float rot = c.getRotation() + (style == Style.X ? 45f : 0f);
        rot = ((rot % 360f) + 360f) % 360f;
        int k = 0;
        boolean free = false;
        if (rot % 90f == 0f) {
            k = Math.round(rot / 90f) & 3;
        } else {
            free = true;
        }

        int cx0 = Math.round(centreX - a / 2f);
        int cy0 = Math.round(centreY - a / 2f);

        int radius = 0, ringW = 0;
        if (circle) {
            radius = Math.max(1, Math.round((c.getCircleRadius() + spread) * unit));
            ringW = Math.max(1, Math.round(c.getCircleThickness() * unit));
            RING.ensure(radius, ringW, a);
            if (drawOutline) {
                RING_OUTLINE.ensure(radius, ringW + 2 * o, a);
            }
        }

        boolean top = arms && c.isArmTop() && style != Style.T_SHAPE;
        boolean bottom = arms && c.isArmBottom();
        boolean left = arms && c.isArmLeft();
        boolean right = arms && c.isArmRight();
        if (len == 0) {
            top = bottom = left = right = false;
        }
        int dOff = (a - d) / 2;

        fills = 0;
        var pose = g.pose();
        pose.pushMatrix();
        try {
            pose.identity();
            pose.scale(1f / guiScale, 1f / guiScale);
            pose.translate(cx0, cy0);
            if (free) {
                pose.translate(a / 2f, a / 2f);
                pose.rotate((float) Math.toRadians(rot));
                pose.translate(-a / 2f, -a / 2f);
            }
            if (drawOutline) {
                if (right) {
                    rect(g, k, a, a + gap - o, -o, a + gap + len + o, a + o, outlineColor, false);
                }
                if (left) {
                    rect(g, k, a, -gap - len - o, -o, -gap + o, a + o, outlineColor, false);
                }
                if (bottom) {
                    rect(g, k, a, -o, a + gap - o, a + o, a + gap + len + o, outlineColor, false);
                }
                if (top) {
                    rect(g, k, a, -o, -gap - len - o, a + o, -gap + o, outlineColor, false);
                }
                if (circle) {
                    ring(g, k, a, RING_OUTLINE, outlineColor, false);
                }
                if (dot) {
                    rect(g, k, a, dOff - o, dOff - o, dOff + d + o, dOff + d + o, outlineColor, false);
                }
            }
            if (drawMain) {
                if (right) {
                    rect(g, k, a, a + gap, 0, a + gap + len, a, mainColor, invert);
                }
                if (left) {
                    rect(g, k, a, -gap - len, 0, -gap, a, mainColor, invert);
                }
                if (bottom) {
                    rect(g, k, a, 0, a + gap, a, a + gap + len, mainColor, invert);
                }
                if (top) {
                    rect(g, k, a, 0, -gap - len, a, -gap, mainColor, invert);
                }
                if (circle) {
                    ring(g, k, a, RING, mainColor, invert);
                }
            }
            if (drawDot) {
                rect(g, k, a, dOff, dOff, dOff + d, dOff + d, dotColor, invert);
            }
        } finally {
            pose.popMatrix();
        }
        lastFillCount = fills;
    }

    private static int fills;

    private static void ring(GuiGraphicsExtractor g, int k, int a, Ring ring, int color, boolean invert) {
        int[] r = ring.rects;
        for (int i = 0; i < ring.n; i++) {
            rect(g, k, a, r[i * 4], r[i * 4 + 1], r[i * 4 + 2], r[i * 4 + 3], color, invert);
        }
    }

    /** One rectangle in the corner frame, turned {@code k} quarter turns about the centre square's middle. */
    private static void rect(GuiGraphicsExtractor g, int k, int a, int x0, int y0, int x1, int y1, int color,
                             boolean invert) {
        int rx0, ry0, rx1, ry1;
        switch (k) {
            case 1 -> {
                rx0 = a - y1;
                rx1 = a - y0;
                ry0 = x0;
                ry1 = x1;
            }
            case 2 -> {
                rx0 = a - x1;
                rx1 = a - x0;
                ry0 = a - y1;
                ry1 = a - y0;
            }
            case 3 -> {
                rx0 = y0;
                rx1 = y1;
                ry0 = a - x1;
                ry1 = a - x0;
            }
            default -> {
                rx0 = x0;
                rx1 = x1;
                ry0 = y0;
                ry1 = y1;
            }
        }
        if (rx1 <= rx0 || ry1 <= ry0) {
            return;
        }
        fills++;
        if (invert) {
            g.fill(RenderPipelines.GUI_INVERT, rx0, ry0, rx1, ry1, color);
        } else {
            g.fill(rx0, ry0, rx1, ry1, color);
        }
    }
}

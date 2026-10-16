package com.killer560.hub.mainmenu;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.hud.GuiRects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;

/**
 * The Simple main menu scenes (killer560, 2026-10-16: "Look at ambers simple theme and apply that concept to all the other
 * color palletes ... Simple is just the floating particles of their right color and whatnot with the right shading theme").
 * One family, the Amber Simple concept ({@link AmberCalmScene}): a dark backdrop in the theme's own base gradient, a low glow
 * that breathes slowly, a fainter pool of light drifting along the bottom, a faint warmth behind the title, and particles in
 * three depths that thin out behind the button column and stay clear of the title. Each theme reskins it:
 * <ul>
 *   <li>{@link Flavour#EMBERS} (Amber, and Custom in its own accent): embers rising and flickering, the near ones with a
 *       hot centre and a soft halo, over a warm floor of light;</li>
 *   <li>{@link Flavour#BUBBLES} (Ocean): bubbles rising and wobbling (a faint body, a bright rim, a highlight), with tiny
 *       motes drifting in the water, light from the surface above and a deep teal glow below;</li>
 *   <li>{@link Flavour#STARDUST} (Dark): a fine field of stars twinkling and drifting, dust rising slowly, a few soft near
 *       motes, two faint nebula glows that wander and the odd shooting star, nearly black and white;</li>
 *   <li>{@link Flavour#FIREFLIES} (Viridian): fireflies wandering and blinking in yellow-green with soft halos, spores
 *       drifting down, a mossy glow along the floor under a darker canopy.</li>
 * </ul>
 * HD (killer560: "in hd"): the backdrop is one full-screen quad through {@code scene_simple_bg}, composited in float and
 * dithered with blue noise (no banding), and every particle is an exactly antialiased {@code scene_simple_mote} quad at its
 * sub-pixel place, at the screen's full resolution; positions come from the nanosecond scene clock, so motion is smooth at
 * any frame rate. Without the scene shaders (VulkanMod, a failure) the same plan is drawn through {@link GuiRects}.
 * <p>
 * Every position is a function of the clock, the size and a per-particle hash (no state). The plan is built into static
 * arrays (render thread only), so a frame allocates only the shader elements themselves.
 */
public final class SimpleScenes {

    private SimpleScenes() {
    }

    /** Which particles and shading a Simple scene draws. */
    public enum Flavour { EMBERS, BUBBLES, STARDUST, FIREFLIES }

    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;

    /** Particle kinds (the mote shader's). */
    static final int DISC = 0;
    static final int HALO = 1;
    static final int BUBBLE = 2;
    /** A firefly: a disc with a wide soft glow round it. */
    static final int GLOW = 3;

    /** Test probes (testkit 1700-1704): frames drawn through the shaders, frames drawn through GuiRects, and the particles
     *  the last frame drew. */
    public static long shaderFrames;
    public static long rectFrames;
    public static int lastParticles;
    public static Flavour lastFlavour;

    /** The Simple flavour a background draws, or null when it is not a Simple scene. */
    public static Flavour flavour(MainMenuThemeConfig.Scene scene) {
        if (scene == null) {
            return null;
        }
        return switch (scene) {
            case SIMPLE_EMBERS -> Flavour.EMBERS;
            case SIMPLE_BUBBLES -> Flavour.BUBBLES;
            case SIMPLE_STARDUST -> Flavour.STARDUST;
            case SIMPLE_FIREFLIES -> Flavour.FIREFLIES;
            default -> null;
        };
    }

    // ---------------------------------------------------------------- the plan (GUI units)

    private static int baseTop;
    private static int baseBottom;
    /** Two washes: colour, peak alpha 0..1, the row where it is 0 and the row where it is full. */
    private static final int[] washCol = new int[2];
    private static final double[] washA = new double[2];
    private static final double[] washY0 = new double[2];
    private static final double[] washY1 = new double[2];
    /** Three glows: colour, peak alpha 0..1, centre and radii. */
    private static final int[] glowCol = new int[3];
    private static final double[] glowA = new double[3];
    private static final double[] glowX = new double[3];
    private static final double[] glowY = new double[3];
    private static final double[] glowRx = new double[3];
    private static final double[] glowRy = new double[3];
    private static boolean lightVignette;

    private static final int MAX = 600;
    private static final double[] px = new double[MAX];
    private static final double[] py = new double[MAX];
    private static final double[] pr = new double[MAX];
    private static final int[] pk = new int[MAX];
    private static final int[] pc = new int[MAX];
    private static int n;

    private static void add(double x, double y, double radius, int kind, int rgb, double alpha) {
        if (n >= MAX || alpha < 0.004 || radius <= 0) {
            return;
        }
        px[n] = x;
        py[n] = y;
        pr[n] = radius;
        pk[n] = kind;
        pc[n] = MenuStyle.alpha(rgb, (int) Math.round(Math.min(1.0, alpha) * 255));
        n++;
    }

    private static void wash(int i, int rgb, double a, double y0, double y1) {
        washCol[i] = rgb;
        washA[i] = a;
        washY0[i] = y0;
        washY1[i] = y1;
    }

    private static void glow(int i, int rgb, double a, double cx, double cy, double rx, double ry) {
        glowCol[i] = rgb;
        glowA[i] = a;
        glowX[i] = cx;
        glowY[i] = cy;
        glowRx[i] = rx;
        glowRy[i] = ry;
    }

    /** Builds the frame's plan for {@code f}: the backdrop's washes and glows and every visible particle. */
    static void plan(Flavour f, int w, int h, double t, MainMenuLook look) {
        int accent = look.accent() | BLACK;
        int panel = look.panelColour() | BLACK;
        int text = look.text() | BLACK;
        int second = look.switchOn() | BLACK;
        boolean light = MenuStyle.luminance(panel) > 0.5f;
        lightVignette = light;
        baseTop = MainMenuScenes.baseTop(accent, panel, light);
        baseBottom = MainMenuScenes.baseBottom(accent, panel, light);
        n = 0;
        for (int i = 0; i < 2; i++) {
            washA[i] = 0;
        }
        for (int i = 0; i < 3; i++) {
            glowA[i] = 0;
        }
        float density = look.density();
        switch (f) {
            case BUBBLES -> bubbles(w, h, t, accent, panel, text, density);
            case STARDUST -> stardust(w, h, t, accent, text, second, density);
            case FIREFLIES -> fireflies(w, h, t, accent, panel, text, density);
            default -> embers(w, h, t, accent, density);
        }
        lastParticles = n;
        lastFlavour = f;
    }

    private static int count(int w, int h, double perArea, float density, int min, int max) {
        if (density <= 0f) {
            return 0;
        }
        return Math.max(Math.round(min * density), Math.min(Math.round(max * density),
                (int) Math.round(w * h / perArea * density)));
    }

    /** Quieter behind the button column (Amber Simple's rule): 0.35 at its centre line, full beyond 16% of the width. */
    private static double clearOfColumn(double x, int w) {
        double off = Math.abs(x - w * 0.5) / (w * 0.16);
        return off >= 1.0 ? 1.0 : 0.35 + 0.65 * off * off;
    }

    /** 0 in the title band, 1 below it, smooth between {@code a} and {@code b} (fractions of the height). */
    private static double belowTitle(double y, int h, double a, double b) {
        double u = (y / h - a) / (b - a);
        u = Math.max(0, Math.min(1, u));
        return u * u * (3 - 2 * u);
    }

    // ---------------------------------------------------------------- Embers (Amber, Custom)

    private static void embers(int w, int h, double t, int accent, float density) {
        int hot = MenuStyle.mix(accent, WHITE, 0.45f);
        int deep = MenuStyle.mix(accent, BLACK, 0.45f);
        double breathe = 0.82 + 0.18 * Math.sin(t * 0.35);
        // the warm floor of light, the low glow drifting side to side, a faint warmth behind the title
        wash(0, deep, 0x70 / 255.0 * breathe, h * 0.55, h);
        glow(0, accent, 0.27 * breathe, w / 2.0 + Math.sin(t * 0.07) * w * 0.08, h * 1.04, w * 0.55, h * 0.40);
        glow(1, accent, 0x16 / 255.0, w / 2.0, h * 0.12, Math.min(w * 0.45, 260) * 0.85, Math.min(h * 0.22, 80) * 0.85);
        // heat bloom: a fainter pool low on the far side that swells and fades, like air over a fire
        double bloom = 0.5 + 0.5 * Math.sin(t * 0.21 + 1.3);
        glow(2, hot, 0.13 * bloom, w * (0.5 + 0.28 * Math.sin(t * 0.05 + 2.0)), h, w * 0.27, h * 0.24);

        if (density <= 0f) {
            return;
        }
        int nFar = Math.max(10, Math.min(56, (int) Math.round(w * h / 7000.0 * density)));
        int nMid = Math.max(6, Math.min(32, (int) Math.round(w * h / 11000.0 * density)));
        int nNear = Math.max(3, Math.min(14, (int) Math.round(w * h / 26000.0 * density)));
        int farCol = MenuStyle.mix(accent, deep, 0.2f);
        emberLayer(w, h, t, nFar, 101, 2.5, 4.0, 3.0, 0x90, 0.5, DISC, farCol, accent);
        emberLayer(w, h, t, nMid, 211, 5.0, 6.0, 6.0, 0xD0, 0.75, HALO, accent, hot);
        emberLayer(w, h, t, nNear, 307, 8.0, 7.0, 10.0, 0xF4, 1.3, HALO, accent, hot);
    }

    private static void emberLayer(int w, int h, double t, int count, int salt, double baseSpeed, double speedRange,
                                   double swayAmp, int maxA, double radius, int kind, int col, int bright) {
        double travel = h * 0.9 + 20.0;
        for (int i = 0; i < count; i++) {
            double r1 = MainMenuScenes.hash(i, salt);
            double r2 = MainMenuScenes.hash(i, salt + 1);
            double r3 = MainMenuScenes.hash(i, salt + 2);
            double speed = baseSpeed + r2 * speedRange;
            double rise = (t * speed + r1 * travel * 13.0) % travel;
            double y = h + 8.0 - rise;
            double x = r3 * w + Math.sin(t * (0.12 + r1 * 0.2) + i * 1.7) * swayAmp * (0.6 + r2);
            double progress = rise / travel;
            // fade in over the first sixth, then out smoothly so nothing reaches the title
            double life = Math.min(1.0, progress * 6.0) * (1.0 - progress);
            double flicker = 0.8 + 0.2 * Math.sin(t * (1.1 + r3 * 1.4) + i * 2.3);
            double a = maxA / 255.0 * life * flicker * clearOfColumn(x, w);
            add(x, y, radius * (0.85 + 0.3 * r2), kind, r1 > 0.6 ? bright : col, a);
        }
    }

    // ---------------------------------------------------------------- Bubbles (Ocean)

    private static void bubbles(int w, int h, double t, int accent, int panel, int text, float density) {
        int surface = MenuStyle.mix(accent, WHITE, 0.35f);
        int deep = MenuStyle.mix(accent, BLACK, 0.55f);
        double breathe = 0.82 + 0.18 * Math.sin(t * 0.33);
        // light from the surface above, a deep teal floor below
        wash(0, surface, 0.085 * (0.85 + 0.15 * Math.sin(t * 0.5)), h * 0.5, 0);
        wash(1, deep, 0x68 / 255.0 * breathe, h * 0.55, h);
        glow(0, accent, 0.24 * breathe, w / 2.0 + Math.sin(t * 0.06) * w * 0.1, h * 1.05, w * 0.55, h * 0.40);
        glow(1, surface, 0.07 * (0.8 + 0.2 * Math.sin(t * 0.41)), w / 2.0 + Math.sin(t * 0.09) * w * 0.15, -h * 0.06,
                w * 0.5, h * 0.26);
        double bloom = 0.5 + 0.5 * Math.sin(t * 0.19 + 0.7);
        glow(2, MenuStyle.mix(accent, WHITE, 0.2f), 0.12 * bloom, w * (0.5 + 0.3 * Math.sin(t * 0.045 + 1.0)), h * 0.95,
                w * 0.26, h * 0.22);

        if (density <= 0f) {
            return;
        }
        // tiny motes drifting in the water, the far depth
        int mote = MenuStyle.mix(accent, text, 0.35f);
        int nMote = Math.max(12, Math.min(60, (int) Math.round(w * h / 6500.0 * density)));
        for (int i = 0; i < nMote; i++) {
            double r1 = MainMenuScenes.hash(i, 401);
            double r2 = MainMenuScenes.hash(i, 402);
            double r3 = MainMenuScenes.hash(i, 403);
            double travel = h * 0.85 + 10;
            double rise = (t * (1.4 + r2 * 2.2) + r1 * travel * 9.0) % travel;
            double y = h + 4.0 - rise;
            double x = r3 * w + Math.sin(t * (0.07 + r1 * 0.09) + i) * (8 + r2 * 10);
            double progress = rise / travel;
            double life = Math.min(1.0, progress * 5.0) * (1.0 - progress);
            double tw = 0.7 + 0.3 * Math.sin(t * (0.6 + r3) + i * 1.9);
            add(x, y, 0.42 + 0.12 * r2, DISC, mote, 0.5 * life * tw * clearOfColumn(x, w));
        }
        int nMid = Math.max(7, Math.min(32, (int) Math.round(w * h / 11000.0 * density)));
        int nNear = Math.max(3, Math.min(12, (int) Math.round(w * h / 28000.0 * density)));
        bubbleLayer(w, h, t, nMid, 431, 1.3, 1.1, 7.0, 5.0, 1.4, 0.62, surface);
        bubbleLayer(w, h, t, nNear, 457, 2.6, 1.8, 13.0, 7.0, 2.6, 0.78, MenuStyle.mix(surface, WHITE, 0.25f));
    }

    private static void bubbleLayer(int w, int h, double t, int count, int salt, double rMin, double rRange,
                                    double baseSpeed, double speedRange, double wobble, double maxA, int col) {
        double travel = h * 0.88 + 20.0;
        for (int i = 0; i < count; i++) {
            double r1 = MainMenuScenes.hash(i, salt);
            double r2 = MainMenuScenes.hash(i, salt + 1);
            double r3 = MainMenuScenes.hash(i, salt + 2);
            double radius = rMin + r2 * rRange;
            // bigger bubbles rise faster
            double speed = baseSpeed + r2 * speedRange;
            double rise = (t * speed + r1 * travel * 11.0) % travel;
            double y = h + 8.0 - rise;
            double drift = Math.sin(t * (0.1 + r1 * 0.12) + i * 2.1) * (4 + r3 * 6);
            double x = r3 * w + drift + Math.sin(t * (2.2 + r1 * 1.6) + i) * wobble * (0.6 + r2 * 0.4);
            double progress = rise / travel;
            double life = Math.min(1.0, progress * 6.0) * (1.0 - progress * progress);
            // a bubble swells a little as it rises
            add(x, y, radius * (0.9 + 0.2 * progress), BUBBLE, col, maxA * life * clearOfColumn(x, w));
        }
    }

    // ---------------------------------------------------------------- Stardust (Dark)

    private static void stardust(int w, int h, double t, int accent, int text, int second, float density) {
        int star = MenuStyle.mix(text, WHITE, 0.4f);
        int cool = MenuStyle.mix(accent, second, 0.5f);
        double breathe = 0.82 + 0.18 * Math.sin(t * 0.3);
        wash(0, MenuStyle.mix(second, BLACK, 0.55f), 0.30 * breathe, h * 0.6, h);
        // two faint nebula glows that wander, and the low glow every Simple scene has
        glow(0, MenuStyle.mix(second, BLACK, 0.1f), 0.17 * breathe, w / 2.0 + Math.sin(t * 0.06) * w * 0.08, h * 1.05,
                w * 0.55, h * 0.38);
        glow(1, cool, 0.11 * (0.8 + 0.2 * Math.sin(t * 0.17)), w * 0.24 + Math.sin(t * 0.05) * w * 0.04, h * 0.58,
                w * 0.30, h * 0.24);
        glow(2, MenuStyle.mix(accent, text, 0.5f), 0.08 * (0.8 + 0.2 * Math.sin(t * 0.13 + 2)),
                w * 0.77 - Math.sin(t * 0.04) * w * 0.04, h * 0.42, w * 0.26, h * 0.22);

        if (density <= 0f) {
            return;
        }
        // far: a fine field of stars drifting slowly left and twinkling
        int nStar = Math.max(30, Math.min(240, (int) Math.round(w * h / 2200.0 * density)));
        for (int i = 0; i < nStar; i++) {
            double r1 = MainMenuScenes.hash(i, 501);
            double r2 = MainMenuScenes.hash(i, 502);
            double r3 = MainMenuScenes.hash(i, 503);
            double span = w + 4.0;
            double x = ((r1 * span - t * (0.5 + r3 * 0.9)) % span + span) % span - 2.0;
            double y = r2 * h;
            double tw = 0.5 + 0.5 * Math.sin(t * (0.7 + r3 * 2.2) + i * 2.1);
            double a = (0.22 + 0.5 * r3) * (0.45 + 0.55 * tw) * (0.25 + 0.75 * belowTitle(y, h, 0.05, 0.24));
            add(x, y, 0.34 + 0.16 * r3, DISC, r1 > 0.93 ? MenuStyle.mix(accent, WHITE, 0.5f) : star, a);
        }
        // mid: dust rising slowly on the diagonal
        int nDust = Math.max(8, Math.min(40, (int) Math.round(w * h / 9000.0 * density)));
        double travel = h * 0.9 + 20.0;
        for (int i = 0; i < nDust; i++) {
            double r1 = MainMenuScenes.hash(i, 521);
            double r2 = MainMenuScenes.hash(i, 522);
            double r3 = MainMenuScenes.hash(i, 523);
            double rise = (t * (1.8 + r2 * 2.6) + r1 * travel * 7.0) % travel;
            double y = h + 6.0 - rise;
            double x = r3 * w + rise * 0.18 + Math.sin(t * (0.09 + r1 * 0.1) + i) * 6;
            double progress = rise / travel;
            double life = Math.min(1.0, progress * 6.0) * (1.0 - progress);
            add(x, y, 0.5 + 0.15 * r2, DISC, cool, 0.55 * life * clearOfColumn(x, w));
        }
        // near: a few soft motes with a halo
        int nNear = Math.max(3, Math.min(12, (int) Math.round(w * h / 30000.0 * density)));
        for (int i = 0; i < nNear; i++) {
            double r1 = MainMenuScenes.hash(i, 541);
            double r2 = MainMenuScenes.hash(i, 542);
            double r3 = MainMenuScenes.hash(i, 543);
            double rise = (t * (3.5 + r2 * 3.0) + r1 * travel * 5.0) % travel;
            double y = h + 8.0 - rise;
            double x = r3 * w + Math.sin(t * (0.11 + r1 * 0.12) + i * 1.3) * 9;
            double progress = rise / travel;
            double life = Math.min(1.0, progress * 6.0) * (1.0 - progress);
            double tw = 0.75 + 0.25 * Math.sin(t * (0.9 + r3) + i);
            add(x, y, 0.85 + 0.25 * r2, HALO, star, 0.72 * life * tw * clearOfColumn(x, w));
        }
        // a shooting star every nine seconds or so, below the title and away from the column
        double period = 9.0;
        int shot = (int) Math.floor(t / period);
        double p = (t - shot * period) / 1.2;
        if (p < 1.0) {
            boolean left = MainMenuScenes.hash(shot, 544) < 0.5;
            double sx = w * (left ? 0.06 + 0.12 * MainMenuScenes.hash(shot, 545) : 0.64 + 0.12 * MainMenuScenes.hash(shot, 545));
            double sy = h * (0.24 + 0.22 * MainMenuScenes.hash(shot, 546));
            double dist = Math.min(w * 0.2, 150) * (0.8 + 0.4 * MainMenuScenes.hash(shot, 547));
            double fade = Math.sin(Math.PI * Math.min(1.0, p * 1.15));
            for (int k = 0; k < 14; k++) {
                double fk = k / 14.0;
                double d = dist * Math.max(0, p - fk * 0.18);
                add(sx + d, sy + d * 0.42, 0.55 * (1 - fk * 0.6), k == 0 ? HALO : DISC, star, 0.85 * (1 - fk) * fade);
            }
        }
    }

    // ---------------------------------------------------------------- Fireflies (Viridian)

    private static void fireflies(int w, int h, double t, int accent, int panel, int text, float density) {
        int glowCol = MenuStyle.mix(accent, 0xFFEAFF8A, 0.55f);
        int moss = MenuStyle.mix(panel, accent, 0.35f);
        double breathe = 0.82 + 0.18 * Math.sin(t * 0.32);
        // a mossy glow along the floor under a darker canopy
        wash(0, moss, 0.34 * breathe, h * 0.5, h);
        wash(1, MenuStyle.mix(panel, BLACK, 0.55f), 0.32, h * 0.38, 0);
        glow(0, accent, 0.22 * breathe, w / 2.0 + Math.sin(t * 0.06) * w * 0.08, h * 1.04, w * 0.55, h * 0.40);
        glow(1, accent, 0.05, w / 2.0, h * 0.12, Math.min(w * 0.45, 260) * 0.85, Math.min(h * 0.22, 80) * 0.85);
        double bloom = 0.5 + 0.5 * Math.sin(t * 0.2 + 2.1);
        glow(2, glowCol, 0.11 * bloom, w * (0.5 + 0.3 * Math.sin(t * 0.05 + 2.0)), h * 0.96, w * 0.27, h * 0.23);

        if (density <= 0f) {
            return;
        }
        // far: spores drifting down from under the title
        int spore = MenuStyle.mix(accent, text, 0.5f);
        int nSpore = Math.max(8, Math.min(40, (int) Math.round(w * h / 9000.0 * density)));
        double travel = h * 0.8;
        for (int i = 0; i < nSpore; i++) {
            double r1 = MainMenuScenes.hash(i, 601);
            double r2 = MainMenuScenes.hash(i, 602);
            double r3 = MainMenuScenes.hash(i, 603);
            double fall = (t * (1.4 + r2 * 2.0) + r1 * travel * 9.0) % travel;
            double y = h * 0.18 + fall;
            double x = r3 * w + Math.sin(t * (0.15 + r1 * 0.2) + i * 1.7) * (4 + r2 * 6);
            double progress = fall / travel;
            double life = Math.min(1.0, progress * 6.0) * Math.min(1.0, (1.0 - progress) * 5.0);
            add(x, y, 0.42 + 0.12 * r2, DISC, spore, 0.42 * life * clearOfColumn(x, w));
        }
        int nMid = Math.max(7, Math.min(30, (int) Math.round(w * h / 12000.0 * density)));
        int nNear = Math.max(3, Math.min(12, (int) Math.round(w * h / 30000.0 * density)));
        fireflyLayer(w, h, t, nMid, 631, 0.9, 18, 0.8, glowCol, accent);
        fireflyLayer(w, h, t, nNear, 659, 1.5, 30, 1.0, glowCol, MenuStyle.mix(glowCol, WHITE, 0.2f));
    }

    private static void fireflyLayer(int w, int h, double t, int count, int salt, double radius, double roam, double maxA,
                                     int col, int bright) {
        for (int i = 0; i < count; i++) {
            double r1 = MainMenuScenes.hash(i, salt);
            double r2 = MainMenuScenes.hash(i, salt + 1);
            double r3 = MainMenuScenes.hash(i, salt + 2);
            double r4 = MainMenuScenes.hash(i, salt + 3);
            // each wanders round a home low on the screen: two slow sines a side, never the same path twice in view
            double hx = r1 * w;
            double hy = h * (0.36 + 0.58 * r2);
            double x = hx + roam * Math.sin(t * (0.07 + r3 * 0.09) + i * 2.3) + roam * 0.4 * Math.sin(t * (0.19 + r4 * 0.13) + i);
            double y = hy + roam * 0.6 * Math.sin(t * (0.06 + r4 * 0.08) + i * 1.1)
                    + roam * 0.25 * Math.sin(t * (0.23 + r1 * 0.1) + i * 0.7);
            // a slow blink: up for a while, then down to a faint ember
            double s = 0.5 + 0.5 * Math.sin(t * (0.45 + r3 * 0.6) + i * 2.7);
            double pulse = s * s * (3 - 2 * s);
            double a = maxA * (0.12 + 0.88 * pulse) * belowTitle(y, h, 0.16, 0.32) * clearOfColumn(x, w);
            add(x, y, radius * (0.85 + 0.3 * r4), GLOW, r4 > 0.5 ? bright : col, a);
        }
    }

    // ---------------------------------------------------------------- drawing

    /**
     * Draws {@code f} through the scene shaders. Returns false (having drawn nothing) when they are unavailable; the
     * caller then draws {@link #drawRects}.
     */
    static boolean drawShaded(GuiGraphicsExtractor g, int w, int h, double t, MainMenuLook look, Flavour f) {
        if (!SceneShaders.available() || SceneShaders.simpleBg == null || SceneShaders.simpleMote == null) {
            return false;
        }
        int s = Math.max(1, net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale());
        double sw = w * (double) s;
        double sh = h * (double) s;
        plan(f, w, h, t, look);
        float[] p = SceneShaders.slot();
        p[0] = s;
        p[1] = (float) sw;
        p[2] = (float) sh;
        rgb(p, 3, baseTop);
        rgb(p, 6, baseBottom);
        for (int i = 0; i < 2; i++) {
            int o = 9 + i * 6;
            rgb(p, o, washCol[i]);
            p[o + 3] = (float) washA[i];
            p[o + 4] = (float) (washY0[i] * s);
            p[o + 5] = (float) (washY1[i] * s);
        }
        for (int i = 0; i < 3; i++) {
            int o = 21 + i * 8;
            rgb(p, o, glowCol[i]);
            p[o + 3] = (float) glowA[i];
            p[o + 4] = (float) (glowX[i] * s);
            p[o + 5] = (float) (glowY[i] * s);
            p[o + 6] = (float) (glowRx[i] * s);
            p[o + 7] = (float) (glowRy[i] * s);
        }
        boolean light = lightVignette;
        p[45] = Math.max(1, h / 5) * (float) s;
        p[46] = (light ? 0x30 : 0x80) / 255f;
        p[47] = Math.max(1, h / 4) * (float) s;
        p[48] = (light ? 0x38 : 0xA0) / 255f;
        p[49] = Math.max(1, w / 6) * (float) s;
        p[50] = (light ? 0x28 : 0x70) / 255f;
        int slot = SceneShaders.upload();
        if (slot < 0) {
            return false;
        }
        new SceneShaders.Quads(SceneShaders.simpleBg, g)
                .add(0, 0, w, h, 0, 0, (float) sw, (float) sh, 0xFF000000 | (slot << 16))
                .submit(g);
        if (n > 0) {
            SceneShaders.Quads q = new SceneShaders.Quads(SceneShaders.simpleMote, g);
            float off = slot * 16f;
            for (int i = 0; i < n; i++) {
                mote(q, px[i], py[i], pr[i], pk[i], s, off, pc[i]);
            }
            q.submit(g);
        }
        shaderFrames++;
        return true;
    }

    private static void rgb(float[] p, int i, int c) {
        p[i] = ((c >> 16) & 0xFF) / 255f;
        p[i + 1] = ((c >> 8) & 0xFF) / 255f;
        p[i + 2] = (c & 0xFF) / 255f;
    }

    /** One particle of core {@code radius} GUI units at (x, y): its box past the halo or edge by a pixel and a half, uv in
     *  core-radius units, the slot in u and the kind in v (16 apart). */
    private static void mote(SceneShaders.Quads q, double x, double y, double radius, int kind, int s, float slotU, int argb) {
        double rp = radius * s;
        if (rp < 0.25 || (argb >>> 24) == 0) {
            return;
        }
        double reach = kind == GLOW ? 7.0 : kind == HALO ? 3.6 : kind == BUBBLE ? 1.1 : 1.0;
        double e = Math.min(7.5, reach + 1.5 / rp);
        double half = radius * e;
        float kv = kind * 16f;
        q.add((float) (x - half), (float) (y - half), (float) (x + half), (float) (y + half),
                (float) (slotU - e), kv - (float) e, (float) (slotU + e), kv + (float) e, argb);
    }

    /** The same plan through {@link GuiRects} (no scene shaders): stepped glows, antialiased discs in screen pixels. */
    static void drawRects(GuiRects r, int w, int h, double t, MainMenuLook look, Flavour f) {
        plan(f, w, h, t, look);
        r.gradient(0, 0, w, h, baseTop, baseBottom);
        for (int i = 0; i < 2; i++) {
            if (washA[i] <= 0) {
                continue;
            }
            int full = MenuStyle.alpha(washCol[i], (int) Math.round(washA[i] * 255));
            int none = MenuStyle.alpha(washCol[i], 0);
            int y0 = (int) Math.round(Math.min(washY0[i], washY1[i]));
            int y1 = (int) Math.round(Math.max(washY0[i], washY1[i]));
            boolean down = washY1[i] > washY0[i];
            r.gradient(0, y0, w, y1, down ? none : full, down ? full : none);
        }
        int s = Math.max(1, net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale());
        Matrix3x2fStack pose = r.graphics().pose();
        pose.pushMatrix();
        pose.scale(1f / s, 1f / s);
        for (int i = 0; i < 3; i++) {
            Px.softGlow(GuiPx.of(r), glowX[i] * s, glowY[i] * s, glowRx[i] * s, glowRy[i] * s, glowCol[i], glowA[i]);
        }
        for (int i = 0; i < n; i++) {
            double a = (pc[i] >>> 24);
            int col = pc[i] | BLACK;
            double rad = pr[i] * s;
            if (pk[i] == HALO || pk[i] == GLOW) {
                SoftLightSmooth.aaDisc(r, px[i] * s, py[i] * s, rad * 2.6, col, a * 0.16);
                SoftLightSmooth.aaDisc(r, px[i] * s, py[i] * s, rad, MenuStyle.mix(col, WHITE, 0.3f), a);
            } else if (pk[i] == BUBBLE) {
                SoftLightSmooth.aaDisc(r, px[i] * s, py[i] * s, rad, col, a * 0.35);
                SoftLightSmooth.aaDisc(r, (px[i] - pr[i] * 0.36) * s, (py[i] - pr[i] * 0.4) * s, Math.max(0.6, rad * 0.25),
                        WHITE, a);
            } else {
                SoftLightSmooth.aaDisc(r, px[i] * s, py[i] * s, Math.max(0.6, rad), col, a);
            }
        }
        pose.popMatrix();
        SoftLightSmooth.vignette(r, w, h, lightVignette);
        rectFrames++;
    }
}

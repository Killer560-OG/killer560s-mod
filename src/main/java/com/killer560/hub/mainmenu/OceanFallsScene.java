package com.killer560.hub.mainmenu;

import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.util.ModLog;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.slf4j.Logger;

/**
 * Ocean's main menu after killer560's waterfall picture (2026-10-16: "can you do a similar thing with this new waterfall
 * one"): a dark teal Minecraft cavern, glowing water falling like rain curtains from above, blocky cliffs either side with
 * small glowing falls, mist where it all lands on a dark lake, glowing specks, a character standing on the rocks at the
 * lower left.
 * <p>
 * The base is {@code textures/gui/ocean_falls.png}, 2560x2880 (two 2560x1440 halves, high res; killer560 2026-10-16): on top the picture with its baked-in menu removed
 * ({@code killer560s-mod-logs/waterfall-picture/make_waterfall.py}) and most of its streak lines lifted out where water
 * falls; below, maps of where water falls, its streaks, where it lands and the lake ({@code make_waterfall_maps.py}). One
 * full-screen quad draws it through the {@code killer560smod:pipeline/ocean_falls} shader
 * ({@code shaders/core/ocean_falls.fsh}, sampled LINEAR, so smooth at full resolution): the whole mass of the falls rolls
 * down in turbulent sheets and surges, every streak of the picture is lit by dashes pouring down its own column at a
 * heavy-water speed, fine threads rush down in front, spray churns up where it lands and the lake glints (killer560,
 * 2026-10-16, after the first clip: "the water in that looks still"). The cliffs, the character and the land stay still.
 * If the pipeline is unavailable (VulkanMod, a failure) the plate is drawn still under the overlays.
 * <p>
 * On top, in the picture's 1920x1080 space at sub-pixel positions (the pose carries the fraction):
 * <ul>
 *   <li>bright soft streaks in front at many speeds, each ending in the mist (or on the cliff top, or above the
 *       character's head) by fading away;</li>
 *   <li>the four small cliff-side falls running, a little foam pulsing at each foot;</li>
 *   <li>mist billowing up and drifting where the falls land, over a broad band of haze that breathes;</li>
 *   <li>ripples spreading on the lake and glints coming and going on it, most of them in the reflections;</li>
 *   <li>the glowing specks on the cliffs twinkling and a few motes of spray drifting up through the air;</li>
 *   <li>a slow glow pulse at the bright feet of the falls.</li>
 * </ul>
 * The view itself never moves (no sway). Per-streak numbers are worked out once into arrays, so a frame allocates nothing
 * beyond the batch's own parts. Built on {@link PictureScene}.
 */
final class OceanFallsScene {

    private OceanFallsScene() {
    }

    static final int TW = 1920;
    static final int TH = 1080;

    /** The streaks' colors: the picture's glowing teal and a near-white head. */
    static final int TEAL = 0xFF5FF2E4;
    static final int HEAD = 0xFFCFFFF8;
    static final int MIST = 0xFF7FE6DE;

    /** How many streaks fall in the open (testkit 1690 checks the scene has them). */
    static final int STREAKS = 140;

    /** The glowing specks on the cliffs (x, y in the picture's space), found by make_waterfall's speck pass. */
    private static final int[] SPECKS = {24, 497, 49, 617, 60, 521, 76, 759, 79, 828, 133, 834, 151, 907, 154, 702, 168, 701,
            192, 925, 227, 716, 241, 499, 241, 722, 255, 652, 283, 641, 290, 659, 291, 793, 316, 519, 348, 916, 361, 930, 375,
            594, 394, 607, 411, 940, 418, 964, 431, 949, 446, 931, 449, 914, 482, 999, 484, 954, 487, 970, 493, 941, 501, 990,
            514, 992, 537, 987, 538, 947, 1754, 674, 1794, 476, 1801, 596, 1822, 603, 1841, 954};

    static final PictureScene SCENE = PictureScene.of("textures/gui/ocean_falls.png", TW, TH)
            // the haze over the whole fall breathing, and the bright feet of the falls pulsing
            .overlay(new Pulse(new double[]{
                    // cx, cy, rx, ry, alpha low, alpha high, period s
                    960, 875, 620, 46, 0.05, 0.10, 11.0,
                    1345, 862, 120, 60, 0.07, 0.16, 6.5,
                    1000, 880, 110, 46, 0.05, 0.13, 7.7,
                    1470, 742, 60, 70, 0.06, 0.15, 5.3,
                    960, 300, 520, 150, 0.025, 0.05, 13.0}, MIST))
            .overlay(new Rain(STREAKS, 340, 1640, 501))
            .overlay(new Cascades(new int[]{
                    // x0, y0, x1, y1
                    1655, 597, 1679, 704,
                    1720, 690, 1740, 806,
                    162, 541, 173, 588,
                    398, 827, 412, 880}, 521))
            .overlay(new Mist(10, 531))
            .overlay(new Lake(56, 12, 541))
            .overlay(new Specks(SPECKS, 34, 561));

    private static final Logger LOGGER = ModLog.get("killer560smod-mainmenu");
    /** The shader's loop, seconds (its sines turn a whole number of times a loop). */
    static final double LOOP = 600.0;
    static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("killer560smod", "textures/gui/ocean_falls.png");

    private static RenderPipeline pipeline;
    private static boolean failed;
    private static GpuTextureView setupView;
    private static TextureSetup setup;

    /** Test probe (testkit 1690): frames drawn through the shader, and frames drawn still because it was unavailable. */
    public static long shaderFrames;
    public static long stillFrames;

    /** Registers the pipeline at client init, so it is in RenderPipelines' static list before the shaders precompile. */
    static void register() {
        if (pipeline != null || failed) {
            return;
        }
        if (FabricLoader.getInstance().isModLoaded("vulkanmod")) {
            failed = true;
            return;
        }
        try {
            Identifier shader = Identifier.fromNamespaceAndPath("killer560smod", "core/ocean_falls");
            pipeline = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/ocean_falls"))
                    .withVertexShader(shader)
                    .withFragmentShader(shader)
                    .build());
        } catch (RuntimeException | LinkageError e) {
            fail("pipeline registration", e);
        }
    }

    private static void fail(String what, Throwable e) {
        if (!failed) {
            failed = true;
            LOGGER.warn("[MainMenu] Ocean's falls shader disabled ({} failed): {}; the picture is drawn still", what,
                    e.toString());
        }
    }

    /** Whether the shader is in use (testkit 1690). */
    public static boolean shaderActive() {
        return pipeline != null && !failed;
    }

    /** The picture through the shader (or still), before the frame's batch. */
    static void drawImage(GuiGraphicsExtractor g, int w, int h, double t) {
        if (pipeline != null && !failed) {
            try {
                AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(TEXTURE);
                GpuTextureView view = tex.getTextureView();
                if (setup == null || view != setupView) {
                    setupView = view;
                    setup = TextureSetup.singleTexture(view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                }
                g.guiRenderState.addGuiElement(new BlitRenderState(pipeline, setup, new Matrix3x2f(g.pose()), 0, 0, w, h,
                        0f, 1f, 0f, 0.5f, AmberAuroraScene.timeColour(t, LOOP), g.scissorStack.peek()));
                shaderFrames++;
                return;
            } catch (RuntimeException | LinkageError e) {
                fail("draw", e);
            }
        }
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.scale(w / (float) TW, h / (float) TH);
        g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, 0, 0, 0f, 0f, TW, TH, TW, TH, TW, TH * 2);
        pose.popMatrix();
        stillFrames++;
    }

    static void drawOverlays(GuiRects r, int w, int h, double t, float density) {
        SCENE.drawOverlays(r, w, h, t, density);
    }

    // ---- shapes --------------------------------------------------------------------------------------------------------

    /**
     * A soft falling streak from {@code yTail} (clear) to {@code yHead} (brightest) centered on x, {@code w} wide: a faint
     * glow three and a half times as wide, a core of three columns (the outer two at 45%), and a short fade below the head
     * so the head has no hard edge. One pose for the whole streak (a pose change costs the batch a matrix copy), placed so
     * x and both ends are sub-pixel: across, a unit is w/12; down, len/256.
     */
    static void fall(GuiRects r, double x, double yTail, double yHead, double w, int rgb, int head, double a) {
        double len = yHead - yTail;
        if (len < 0.75 || a < 0.01) {
            return;
        }
        Matrix3x2fStack pose = r.graphics().pose();
        int none = Px.alpha(rgb, 0);
        pose.pushMatrix();
        pose.translate((float) x, (float) yTail);
        pose.scale((float) (w / 12.0), (float) (len / 256.0));
        // glow: 42 units (3.5 w), its middle half brighter
        r.gradient(-21, 0, -10, 256, none, Px.alpha(rgb, a * 0.08));
        r.gradient(-10, 0, 10, 256, none, Px.alpha(rgb, a * 0.16));
        r.gradient(10, 0, 21, 256, none, Px.alpha(rgb, a * 0.08));
        // core: 12 units (w) in thirds
        int side = Px.alpha(rgb, a * 0.45);
        int core = Px.alpha(head, a);
        r.gradient(-6, 0, -2, 256, none, side);
        r.gradient(-2, 0, 2, 256, none, core);
        r.gradient(2, 0, 6, 256, none, side);
        // the head's soft end, below the head
        int cap = (int) Math.max(1, Math.round(Math.min(6.0, 1.5 + w * 1.6) * 256.0 / len));
        r.gradient(-2, 256, 2, 256 + cap, core, Px.alpha(head, 0));
        r.gradient(-6, 256, -2, 256 + cap, side, none);
        r.gradient(2, 256, 6, 256 + cap, side, none);
        pose.popMatrix();
    }

    /** A soft round glow with the default {@link #PUFF} knots and columns rx/4 wide (about 110 quads). */
    static void puff(GuiRects r, double cx, double cy, double rx, double ry, int rgb, double a) {
        puff(r, cx, cy, rx, ry, rgb, a, 4.0, PUFF);
    }

    /**
     * A soft round glow (Gaussian, no rings) at a sub-pixel center: columns {@code rx / perRx} wide, each a few
     * piecewise-linear vertical gradients through {@code knots} (in radii). One pose for all of it - across, a unit is an
     * eighth of a column; down, ry/64 - so it is a cheaper {@link Px#softGlow} for the many puffs of mist and halos.
     */
    static void puff(GuiRects r, double cx, double cy, double rx, double ry, int rgb, double a, double perRx, double[] knots) {
        if (rx < 1 || ry < 1 || a <= 0.003) {
            return;
        }
        double span = knots[knots.length - 1];
        double cw = rx / perRx;
        int n = (int) Math.ceil(rx * span * 2 / cw);
        Matrix3x2fStack pose = r.graphics().pose();
        pose.pushMatrix();
        pose.translate((float) (cx - n * cw / 2.0), (float) cy);
        pose.scale((float) (cw / 8.0), (float) (ry / 64.0));
        for (int i = 0; i < n; i++) {
            double u = ((i + 0.5) * cw - n * cw / 2.0) / rx;
            double ax = a * Math.exp(-u * u * 1.6);
            if (ax * 255 < 0.6) {
                continue;
            }
            int prevY = (int) Math.round(knots[0] * 64);
            double prevA = 0;
            for (int k = 1; k < knots.length; k++) {
                double v = knots[k];
                double av = k == knots.length - 1 ? 0 : ax * Math.exp(-v * v * 1.6);
                int y = (int) Math.round(v * 64);
                r.gradient(i * 8, prevY, i * 8 + 8, y, Px.alpha(rgb, prevA), Px.alpha(rgb, av));
                prevY = y;
                prevA = av;
            }
        }
        pose.popMatrix();
    }

    static final double[] PUFF = {-2.2, -1.3, -0.6, 0, 0.6, 1.3, 2.2};
    static final double[] SMALL = {-2.0, -0.7, 0, 0.7, 2.0};

    /** A tiny glowing point at a sub-pixel place, in five rectangles: a core, a soft plus round it and a faint halo. */
    static void dot(GuiRects r, double cx, double cy, double rad, int rgb, int alpha) {
        if (alpha <= 2) {
            return;
        }
        Matrix3x2fStack pose = r.graphics().pose();
        pose.pushMatrix();
        pose.translate((float) cx, (float) cy);
        pose.scale((float) (rad / 8.0), (float) (rad / 8.0));
        r.fill(-24, -24, 24, 24, Px.alpha(rgb, alpha / 12));
        r.fill(-14, -5, 14, 5, Px.alpha(rgb, alpha * 2 / 5));
        r.fill(-5, -14, 5, -5, Px.alpha(rgb, alpha * 2 / 5));
        r.fill(-5, 5, 5, 14, Px.alpha(rgb, alpha * 2 / 5));
        r.fill(-6, -6, 6, 6, Px.alpha(rgb, alpha));
        pose.popMatrix();
    }

    /** An elliptical ring (rx, ry) of {@code thick} px at a sub-pixel center, one pose for all of it (quarter-pixel
     *  units); its far half (the top, on the water) is fainter. */
    static void ring(GuiRects r, double cx, double cy, double rx, double ry, double thick, int rgb, int alpha) {
        if (alpha <= 2 || rx < 1) {
            return;
        }
        int steps = (int) Math.max(20, rx * 1.4);
        Matrix3x2fStack pose = r.graphics().pose();
        pose.pushMatrix();
        pose.translate((float) cx, (float) cy);
        pose.scale(0.25f, 0.25f);
        int hw = (int) Math.max(2, Math.round(thick * 3.2));
        int hh = (int) Math.max(2, Math.round(thick * 2));
        int near = Px.alpha(rgb, alpha / 2);
        int far = Px.alpha(rgb, (int) (alpha * 0.28));
        for (int k = 0; k < steps; k++) {
            double ang = Math.PI * 2 * k / steps;
            int px = (int) Math.round(Math.cos(ang) * rx * 4);
            int py = (int) Math.round(Math.sin(ang) * ry * 4);
            r.fill(px - hw, py - hh, px + hw, py + hh, Math.sin(ang) < 0 ? far : near);
        }
        pose.popMatrix();
    }

    static double ease(double v) {
        return PictureScene.ease(v);
    }

    static double hash(int i, int salt) {
        return MainMenuScenes.hash(i, salt);
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** Linear interpolation through (x, y) pairs sorted by x, clamped at the ends. */
    static double through(double[] pts, double x) {
        if (x <= pts[0]) {
            return pts[1];
        }
        for (int i = 2; i < pts.length; i += 2) {
            if (x <= pts[i]) {
                double f = (x - pts[i - 2]) / (pts[i] - pts[i - 2]);
                return lerp(pts[i - 1], pts[i + 1], f);
            }
        }
        return pts[pts.length - 1];
    }

    // ---- overlays ------------------------------------------------------------------------------------------------------

    /** Glows breathing between two strengths, each on its own slow eased clock. Seven numbers each (see SCENE). */
    record Pulse(double[] g, int rgb) implements PictureScene.Overlay {
        @Override
        public void draw(GuiRects r, Px p, double t, float density) {
            for (int i = 0; i < g.length; i += 7) {
                double e = ease(0.5 + 0.5 * Math.sin(t * Math.PI * 2 / g[i + 6] + i));
                puff(r, g[i], g[i + 1], g[i + 2] * (0.96 + 0.08 * e), g[i + 3], rgb, lerp(g[i + 4], g[i + 5], e));
            }
        }
    }

    /**
     * The falling water: {@code count} streaks between x0 and x1. Depth d (0 far .. 1 near) sets width, length, brightness
     * and speed. A streak falls from above the top to where it stops - the mist, the top of a cliff, or above the
     * character's head - and fades away over its last stretch there, then comes round again after a gap of its own.
     */
    static final class Rain implements PictureScene.Overlay {
        /** Where the left cliff's face starts below the open fall (x, y) and the right one's. */
        private static final double[] LEFT_CLIFF = {340, 230, 400, 440, 470, 650, 560, 790, 600, 860};
        private static final double[] RIGHT_CLIFF = {1470, 860, 1520, 760, 1590, 600, 1640, 380};

        final double[] x;
        final double[] w;
        final double[] len;
        final double[] speed;
        final double[] alpha;
        final double[] stop;
        final double[] travel;
        final double[] phase;

        Rain(int count, double x0, double x1, int salt) {
            x = new double[count];
            w = new double[count];
            len = new double[count];
            speed = new double[count];
            alpha = new double[count];
            stop = new double[count];
            travel = new double[count];
            phase = new double[count];
            for (int i = 0; i < count; i++) {
                double d = Math.pow(hash(i, salt), 1.6);
                double sx = x0 + hash(i, salt + 1) * (x1 - x0);
                double s = 858 + hash(i, salt + 2) * 40;
                if (sx < 600) {
                    s = Math.min(s, through(LEFT_CLIFF, sx));
                }
                if (sx > 1470) {
                    s = Math.min(s, through(RIGHT_CLIFF, sx));
                }
                if (sx > 580 && sx < 694) {
                    s = Math.min(s, 782);           // above the character's head
                }
                x[i] = sx;
                w[i] = 1.0 + 1.9 * d;
                len[i] = 46 + 200 * d + hash(i, salt + 3) * 70;
                speed[i] = 210 + 560 * d + hash(i, salt + 4) * 90;
                alpha[i] = 0.16 + 0.46 * d;
                stop[i] = s;
                travel[i] = s + len[i] + 60 + hash(i, salt + 5) * 520;
                phase[i] = hash(i, salt + 6) * travel[i];
            }
        }

        @Override
        public void draw(GuiRects r, Px p, double t, float density) {
            int n = density <= 0 ? x.length / 3 : (int) Math.min(x.length, Math.round(x.length * Math.max(0.35, density)));
            for (int i = 0; i < n; i++) {
                double yh = (t * speed[i] + phase[i]) % travel[i] - len[i];
                double yt = yh - len[i];
                double s = stop[i];
                if (yt >= s || yh <= 0) {
                    continue;
                }
                // fading away over its last stretch into the mist (or onto the cliff)
                double fade = 1 - 0.9 * PictureScene.ease((yh - (s - 90)) / (90 + len[i] * 0.6));
                double head = Math.min(yh, s);
                double a = alpha[i] * fade;
                fall(r, x[i], Math.max(-4, yt), head, w[i], TEAL, HEAD, a);
            }
        }
    }

    /** The small falls on the cliffs: short bright streaks running down each box fast, foam pulsing at its foot, a few
     *  drops thrown up. Four numbers a fall (x0, y0, x1, y1). */
    static final class Cascades implements PictureScene.Overlay {
        static final int PER = 9;
        final int[] box;
        final int salt;

        Cascades(int[] box, int salt) {
            this.box = box;
            this.salt = salt;
        }

        @Override
        public void draw(GuiRects r, Px p, double t, float density) {
            for (int f = 0; f < box.length; f += 4) {
                double x0 = box[f];
                double y0 = box[f + 1];
                double x1 = box[f + 2];
                double y1 = box[f + 3];
                double h = y1 - y0;
                for (int k = 0; k < PER; k++) {
                    int i = f * 16 + k;
                    double sp = 0.9 + hash(i, salt) * 0.7;              // heights a second
                    double l = h * (0.18 + hash(i, salt + 1) * 0.22);
                    double cyc = (h + l) * (1.0 + hash(i, salt + 2) * 0.5);
                    double yh = y0 + ((t * sp * h + hash(i, salt + 3) * cyc) % cyc);
                    double yt = Math.max(y0, yh - l);
                    double head = Math.min(yh, y1);
                    if (head - yt < 1) {
                        continue;
                    }
                    double sx = x0 + 1.5 + hash(i, salt + 4) * (x1 - x0 - 3);
                    double a = (0.35 + 0.4 * hash(i, salt + 5)) * (1 - 0.7 * PictureScene.ease((yh - (y1 - 10)) / (l + 10)));
                    fall(r, sx, yt, head, 1.1 + hash(i, salt + 6) * 1.1, TEAL, HEAD, a);
                }
                // foam at the foot, pulsing
                double cx = (x0 + x1) / 2;
                double e = 0.5 + 0.5 * Math.sin(t * 4.3 + f) * Math.sin(t * 2.9 + f * 1.7);
                puff(r, cx, y1 - 1, (x1 - x0) * 0.9 + 4, 5 + 2 * e, HEAD, 0.16 + 0.12 * e);
                for (int k = 0; k < 4; k++) {
                    int i = f * 16 + 10 + k;
                    double ph = (t * (0.9 + hash(i, salt + 7) * 0.8) + hash(i, salt + 8)) % 1.0;
                    double dir = k % 2 == 0 ? -1 : 1;
                    double sx = cx + dir * (2 + 10 * ph) * (0.6 + hash(i, salt + 9));
                    double sy = y1 - 2 - 4 * (7 + 6 * hash(i, salt + 10)) * ph * (1 - ph);
                    int a = (int) Math.round(170 * (1 - ph) * PictureScene.ease(ph / 0.15));
                    if (a > 4) {
                        PictureScene.softDot(r, sx, sy, 0.8, 1, HEAD, a, 0);
                    }
                }
            }
        }
    }

    /** Mist billowing where the falls land: {@code count} puffs along the foot, each rising, swelling and fading on its own
     *  clock while drifting sideways, so the band always holds about the same amount. */
    record Mist(int count, int salt) implements PictureScene.Overlay {
        @Override
        public void draw(GuiRects r, Px p, double t, float density) {
            for (int k = 0; k < count; k++) {
                double period = 8 + hash(k, salt) * 6;
                // two puffs a slot, half a cycle apart, so a slot is never empty
                for (int half = 0; half < 2; half++) {
                    double ph = (t / period + hash(k, salt + 1) + half * 0.5) % 1.0;
                    double bx = 640 + (k + 0.5) * (1000.0 / count) + (hash(k * 2 + half, salt + 2) - 0.5) * 70;
                    double x = bx + 30 * Math.sin(t * 0.11 + k * 1.9) + (ph - 0.5) * 50 * (hash(k, salt + 3) - 0.5);
                    double y = 906 - 52 * ease(ph) - hash(k, salt + 4) * 12;
                    double rx = 70 + 70 * ph + hash(k, salt + 5) * 40;
                    double ry = 20 + 22 * ph;
                    double a = 0.13 * Math.pow(Math.sin(Math.PI * ph), 1.6);
                    puff(r, x, y, rx, ry, MIST, a);
                }
            }
        }
    }

    /** The lake: glints coming and going (most in the three reflections) and drops' ripples spreading. */
    record Lake(int glints, int rings, int salt) implements PictureScene.Overlay {
        private static final double[] REFLECT = {985, 1160, 1345};
        private static final double HORIZON = 926;

        /** Not on the rocks at the lower left. */
        private static double shoreX(double y) {
            return y > 1000 ? 912 : y > 948 ? 832 : 690;
        }

        @Override
        public void draw(GuiRects r, Px p, double t, float density) {
            Matrix3x2fStack pose = r.graphics().pose();
            for (int i = 0; i < glints; i++) {
                double period = 1.8 + hash(i, salt) * 1.8;
                double cyc = t / period + hash(i, salt + 1);
                int shot = (int) Math.floor(cyc);
                double ph = cyc - shot;
                // a new place every time it comes round
                double y = HORIZON + 4 + Math.pow(hash(shot * 61 + i, salt + 2), 1.5) * 148;
                double persp = (y - HORIZON) / 150.0;
                double x;
                if (hash(shot * 61 + i, salt + 3) < 0.55) {
                    double c = REFLECT[(int) (hash(shot * 61 + i, salt + 4) * 3) % 3];
                    x = c + (hash(shot * 61 + i, salt + 5) - 0.5) * (14 + 60 * persp);
                } else {
                    x = 700 + hash(shot * 61 + i, salt + 5) * 1200;
                }
                if (x < shoreX(y)) {
                    x = shoreX(y) + (shoreX(y) - x) * 0.3;
                }
                x += (ph - 0.5) * 6 * (hash(i, salt + 6) - 0.5);
                double l = 4 + 22 * persp + hash(shot * 61 + i, salt + 7) * 10;
                double a = Math.pow(Math.sin(Math.PI * ph), 2) * (0.35 + 0.4 * hash(i, salt + 8));
                int ca = (int) Math.round(255 * a);
                if (ca < 4) {
                    continue;
                }
                double th = persp > 0.45 ? 1.6 : 1.0;
                pose.pushMatrix();
                pose.translate((float) x, (float) y);
                pose.scale((float) (l / 8.0), (float) th);
                r.fill(-4, 0, 4, 1, Px.alpha(HEAD, ca));
                r.fill(-7, 0, -4, 1, Px.alpha(TEAL, ca * 2 / 5));
                r.fill(4, 0, 7, 1, Px.alpha(TEAL, ca * 2 / 5));
                pose.popMatrix();
            }
            for (int i = 0; i < rings; i++) {
                double period = 2.4 + hash(i, salt + 20) * 1.8;
                double cyc = t / period + hash(i, salt + 21);
                int shot = (int) Math.floor(cyc);
                double f = (cyc - shot) * period / 1.6;           // a ring lives 1.6 s of its period
                if (f >= 1) {
                    continue;
                }
                double y = HORIZON + 6 + Math.pow(hash(shot * 37 + i, salt + 22), 1.3) * 140;
                double x = 720 + hash(shot * 37 + i, salt + 23) * 1150;
                if (x < shoreX(y) + 20) {
                    x = shoreX(y) + 20 + hash(i, salt + 24) * 200;
                }
                double persp = 0.35 + (y - HORIZON) / 150.0;
                double e = 1 - (1 - f) * (1 - f);
                double rx = (2 + 26 * e) * persp;
                int a = (int) Math.round(150 * (1 - f) * (1 - f) * PictureScene.ease(f / 0.08));
                ring(r, x, y, rx, rx * 0.24, 0.9 + 0.5 * persp, HEAD, a);
                if (f > 0.25) {
                    double e2 = 1 - Math.pow(1 - (f - 0.25) / 0.75, 2);
                    ring(r, x, y, (1 + 16 * e2) * persp, (1 + 16 * e2) * persp * 0.24, 0.8, TEAL, a / 2);
                }
            }
        }
    }

    /** The cliffs' glowing specks twinkling where the picture has them, and motes of spray drifting up through the air. */
    record Specks(int[] at, int motes, int salt) implements PictureScene.Overlay {
        @Override
        public void draw(GuiRects r, Px p, double t, float density) {
            for (int i = 0; i < at.length; i += 2) {
                double tw = ease(0.5 + 0.5 * Math.sin(t * (0.5 + 1.1 * hash(i, salt)) + hash(i, salt + 1) * 6.28));
                int a = (int) Math.round(255 * (0.10 + 0.55 * tw));
                dot(r, at[i] + 0.5, at[i + 1] + 0.5, 0.9 + 0.6 * tw, HEAD, a);
                puff(r, at[i] + 0.5, at[i + 1] + 0.5, 4 + 3 * tw, 4 + 3 * tw, TEAL, 0.10 * tw, 1.6, SMALL);
            }
            if (density <= 0) {
                return;
            }
            for (int i = 0; i < motes; i++) {
                double period = 7 + hash(i, salt + 2) * 7;
                double cyc = t / period + hash(i, salt + 3);
                int shot = (int) Math.floor(cyc);
                double ph = cyc - shot;
                double x0 = 380 + hash(shot * 17 + i, salt + 4) * 1200;
                double y0 = 640 + hash(shot * 17 + i, salt + 5) * 300;
                double x = x0 + Math.sin(t * 0.7 + i * 2.1) * 9 + (hash(i, salt + 6) - 0.5) * 60 * ph;
                double y = y0 - ph * (90 + 120 * hash(i, salt + 7));
                double flick = 0.6 + 0.4 * Math.sin(t * (1.3 + hash(i, salt + 8)) + i);
                int a = (int) Math.round(230 * Math.pow(Math.sin(Math.PI * ph), 1.5) * flick);
                if (a > 4) {
                    PictureScene.softDot(r, x, y, 0.8 + 0.7 * hash(i, salt + 9), 1, HEAD, a, 0);
                }
            }
        }
    }
}

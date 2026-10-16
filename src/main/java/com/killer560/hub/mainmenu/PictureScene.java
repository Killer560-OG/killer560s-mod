package com.killer560.hub.mainmenu;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.hud.GuiRects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;

/**
 * A main menu background made from a picture someone painted, animated in code (killer560, 2026-10-16: "I will create them
 * and just let you animate them"). One {@link PictureScene} is a texture plus what moves on it:
 * <ul>
 *   <li>{@link Patch}: a small texture blitted over the base - baked-in UI cleaned off, or a variant (the Milky Way without
 *       its character);</li>
 *   <li>{@link Ripple}: a region of the picture redrawn as small tiles whose source is offset by a travelling sine that is
 *       zero where the region is attached (shoulders, a crown, a waterline) - cloth, hair, water;</li>
 *   <li>{@link Overlay}s drawn over it in the picture's own space: a pulsing glow, falling streaks, a splash, shimmer
 *       drifting across water, drifting fog, rising motes, twinkling stars, a shooting star;</li>
 *   <li>an optional slow side-to-side sway of the whole view.</li>
 * </ul>
 * Everything is a function of the clock; nothing is allocated per frame beyond the batch's own parts (the specs are built
 * once into arrays). {@link TargetScene} (Green's designer picture) and {@link MilkyWayTexScene} (Dark's Milky Way) are
 * the two pictures so far. To add one: put the PNG under {@code assets/killer560smod/textures/gui/}, describe it with
 * {@link #of}, and draw it from {@link MainMenuScenes} with {@link #drawImage} before the frame's batch and
 * {@link #drawOverlays} into it.
 */
final class PictureScene {

    final Identifier texture;
    final int tw;
    final int th;
    private double swayPeriod;
    private double swayPx;
    private Patch[] patches = new Patch[0];
    private Ripple[] ripples = new Ripple[0];
    private Overlay[] overlays = new Overlay[0];
    /** Tiles the ripples redrew in the last frame (the testkit's proof that cloth or hair moved). */
    int rippleTiles;

    private PictureScene(Identifier texture, int tw, int th) {
        this.texture = texture;
        this.tw = tw;
        this.th = th;
    }

    static PictureScene of(String path, int tw, int th) {
        return new PictureScene(Identifier.fromNamespaceAndPath("killer560smod", path), tw, th);
    }

    PictureScene sway(double periodSeconds, double px) {
        swayPeriod = periodSeconds;
        swayPx = px;
        return this;
    }

    PictureScene patch(String path, int x, int y, int w, int h) {
        patches = append(patches, new Patch(Identifier.fromNamespaceAndPath("killer560smod", path), x, y, w, h));
        return this;
    }

    PictureScene ripple(Ripple r) {
        ripples = append(ripples, r);
        return this;
    }

    PictureScene overlay(Overlay o) {
        overlays = append(overlays, o);
        return this;
    }

    private static <T> T[] append(T[] a, T v) {
        T[] b = java.util.Arrays.copyOf(a, a.length + 1);
        b[a.length] = v;
        return b;
    }

    /** Puts the pose into the picture's space (stretched over the screen); with a sway, swayed and a touch enlarged so the
     *  sway never shows an edge. */
    void enter(Matrix3x2fStack pose, int w, int h, double t) {
        pose.pushMatrix();
        pose.scale(w / (float) tw, h / (float) th);
        if (swayPeriod > 0) {
            float sway = (float) (Math.sin(t * Math.PI * 2 / swayPeriod) * swayPx);
            float mx = (float) (swayPx * 1.4);
            float my = (float) (swayPx * 0.8);
            pose.translate(-mx + sway, -my);
            pose.scale((tw + 2 * mx) / tw, (th + 2 * my) / th);
        }
    }

    /** The picture, its patches and its ripples, before the frame's batch. */
    void drawImage(GuiGraphicsExtractor g, int w, int h, double t) {
        Matrix3x2fStack pose = g.pose();
        enter(pose, w, h, t);
        g.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0f, 0f, tw, th, tw, th, tw, th);
        for (Patch p : patches) {
            g.blit(RenderPipelines.GUI_TEXTURED, p.texture, p.x, p.y, 0f, 0f, p.w, p.h, p.w, p.h, p.w, p.h);
        }
        int tiles = 0;
        for (Ripple r : ripples) {
            tiles += r.draw(g, texture, tw, th, t);
        }
        rippleTiles = tiles;
        pose.popMatrix();
    }

    /** What moves over the picture, into the frame's batch. */
    void drawOverlays(GuiRects r, int w, int h, double t, float density) {
        Matrix3x2fStack pose = r.graphics().pose();
        enter(pose, w, h, t);
        Px p = GuiPx.of(r);
        for (Overlay o : overlays) {
            o.draw(r, p, t, density);
        }
        pose.popMatrix();
    }

    static double clamp(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    static double hash(int i, int salt) {
        return MainMenuScenes.hash(i, salt);
    }

    // ---- pieces --------------------------------------------------------------------------------------------------------

    record Patch(Identifier texture, int x, int y, int w, int h) {
    }

    /**
     * A region redrawn as {@code tile}-sized pieces of the picture, each sampled {@code (dx, dy)} away, where
     * <pre>
     *   weight = attach * (base + (1 - base) * clamp((cy - y0Second) / reachSecond)) * fade
     *   dx = amp  * weight * sin(t * fx - cx * kxx + cy * kxy)
     *   dy = ampY * weight * sin(t * fy + cx * kyx)
     * </pre>
     * {@code attach} rises from 0 at the attached edge ({@code anchor}, {@code inset} px in) over {@code reach} px; {@code fade}
     * rises from 0 over the given margins at the region's four edges. Build with {@link #ripple}.
     */
    static final class Ripple {
        enum Anchor { LEFT, RIGHT, TOP, BOTTOM }

        final int x0;
        final int y0;
        final int x1;
        final int y1;
        int tile = 4;
        Anchor anchor = Anchor.TOP;
        double inset;
        double reach = 1;
        double base = 1;
        double y0Second;
        double reachSecond = 1;
        double fadeL = 1;
        double fadeT = 1;
        double fadeB = 1;
        double fadeR = 1;
        double amp = 3;
        double fx = 1;
        double kxx;
        double kxy;
        double ampY;
        double fy = 1;
        double kyx;

        Ripple(int x0, int y0, int x1, int y1) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
        }

        Ripple tile(int tile) {
            this.tile = tile;
            return this;
        }

        Ripple attached(Anchor anchor, double inset, double reach) {
            this.anchor = anchor;
            this.inset = inset;
            this.reach = reach;
            return this;
        }

        /** A second, vertical ramp: the weight runs from {@code base} at {@code fromY} to 1 over {@code reach} px. */
        Ripple ramp(double base, double fromY, double reach) {
            this.base = base;
            this.y0Second = fromY;
            this.reachSecond = reach;
            return this;
        }

        Ripple fade(double left, double top, double bottom, double right) {
            fadeL = left;
            fadeT = top;
            fadeB = bottom;
            fadeR = right;
            return this;
        }

        Ripple waveX(double amp, double freq, double kx, double ky) {
            this.amp = amp;
            this.fx = freq;
            this.kxx = kx;
            this.kxy = ky;
            return this;
        }

        Ripple waveY(double ampY, double freq, double kx) {
            this.ampY = ampY;
            this.fy = freq;
            this.kyx = kx;
            return this;
        }

        int draw(GuiGraphicsExtractor g, Identifier texture, int tw, int th, double t) {
            int n = 0;
            for (int y = y0; y < y1; y += tile) {
                for (int x = x0; x < x1; x += tile) {
                    double cx = x + tile * 0.5;
                    double cy = y + tile * 0.5;
                    double fade = clamp((cx - x0) / fadeL) * clamp((cy - y0) / fadeT) * clamp((y1 - cy) / fadeB)
                            * clamp((x1 - cx) / fadeR);
                    double attach = switch (anchor) {
                        case RIGHT -> clamp((x1 - inset - cx) / reach);
                        case LEFT -> clamp((cx - x0 - inset) / reach);
                        case TOP -> clamp((cy - y0 - inset) / reach);
                        case BOTTOM -> clamp((y1 - inset - cy) / reach);
                    };
                    double wgt = attach * (base + (1 - base) * clamp((cy - y0Second) / reachSecond)) * fade;
                    double dx = amp * wgt * Math.sin(t * fx - cx * kxx + cy * kxy);
                    double dy = ampY == 0 ? 0 : ampY * wgt * Math.sin(t * fy + cx * kyx);
                    if (Math.abs(dx) < 0.05 && Math.abs(dy) < 0.05) {
                        continue;
                    }
                    g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, (float) (x - dx), (float) (y - dy), tile, tile, tile, tile,
                            tw, th);
                    n++;
                }
            }
            return n;
        }
    }

    static Ripple ripple(int x0, int y0, int x1, int y1) {
        return new Ripple(x0, y0, x1, y1);
    }

    /** Something drawn over the picture each frame, in the picture's space. */
    interface Overlay {
        void draw(GuiRects r, Px p, double t, float density);
    }

    /**
     * A glow whose strength (and optionally size and position) follows {@code v = c0 + c1 * sin(t * freq)}: alpha
     * {@code a0 + aK * v}, horizontal radius {@code rx + rxK * v}, centre x drifting by {@code driftAmp * sin(t * driftFreq)}.
     * Stepped ({@link MainMenuScenes#glow}, the older pictures) or soft ({@link Px#softGlow}, no rings).
     */
    record Glow(int cx, int cy, double rx, double ry, int rgb, double c0, double c1, double freq, int a0, int aK, double rxK,
                double driftAmp, double driftFreq, int layers, boolean soft) implements Overlay {
        @Override
        public void draw(GuiRects r, Px p, double t, float density) {
            double v = c0 + c1 * Math.sin(t * freq);
            int x = driftAmp == 0 ? cx : (int) (cx + driftAmp * Math.sin(t * driftFreq));
            int alpha = (int) (a0 + aK * v);
            if (soft) {
                // eased between its low and high (no linger-then-jump at the turns), unrounded
                double e = c1 == 0 ? v : ease((v - (c0 - c1)) / (2 * c1)) * (2 * c1) + (c0 - c1);
                double xs = cx + driftAmp * Math.sin(t * driftFreq);
                Px.softGlow(p, xs, cy, rx + rxK * e, ry, rgb, (a0 + aK * e) / 255.0);
            } else if (!coneGlow(r, cx + driftAmp * Math.sin(t * driftFreq), cy, rx + rxK * v, ry, rgb, a0 + aK * v, layers)) {
                MainMenuScenes.glow(r, x, cy, rx + rxK * v, ry, rgb, alpha, layers);
            }
        }
    }

    /**
     * The stepped glow ({@link MainMenuScenes#glow}: {@code layers} nested ellipses of {@code alpha / layers} each) drawn by
     * {@link SceneShaders}' {@code scene_cone_glow} as the same falloff without its steps (killer560, 2026-10-16: Viridian
     * "super high res"): the stair of ellipses averaged into one line from the centre to a half step past the outer
     * ellipse, at a sub-pixel centre, its alpha unrounded in time and dithered with blue noise in space, so the slime's
     * pulse and the drifting fog show no rings and no one-pixel hops. The batch so far is submitted first so the glow
     * stays in its place in the drawing order. False (nothing drawn) without the shaders.
     */
    static boolean coneGlow(GuiRects r, double cx, double cy, double rx, double ry, int rgb, double alpha, int layers) {
        if (!SceneShaders.available() || !SceneShaders.ensure()) {
            return false;
        }
        if (rx < 1 || ry < 1 || alpha <= 0 || layers <= 0) {
            return true;
        }
        double per = Math.max(1, alpha / layers) / 255.0;
        double peak = 1 - Math.pow(1 - per, layers);
        double c = 1 + 0.5 / layers;
        GuiGraphicsExtractor g = r.graphics();
        r.submit();
        new SceneShaders.Quads(SceneShaders.coneGlow, g)
                .add((float) (cx - rx * c), (float) (cy - ry * c), (float) (cx + rx * c), (float) (cy + ry * c), -1f, -1f, 1f, 1f,
                        MenuStyle.alpha(rgb, (int) Math.round(peak * 255)))
                .submit(g);
        return true;
    }

    /** Streaks falling down a band (a waterfall): {@code count} of them between x and x + width, top to bottom. */
    record Streaks(int count, int x, int width, int top, int bottom, double speed, double speedRange, double len,
                   double lenRange, int rgb, int darkRgb, int salt) implements Overlay {
        @Override
        public void draw(GuiRects r, Px p, double t, float density) {
            int span = bottom - top;
            for (int i = 0; i < count; i++) {
                double u = hash(i, salt);
                double sp = speed + hash(i, salt + 1) * speedRange;
                double sl = len + hash(i, salt + 2) * lenRange;
                double travel = span + sl;
                double pos = (t * sp + hash(i, salt + 3) * travel * 9) % travel;
                double y1 = Math.min(bottom, top + pos);
                double y0 = Math.max(top, top + pos - sl);
                if (y1 - y0 < 3) {
                    continue;
                }
                int sx = (int) (x + u * width);
                int sw = hash(i, salt + 4) > 0.6 ? 3 : 2;
                int c = i % 6 == 0 ? MenuStyle.alpha(darkRgb, 0x50) : MenuStyle.alpha(rgb, 0x40 + (int) (hash(i, salt + 5) * 0x70));
                // at its sub-pixel place (2026-10-16, 120 fps): the gradient slides with the water instead of hopping rows
                Matrix3x2fStack pose = r.graphics().pose();
                pose.pushMatrix();
                pose.translate(0f, (float) y0);
                pose.scale(1f, (float) ((y1 - y0) / 64.0));
                r.gradient(sx, 0, sx + sw, 64, MenuStyle.alpha(c, 0), c);
                pose.popMatrix();
            }
        }
    }

    /** Droplets thrown up in little arcs where water lands. */
    record Splash(int count, int cx, int cy, double spread, double thrown, double height, double heightRange, double speed,
                  int size, int rgb, int salt, boolean soft) implements Overlay {
        @Override
        public void draw(GuiRects r, Px p, double t, float density) {
            for (int i = 0; i < count; i++) {
                double ph = (t * (speed + hash(i, salt)) + hash(i, salt + 1)) % 1.0;
                double dir = hash(i, salt + 2) < 0.5 ? -1 : 1;
                double x = cx + (hash(i, salt + 3) - 0.5) * spread + dir * thrown * ph;
                double y = cy - 4 * (height + hash(i, salt + 4) * heightRange) * ph * (1 - ph);
                int a = (int) (0xC0 * (1 - ph));
                if (a > 8) {
                    if (soft) {
                        softDot(r, x, y, 1.4, 1, rgb, a, 0);
                    } else {
                        r.fill((int) x, (int) y, (int) x + size, (int) y + size, MenuStyle.alpha(rgb, a));
                    }
                }
            }
        }
    }

    /** Short bright lines drifting sideways across water. */
    record Shimmer(int count, int x, int span, int y, int yRange, double len, double lenRange, double speed, double speedRange,
                   int rgb, int salt) implements Overlay {
        @Override
        public void draw(GuiRects r, Px p, double t, float density) {
            for (int i = 0; i < count; i++) {
                double d = hash(i, salt);
                int ly = y + (int) (d * yRange);
                double l = (len + hash(i, salt + 1) * lenRange) * (0.5 + d);
                double lx = x + ((hash(i, salt + 2) * span + t * (speed + hash(i, salt + 3) * speedRange)) % span);
                double a = 0x18 + 0x30 * (0.5 + 0.5 * Math.sin(t * 1.4 + i * 2.3));
                int lh = d > 0.5 ? 2 : 1;
                // antialiased ends (2026-10-16, 120 fps): a line drifting 8-22 px/s hopped a whole pixel every few frames;
                // its end pixels now carry their partial coverage, so it glides
                double x1 = lx + l;
                int in0 = (int) Math.ceil(lx);
                int in1 = (int) Math.floor(x1);
                if (in1 > in0) {
                    r.fill(in0, ly, in1, ly + lh, MenuStyle.alpha(rgb, (int) Math.round(a)));
                    int ea = (int) Math.round(a * (in0 - lx));
                    if (ea > 0) {
                        r.fill(in0 - 1, ly, in0, ly + lh, MenuStyle.alpha(rgb, ea));
                    }
                    int eb = (int) Math.round(a * (x1 - in1));
                    if (eb > 0) {
                        r.fill(in1, ly, in1 + 1, ly + lh, MenuStyle.alpha(rgb, eb));
                    }
                }
            }
        }
    }

    /** Banks of fog drifting across: {@code count} glows, the k-th at y + k * yStep moving at speed + k * speedStep. */
    record Fog(int count, int tw, int y, int yStep, double rx, double ry, double speed, double speedStep, int rgb, int alpha,
               int layers, boolean soft, int salt) implements Overlay {
        @Override
        public void draw(GuiRects r, Px p, double t, float density) {
            for (int k = 0; k < count; k++) {
                double span = tw * 1.6;
                double fx = ((t * (speed + k * speedStep) + hash(k, salt) * span) % span) - tw * 0.3;
                int fy = y + k * yStep;
                if (soft) {
                    Px.softGlow(p, fx, fy, rx, ry, rgb, alpha / 255.0);
                } else if (!coneGlow(r, fx, fy, rx, ry, rgb, alpha, layers)) {
                    MainMenuScenes.glow(r, (int) fx, fy, rx, ry, rgb, alpha, layers);
                }
            }
        }
    }

    /** Motes rising in two side bands (x0..x0+w0 and x1..x1+w1), from y up by rise, fading in and out and flickering. */
    record Motes(int count, int x0, int w0, int x1, int w1, int y, int rise, double speed, double speedRange, int size,
                 int rgb, int salt, boolean soft) implements Overlay {
        @Override
        public void draw(GuiRects r, Px p, double t, float density) {
            for (int i = 0; i < count; i++) {
                double ph = (t * (speed + hash(i, salt) * speedRange) + hash(i, salt + 1)) % 1.0;
                double fx = hash(i, salt + 2);
                double x = (i % 2 == 0 ? x0 + fx * w0 : x1 + fx * w1) + Math.sin(t * 0.6 + i) * 10;
                double my = y - ph * rise;
                int a = (int) (0xB0 * Math.sin(Math.PI * ph) * (0.5 + 0.5 * Math.sin(t * 1.7 + i * 3.1)));
                if (a > 8) {
                    if (soft) {
                        softDot(r, x, my, 1.4, 1, rgb, a, 0);
                    } else {
                        r.fill((int) x, (int) my, (int) x + size, (int) my + size, MenuStyle.alpha(rgb, a));
                    }
                }
            }
        }
    }

    /** 0..1 with zero slope at both ends (no pop when something starts or stops fading). */
    static double ease(double v) {
        v = clamp(v);
        return v * v * (3 - 2 * v);
    }

    /**
     * Stars twinkling smoothly (killer560, 2026-10-16: "make the whole thing a bit smoother ... the pulsating lights", then
     * "make the stars pulsate a bit more"): the bright ones at {@code stars} (x, y pairs found in the picture) breathe
     * clearly - a soft round glow growing and dimming with a soft cross whose arms taper away; the medium ones at
     * {@code mids} shimmer as soft dots over the painted ones; and {@code faint} small soft dots at sub-pixel places above
     * {@code skyBottom} fade in and out on their own slow clocks, none inside {@code calm} (x0, y0, x1, y1: the title).
     * Every brightness follows an eased curve, so nothing pops.
     */
    record Twinkles(int[] stars, int[] mids, int faint, int skyBottom, int[] calm, int rgb, int salt) implements Overlay {
        @Override
        public void draw(GuiRects r, Px p, double t, float density) {
            if (density <= 0) {
                return;
            }
            for (int i = 0; i < stars.length; i += 2) {
                double x = stars[i] + 0.5;
                double y = stars[i + 1] + 0.5;
                double tw = ease(0.5 + 0.5 * Math.sin(t * (0.5 + 1.0 * hash(i, salt + 1)) + hash(i, salt) * 6.28));
                int a = (int) Math.round(255 * (0.08 + 0.80 * tw));
                softDot(r, x, y, 1.0 + 1.3 * tw, 1, rgb, a, 0.3 + 0.7 * tw);
                cross(r, x, y, 5 + 12 * tw, rgb, (int) Math.round(a * 0.5));
            }
            for (int i = 0; i < mids.length; i += 2) {
                double x = mids[i] + 0.5;
                double y = mids[i + 1] + 0.5;
                double tw = ease(0.5 + 0.5 * Math.sin(t * (0.6 + 1.4 * hash(i, salt + 5)) + hash(i, salt + 4) * 6.28));
                int a = (int) Math.round(255 * 0.62 * tw * tw);
                if (a > 2) {
                    softDot(r, x, y, 0.9 + 0.6 * tw, 1, rgb, a, 0);
                }
            }
            for (int i = 0; i < faint; i++) {
                double x = hash(i, salt + 10) * 1920;
                double y = hash(i, salt + 11) * skyBottom;
                if (x > calm[0] && x < calm[2] && y > calm[1] && y < calm[3]) {
                    continue;
                }
                double s = Math.sin(t * (0.3 + 0.8 * hash(i, salt + 12)) + i * 2.3);
                if (s <= 0) {
                    continue;
                }
                int a = (int) Math.round(255 * 0.65 * s * s);
                if (a > 2) {
                    softDot(r, x, y, 0.8 + 0.7 * hash(i, salt + 13), 1, rgb, a, 0);
                }
            }
        }
    }

    /** A soft cross at (cx, cy): four arms one pixel wide whose alpha falls smoothly to nothing over {@code arm} px. */
    static void cross(GuiRects r, double cx, double cy, double arm, int rgb, int alpha) {
        if (alpha <= 2) {
            return;
        }
        Matrix3x2fStack pose = r.graphics().pose();
        pose.pushMatrix();
        pose.translate((float) cx, (float) cy);
        int c = MenuStyle.alpha(rgb, alpha);
        int none = MenuStyle.alpha(rgb, 0);
        for (int k = 0; k < 4; k++) {
            pose.pushMatrix();
            pose.rotate((float) (Math.PI / 2 * k));
            pose.translate(-0.5f, 0f);
            pose.scale(1f, (float) (arm / 32.0));
            // a vertical arm from the centre (full) to its tip (clear), half a pixel either side of the axis
            r.gradient(0, -32, 1, 0, none, c);
            pose.popMatrix();
        }
        pose.popMatrix();
    }

    /**
     * A shooting star every {@code period} s or so, starting at a new place each time in the left or right sky band (never
     * across the middle, where the title and buttons are): a soft head and a tail that fades smoothly to nothing, both at
     * sub-pixel positions, the whole of it easing in and out.
     */
    record Meteor(double period, int tw, int th, double width, int rgb, int salt) implements Overlay {
        @Override
        public void draw(GuiRects r, Px p, double t, float density) {
            if (density <= 0) {
                return;
            }
            int shot = (int) Math.floor(t / period);
            double life = 1.1;
            double q = (t - shot * period - hash(shot, salt) * 2.5) / life;
            if (q < 0 || q > 1) {
                return;
            }
            boolean left = hash(shot, salt + 1) < 0.5;
            double sx = tw * (left ? 0.03 + 0.20 * hash(shot, salt + 2) : 0.70 + 0.22 * hash(shot, salt + 2));
            double sy = th * (0.05 + 0.25 * hash(shot, salt + 3));
            double ang = Math.toRadians(left ? 22 + 25 * hash(shot, salt + 4) : 158 - 25 * hash(shot, salt + 4));
            double dist = 260 + 120 * hash(shot, salt + 5);
            double ease = 1 - Math.pow(1 - q, 1.6);
            double hx = sx + Math.cos(ang) * dist * ease;
            double hy = sy + Math.sin(ang) * dist * ease;
            double fade = ease(q / 0.25) * ease((1 - q) / 0.45);
            double tail = 150 * ease(q / 0.35);
            int a = (int) Math.round(255 * fade);
            if (a <= 2) {
                return;
            }
            Matrix3x2fStack pose = r.graphics().pose();
            pose.pushMatrix();
            pose.translate((float) hx, (float) hy);
            // rotate so that +y points back along the tail
            pose.rotate((float) (ang + Math.PI / 2));
            int none = MenuStyle.alpha(rgb, 0);
            // a wide faint glow and a thin bright core, both fading to nothing at the tail's end
            pose.pushMatrix();
            pose.scale((float) (width * 3), (float) (tail / 64.0));
            r.gradient(-1, 0, 1, 64, MenuStyle.alpha(rgb, (int) (a * 0.12)), none);
            pose.popMatrix();
            pose.pushMatrix();
            pose.scale((float) width, (float) (tail / 64.0));
            r.gradient(-1, 0, 1, 64, MenuStyle.alpha(rgb, (int) (a * 0.75)), none);
            pose.popMatrix();
            pose.popMatrix();
            softDot(r, hx, hy, width * 0.9, 1, rgb, a, 0.8);
        }
    }

    /**
     * Glowing droplets dripping from a ceiling (Green's cave, killer560 2026-10-16). Each of {@code count} sources has a place
     * - the first {@code mid} faint and far in the middle band, then alternately the left and right bands (a right-band one
     * inside skip0..skip1 moved {@code skipBy} on) - a depth (0 far: small and faint; 1 near: bigger, brighter, with a glow)
     * and a period; within the period a drop forms (swelling and stretching), falls under {@code gravity} with a streak as
     * long as its speed, then rings out on the water at {@code lakeY} or splats on the ledge ({@code ledgeY}, x below
     * {@code ledgeEnd}).
     */
    record Drips(int count, int mid, double midX, double midW, double leftX, double leftW, double rightX, double rightW,
                 double skip0, double skip1, double skipBy, double ceilY, double ceilRange, double gravity, double ledgeEnd,
                 double ledgeY, double lakeY, int rgb, int salt) implements Overlay {
        @Override
        public void draw(GuiRects r, Px p, double t, float density) {
            for (int i = 0; i < count; i++) {
                double hx = MainMenuScenes.hash(i, salt + 0);
                double x;
                double depth;
                if (i < mid) {
                    // Two faint far ones through the middle, behind the menu.
                    x = midX + hx * midW;
                    depth = 0;
                } else if (i % 2 == 0) {
                    x = leftX + hx * leftW;      // the left: over the ledge and the near lake
                    depth = MainMenuScenes.hash(i, salt + 1);
                } else {
                    x = rightX + hx * rightW;    // the right: either side of the waterfall
                    if (x > skip0 && x < skip1) {
                        x += skipBy;
                    }
                    depth = MainMenuScenes.hash(i, salt + 1);
                }
                double ceil = ceilY + MainMenuScenes.hash(i, salt + 2) * ceilRange;
                double form = 1.4 + MainMenuScenes.hash(i, salt + 3) * 2.2;
                // 1130 = 2300 x 0.49: drops land at 70% of the old speed and take ~1.43x as long ("falling a bit too fast").
                double g = gravity * (0.7 + 0.3 * depth);
                boolean ledge = x < ledgeEnd;
                double land = ledge ? ledgeY : lakeY;
                double fall = Math.sqrt(2.0 * (land - ceil) / g);
                double splash = 0.55;
                double period = form + fall + splash + MainMenuScenes.hash(i, salt + 4) * 2.0 + 0.8; // longer gaps between drips (was 0..1.5 s)
                double tt = (t + MainMenuScenes.hash(i, salt + 5) * period) % period;
                double radius = 1.1 + depth * 2.6;
                int alpha = (int) (0x80 + 0x7F * depth);
                if (tt < form) {
                    // Forming: a bead swelling on the rock, then stretching before it lets go.
                    double f = tt / form;
                    double rr = radius * (0.35 + 0.65 * f);
                    double stretch = 1 + 0.9 * f * f * f;
                    softDot(r, x, ceil + rr * stretch, rr, stretch, rgb, alpha, depth);
                } else if (tt < form + fall) {
                    double ft = tt - form;
                    double y = ceil + 0.5 * g * ft * ft;
                    double v = g * ft;
                    double streak = Math.min(77, v * 0.026 * (0.6 + 0.4 * depth));
                    streak(r, x, y - streak, y, radius * 0.9, rgb, alpha / 2);
                    softDot(r, x, y, radius, 1.35, rgb, alpha, depth);
                } else {
                    double f = (tt - form - fall) / splash;
                    if (f > 1) {
                        continue;
                    }
                    int a = (int) (alpha * (1 - f) * (1 - f));
                    double e = 1 - (1 - f) * (1 - f); // eases out
                    if (ledge) {
                        // A splat: a flattened blob spreading and fading, a few specks thrown out.
                        softDot(r, x, land - 1, radius * (1.3 + 2.0 * e), 0.35, rgb, a, depth);
                        for (int k = 0; k < 4; k++) {
                            double dir = (k < 2 ? -1 : 1) * (0.6 + 0.5 * MainMenuScenes.hash(i * 4 + k, salt + 6));
                            double sx = x + dir * 26 * e * (0.6 + depth);
                            double sy = land - 3 - 26 * f * (1 - f) * (0.6 + depth) * (0.7 + 0.6 * MainMenuScenes.hash(i * 4 + k, salt + 7));
                            softDot(r, sx, sy, 0.8 + depth * 0.8, 1, rgb, a, 0);
                        }
                    } else {
                        // A ripple ring spreading on the water, a second one behind it, and a little rebound drop.
                        double rw = (5 + 46 * e) * (0.5 + depth);
                        ring(r, x, land + 2, rw, rw * 0.22, 1.2 + depth, rgb, a);
                        if (f > 0.2) {
                            double f2 = (f - 0.2) / 0.8;
                            double e2 = 1 - (1 - f2) * (1 - f2);
                            double rw2 = (3 + 30 * e2) * (0.5 + depth);
                            ring(r, x, land + 2, rw2, rw2 * 0.22, 1, rgb, (int) (a * 0.6));
                        }
                        if (f < 0.6) {
                            double up = Math.sin(Math.PI * f / 0.6) * 20 * (0.5 + depth);
                            softDot(r, x, land - up, radius * 0.6, 1.2, rgb, a, 0);
                        }
                    }
                }
            }
        }
    }

    // ---- smooth little shapes (sub-pixel placed, antialiased) ----------------------------------------------------------

    /**
     * An antialiased disc of {@code radius} (stretched vertically by {@code stretch}) centred on a sub-pixel position: the
     * pose is moved by the fraction, rows are spans whose edge pixels carry the partial coverage, and a soft halo of
     * stacked faint discs surrounds it for the near drops ({@code glow} 0..1).
     */
    static void softDot(GuiRects r, double cx, double cy, double radius, double stretch, int rgb, int alpha,
                                double glow) {
        Matrix3x2fStack pose = r.graphics().pose();
        pose.pushMatrix();
        pose.translate((float) cx, (float) cy);
        if (glow > 0.35) {
            int halo = (int) (0x1A * glow);
            for (int k = 3; k >= 1; k--) {
                disc(r, radius * (1 + k * 1.1), stretch, rgb, halo);
            }
        }
        disc(r, radius, stretch, rgb, alpha);
        pose.popMatrix();
    }

    /** A disc at the pose's origin, in quarter-pixel rows so a small one still reads round. */
    static void disc(GuiRects r, double radius, double stretch, int rgb, int alpha) {
        Matrix3x2fStack pose = r.graphics().pose();
        pose.pushMatrix();
        pose.scale(0.25f, (float) (0.25 * stretch));
        double big = radius * 4;
        int rows = (int) Math.ceil(big);
        for (int dy = -rows; dy < rows; dy++) {
            double mid = dy + 0.5;
            double q = big * big - mid * mid;
            if (q <= 0) {
                continue;
            }
            double half = Math.sqrt(q);
            int core = (int) Math.floor(half);
            double frac = half - core;
            if (core > 0) {
                r.fill(-core, dy, core, dy + 1, MenuStyle.alpha(rgb, alpha));
            }
            int ea = (int) (alpha * frac);
            if (ea > 2) {
                r.fill(-core - 1, dy, -core, dy + 1, MenuStyle.alpha(rgb, ea));
                r.fill(core, dy, core + 1, dy + 1, MenuStyle.alpha(rgb, ea));
            }
        }
        pose.popMatrix();
    }

    /** A soft trail from y0 (clear) to y1 (alpha), {@code width} wide, sub-pixel placed, faded at its sides. */
    static void streak(GuiRects r, double x, double y0, double y1, double width, int rgb, int alpha) {
        if (y1 - y0 < 1) {
            return;
        }
        Matrix3x2fStack pose = r.graphics().pose();
        pose.pushMatrix();
        pose.translate((float) (x - width / 2), (float) y0);
        pose.scale((float) (width / 8), (float) ((y1 - y0) / 64));
        for (int k = 0; k < 8; k++) {
            double edge = 1 - Math.abs(k - 3.5) / 4.0;
            int a = (int) (alpha * edge);
            r.gradient(k, 0, k + 1, 64, MenuStyle.alpha(rgb, 0), MenuStyle.alpha(rgb, a));
        }
        pose.popMatrix();
    }

    /** An antialiased elliptical ring (rx, ry) of {@code thick} pixels, centred on a sub-pixel position; its far half
     *  (the top, on the water) is fainter. */
    static void ring(GuiRects r, double cx, double cy, double rx, double ry, double thick, int rgb, int alpha) {
        if (alpha <= 2 || rx < 1) {
            return;
        }
        int steps = (int) Math.max(24, rx * 1.6);
        Matrix3x2fStack pose = r.graphics().pose();
        for (int k = 0; k < steps; k++) {
            double a0 = Math.PI * 2 * k / steps;
            int al = (int) (alpha * (Math.sin(a0) < 0 ? 0.55 : 1.0));
            pose.pushMatrix();
            pose.translate((float) (cx + Math.cos(a0) * rx), (float) (cy + Math.sin(a0) * ry));
            pose.scale((float) (thick * 1.6), (float) thick);
            r.fill(-1, -1, 1, 1, MenuStyle.alpha(rgb, al / 2));
            pose.popMatrix();
        }
    }
}

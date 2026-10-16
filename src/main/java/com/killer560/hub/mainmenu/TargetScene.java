package com.killer560.hub.mainmenu;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.hud.GuiRects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;

/**
 * The Green theme's main menu as the theme's designer drew it (2026-10-16, relayed by killer560: "match THIS image almost
 * exactly ... make her hair flow a little bit and the cape flow and whatnot ... a rough draw-up of the real animation").
 * <p>
 * The base is the designer's own picture (shipped as {@code textures/gui/green_boss_target.png}, 1920x1080, with the title,
 * buttons and "Playing as" chip it had baked in filled from the surrounding fog, so the real menu draws on it cleanly). On
 * top, all in the picture's own 1920x1080 space:
 * <ul>
 *   <li>the cape and the hair flow: those regions are redrawn as small tiles of the picture whose source is offset by a
 *       travelling sine, growing from nothing where they attach (shoulders, crown) to the most at the free ends;</li>
 *   <li>streaks falling down the acid waterfall and foam flickering where it lands on the slime's crown;</li>
 *   <li>the slime's glow pulsing gently, shimmer drifting across the lake, fog drifting over the water, a few motes rising
 *       at the sides;</li>
 *   <li>glowing droplets dripping from the cave ceiling at three depths (killer560, 2026-10-16): each forms on the
 *       rock, stretches, falls with gravity trailing a short streak, and lands with a ripple on the lake or a splat on the
 *       ledge; only two faint far ones fall through the middle;</li>
 * </ul>
 * The view itself never moves (killer560, 2026-10-16: the side-to-side sway was removed); only those parts do.
 */
final class TargetScene {

    private TargetScene() {
    }

    static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("killer560smod", "textures/gui/green_boss_target.png");
    private static final int TW = 1920;
    private static final int TH = 1080;

    private static final int LIME = 0xFFB4FF4A;
    private static final int HOT = 0xFFDCFFB8;
    private static final int WHITE = 0xFFFFFFFF;

    /** Puts the pose into the picture's space (1920x1080 stretched over the screen). The view itself stays still
     *  (killer560, 2026-10-16: no whole-screen sway; only the parts move). */
    private static void enter(Matrix3x2fStack pose, int w, int h, double t) {
        pose.pushMatrix();
        pose.scale(w / (float) TW, h / (float) TH);
    }

    static void drawImage(GuiGraphicsExtractor g, int w, int h, double t) {
        Matrix3x2fStack pose = g.pose();
        enter(pose, w, h, t);
        g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, 0, 0, 0f, 0f, TW, TH, TW, TH, TW, TH);
        // The cape: attached along its right (the shoulders and the body), free to the left and at its hem.
        warp(g, t, 60, 455, 252, 760, 5, 9.0, true);
        // The hair at the side of the hood: attached at the top, free at the tips.
        warp(g, t, 232, 440, 272, 528, 4, 3.0, false);
        pose.popMatrix();
    }

    /**
     * Redraws {@code x0..x1, y0..y1} of the picture as {@code tile}-sized pieces whose source is offset by a travelling
     * sine of amplitude {@code amp} times a weight that is zero at the region's edges and at its attached side.
     */
    private static void warp(GuiGraphicsExtractor g, double t, int x0, int y0, int x1, int y1, int tile, double amp,
                             boolean cape) {
        for (int y = y0; y < y1; y += tile) {
            for (int x = x0; x < x1; x += tile) {
                double cx = x + tile * 0.5;
                double cy = y + tile * 0.5;
                double fade = clamp((cx - x0) / 14.0) * clamp((cy - y0) / 26.0) * clamp((y1 - cy) / 16.0)
                        * clamp((x1 - cx) / 10.0);
                double wgt;
                double dx;
                double dy;
                if (cape) {
                    double wx = clamp((x1 - 5 - cx) / 130.0);
                    double wy = clamp((cy - 470) / 250.0);
                    wgt = wx * (0.45 + 0.55 * wy) * fade;
                    dx = amp * wgt * Math.sin(t * 1.7 - cx * 0.018 + cy * 0.012);
                    dy = 0.45 * amp * wgt * Math.sin(t * 1.3 + cx * 0.025);
                } else {
                    wgt = clamp((cy - y0) / (double) (y1 - y0)) * fade;
                    dx = amp * wgt * Math.sin(t * 2.1 + cy * 0.05);
                    dy = 0;
                }
                if (Math.abs(dx) < 0.05 && Math.abs(dy) < 0.05) {
                    continue;
                }
                g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, (float) (x - dx), (float) (y - dy), tile, tile, tile, tile,
                        TW, TH);
            }
        }
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    /** How many droplet sources the ceiling has (testkit 1651 checks the scene has them). */
    static final int DROPS = 24;
    /** Where the lake's surface is, and the ledge's top, in the picture's space. */
    private static final int LAKE_Y = 852;
    private static final int LEDGE_Y = 842;
    private static final int LEDGE_END = 520;

    /**
     * The droplets. Each source has a place on the ceiling, a depth (0 far: small and faint; 1 near: bigger, brighter, with a
     * glow) and a period; within the period a drop forms (swelling and stretching), falls under gravity with a streak as
     * long as its speed, then rings out on the lake or splats on the ledge.
     */
    private static void drips(GuiRects r, double t) {
        for (int i = 0; i < DROPS; i++) {
            double hx = MainMenuScenes.hash(i, 730);
            double x;
            double depth;
            if (i < 2) {
                // Two faint far ones through the middle, behind the menu.
                x = 640 + hx * 640;
                depth = 0;
            } else if (i % 2 == 0) {
                x = 70 + hx * 480;      // the left: over the ledge and the near lake
                depth = MainMenuScenes.hash(i, 731);
            } else {
                x = 1360 + hx * 480;    // the right: either side of the waterfall
                if (x > 1462 && x < 1596) {
                    x += 150;
                }
                depth = MainMenuScenes.hash(i, 731);
            }
            double ceil = 24 + MainMenuScenes.hash(i, 732) * 70;
            double form = 1.4 + MainMenuScenes.hash(i, 733) * 2.2;
            double g = 2300 * (0.7 + 0.3 * depth);
            boolean ledge = x < LEDGE_END;
            double land = ledge ? LEDGE_Y : LAKE_Y;
            double fall = Math.sqrt(2.0 * (land - ceil) / g);
            double splash = 0.55;
            double period = form + fall + splash + MainMenuScenes.hash(i, 734) * 1.5;
            double tt = (t + MainMenuScenes.hash(i, 735) * period) % period;
            double radius = 1.1 + depth * 2.6;
            int alpha = (int) (0x80 + 0x7F * depth);
            if (tt < form) {
                // Forming: a bead swelling on the rock, then stretching before it lets go.
                double f = tt / form;
                double rr = radius * (0.35 + 0.65 * f);
                double stretch = 1 + 0.9 * f * f * f;
                softDot(r, x, ceil + rr * stretch, rr, stretch, HOT, alpha, depth);
            } else if (tt < form + fall) {
                double ft = tt - form;
                double y = ceil + 0.5 * g * ft * ft;
                double v = g * ft;
                double streak = Math.min(110, v * 0.026 * (0.6 + 0.4 * depth));
                streak(r, x, y - streak, y, radius * 0.9, HOT, alpha / 2);
                softDot(r, x, y, radius, 1.35, HOT, alpha, depth);
            } else {
                double f = (tt - form - fall) / splash;
                if (f > 1) {
                    continue;
                }
                int a = (int) (alpha * (1 - f) * (1 - f));
                double e = 1 - (1 - f) * (1 - f); // eases out
                if (ledge) {
                    // A splat: a flattened blob spreading and fading, a few specks thrown out.
                    softDot(r, x, land - 1, radius * (1.3 + 2.0 * e), 0.35, HOT, a, depth);
                    for (int k = 0; k < 4; k++) {
                        double dir = (k < 2 ? -1 : 1) * (0.6 + 0.5 * MainMenuScenes.hash(i * 4 + k, 736));
                        double sx = x + dir * 26 * e * (0.6 + depth);
                        double sy = land - 3 - 26 * f * (1 - f) * (0.6 + depth) * (0.7 + 0.6 * MainMenuScenes.hash(i * 4 + k, 737));
                        softDot(r, sx, sy, 0.8 + depth * 0.8, 1, HOT, a, 0);
                    }
                } else {
                    // A ripple ring spreading on the water, a second one behind it, and a little rebound drop.
                    double rw = (5 + 46 * e) * (0.5 + depth);
                    ring(r, x, land + 2, rw, rw * 0.22, 1.2 + depth, HOT, a);
                    if (f > 0.2) {
                        double f2 = (f - 0.2) / 0.8;
                        double e2 = 1 - (1 - f2) * (1 - f2);
                        double rw2 = (3 + 30 * e2) * (0.5 + depth);
                        ring(r, x, land + 2, rw2, rw2 * 0.22, 1, HOT, (int) (a * 0.6));
                    }
                    if (f < 0.6) {
                        double up = Math.sin(Math.PI * f / 0.6) * 20 * (0.5 + depth);
                        softDot(r, x, land - up, radius * 0.6, 1.2, HOT, a, 0);
                    }
                }
            }
        }
    }

    /**
     * An antialiased disc of {@code radius} (stretched vertically by {@code stretch}) centred on a sub-pixel position: the
     * pose is moved by the fraction, rows are spans whose edge pixels carry the partial coverage, and a soft halo of
     * stacked faint discs surrounds it for the near drops ({@code glow} 0..1).
     */
    private static void softDot(GuiRects r, double cx, double cy, double radius, double stretch, int rgb, int alpha,
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
    private static void disc(GuiRects r, double radius, double stretch, int rgb, int alpha) {
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
    private static void streak(GuiRects r, double x, double y0, double y1, double width, int rgb, int alpha) {
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
    private static void ring(GuiRects r, double cx, double cy, double rx, double ry, double thick, int rgb, int alpha) {
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

    static void drawOverlays(GuiRects r, int w, int h, double t) {
        Matrix3x2fStack pose = r.graphics().pose();
        enter(pose, w, h, t);

        // ---- the slime's glow, pulsing
        double pulse = 0.5 + 0.5 * Math.sin(t * 1.2);
        MainMenuScenes.glow(r, 1551, 700, 360, 300, LIME, (int) (0x10 + 0x18 * pulse), 4);
        MainMenuScenes.glow(r, 1551, 690, 230, 190, HOT, (int) (0x08 + 0x10 * pulse), 3);

        // ---- the waterfall: streaks falling, foam where it lands on the crown
        int top = 86;
        int bottom = 548;
        int len = bottom - top;
        for (int i = 0; i < 34; i++) {
            double u = MainMenuScenes.hash(i, 701);
            double speed = 320 + MainMenuScenes.hash(i, 702) * 260;
            double sl = 30 + MainMenuScenes.hash(i, 703) * 70;
            double travel = len + sl;
            double pos = (t * speed + MainMenuScenes.hash(i, 704) * travel * 9) % travel;
            int y1 = (int) Math.min(bottom, top + pos);
            int y0 = (int) Math.max(top, top + pos - sl);
            if (y1 - y0 < 3) {
                continue;
            }
            int x = (int) (1492 + u * 72);
            int sw = MainMenuScenes.hash(i, 705) > 0.6 ? 3 : 2;
            int c = i % 6 == 0 ? MenuStyle.alpha(0xFF0A2A0A, 0x50) : MenuStyle.alpha(HOT, 0x40 + (int) (MainMenuScenes.hash(i, 706) * 0x70));
            r.gradient(x, y0, x + sw, y1, MenuStyle.alpha(c, 0), c);
        }
        double foam = 0.6 + 0.4 * Math.sin(t * 6.1);
        MainMenuScenes.glow(r, 1528, 552, 70 + 10 * foam, 18, HOT, (int) (0x40 * foam + 0x20), 3);
        for (int i = 0; i < 14; i++) {
            double ph = (t * (1.1 + MainMenuScenes.hash(i, 707)) + MainMenuScenes.hash(i, 708)) % 1.0;
            double dir = MainMenuScenes.hash(i, 709) < 0.5 ? -1 : 1;
            double x = 1528 + (MainMenuScenes.hash(i, 710) - 0.5) * 70 + dir * 50 * ph;
            double y = 552 - 4 * (14 + MainMenuScenes.hash(i, 711) * 22) * ph * (1 - ph);
            int a = (int) (0xC0 * (1 - ph));
            if (a > 8) {
                softDot(r, x, y, 1.4, 1, HOT, a, 0);
            }
        }

        // ---- shimmer drifting across the lake
        for (int i = 0; i < 40; i++) {
            double d = MainMenuScenes.hash(i, 712);
            int y = 862 + (int) (d * 200);
            double l = (20 + MainMenuScenes.hash(i, 713) * 70) * (0.5 + d);
            double span = 900;
            double x = 470 + ((MainMenuScenes.hash(i, 714) * span + t * (8 + MainMenuScenes.hash(i, 715) * 14)) % span);
            int a = (int) (0x18 + 0x30 * (0.5 + 0.5 * Math.sin(t * 1.4 + i * 2.3)));
            r.fill((int) x, y, (int) (x + l), y + (d > 0.5 ? 2 : 1), MenuStyle.alpha(HOT, a));
        }

        // ---- fog drifting over the water and round the cliff
        for (int k = 0; k < 4; k++) {
            double span = TW * 1.6;
            double fx = ((t * (14 + k * 6) + MainMenuScenes.hash(k, 716) * span) % span) - TW * 0.3;
            int fy = 820 + k * 40;
            MainMenuScenes.glow(r, (int) fx, fy, 420, 46, LIME, 0x1A, 3);
        }
        MainMenuScenes.glow(r, (int) (160 + 30 * Math.sin(t * 0.2)), 900, 260, 120, LIME, 0x14, 3);

        // ---- droplets dripping from the ceiling
        drips(r, t);

        // ---- motes rising at the sides
        for (int i = 0; i < 26; i++) {
            double ph = (t * (0.02 + MainMenuScenes.hash(i, 717) * 0.03) + MainMenuScenes.hash(i, 718)) % 1.0;
            double fx = MainMenuScenes.hash(i, 719);
            double x = (i % 2 == 0 ? 40 + fx * 480 : 1400 + fx * 460) + Math.sin(t * 0.6 + i) * 10;
            double y = 900 - ph * 820;
            int a = (int) (0xB0 * Math.sin(Math.PI * ph) * (0.5 + 0.5 * Math.sin(t * 1.7 + i * 3.1)));
            if (a > 8) {
                softDot(r, x, y, 1.4, 1, HOT, a, 0);
            }
        }
        pose.popMatrix();
    }
}

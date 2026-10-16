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
 *   <li>the whole view swaying very slowly side to side (killer560's earlier ask).</li>
 * </ul>
 * Nothing moves under the title or the buttons beyond the slow sway of the fog behind them.
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

    /** Seconds per full sway. */
    private static final double SWAY_PERIOD = 14.0;

    /** Puts the pose into the picture's space (1920x1080 stretched over the screen), swayed and a touch enlarged so the
     *  sway never shows an edge. */
    private static void enter(Matrix3x2fStack pose, int w, int h, double t) {
        float sway = (float) (Math.sin(t * Math.PI * 2 / SWAY_PERIOD) * 10.0);
        pose.pushMatrix();
        pose.scale(w / (float) TW, h / (float) TH);
        pose.translate(-14f + sway, -8f);
        pose.scale((TW + 28f) / TW, (TH + 16f) / TH);
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
                r.fill((int) x, (int) y, (int) x + 3, (int) y + 3, MenuStyle.alpha(HOT, a));
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

        // ---- motes rising at the sides
        for (int i = 0; i < 26; i++) {
            double ph = (t * (0.02 + MainMenuScenes.hash(i, 717) * 0.03) + MainMenuScenes.hash(i, 718)) % 1.0;
            double fx = MainMenuScenes.hash(i, 719);
            double x = (i % 2 == 0 ? 40 + fx * 480 : 1400 + fx * 460) + Math.sin(t * 0.6 + i) * 10;
            double y = 900 - ph * 820;
            int a = (int) (0xB0 * Math.sin(Math.PI * ph) * (0.5 + 0.5 * Math.sin(t * 1.7 + i * 3.1)));
            if (a > 8) {
                r.fill((int) x, (int) y, (int) x + 3, (int) y + 3, MenuStyle.alpha(HOT, a));
            }
        }
        pose.popMatrix();
    }
}

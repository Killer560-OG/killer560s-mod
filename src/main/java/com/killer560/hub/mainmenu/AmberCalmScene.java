package com.killer560.hub.mainmenu;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.hud.GuiRects;

/**
 * Amber's calm main-menu background (killer560, 2026-10-15: "I like the more simplistic style of the [old embers]"):
 * the embers refined rather than replaced. A low warm glow lies along the bottom and breathes very slowly, a faint
 * heat haze wavers just above it, and embers rise in three depths - far ones tiny, dim and slow, near ones brighter
 * with a soft halo and a lazy sway. Embers thin out behind the button column and fade before they reach the title, so
 * the menu text stays clear. No architecture, no objects.
 * <p>
 * Same rules as {@link MainMenuScenes}: every position is a function of the clock, the size and a per-ember hash (no
 * state), everything goes into the caller's one {@link GuiRects} batch, and the code allocates nothing of its own. It
 * draws fewer rectangles than the old embers (testkit 1568 checks both).
 */
final class AmberCalmScene {

    private AmberCalmScene() {
    }

    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;

    static void draw(GuiRects r, int w, int h, double t, int accent, float density) {
        int hot = MenuStyle.mix(accent, WHITE, 0.45f);
        int deep = MenuStyle.mix(accent, BLACK, 0.45f);
        double breathe = 0.82 + 0.18 * Math.sin(t * 0.35);

        // The warm floor of light: one wide gradient, and a soft glow centred low that drifts a little side to side.
        int floorTop = h - Math.max(1, (int) (h * 0.45));
        r.gradient(0, floorTop, w, h, MenuStyle.alpha(deep, 0), MenuStyle.alpha(deep, (int) (0x70 * breathe)));
        int glowCx = w / 2 + (int) Math.round(Math.sin(t * 0.07) * w * 0.08);
        softGlow(r, glowCx, h + (int) (h * 0.04), w * 0.62, h * 0.42, accent, (int) (0x40 * breathe), 40, h);
        // A faint upper warmth behind the title, much weaker than the old embers' logo glow so text stays crisp.
        softGlow(r, w / 2, (int) (h * 0.12), Math.min(w * 0.45, 260), Math.min(h * 0.22, 80), accent, 0x16, 16, h);

        // Heat haze: a few thin, very faint bands above the glow whose brightness and length waver slowly.
        int hazeBase = h - (int) (h * 0.2);
        for (int k = 0; k < 6; k++) {
            double ph = t * (0.4 + 0.07 * k) + k * 1.9;
            int y = hazeBase - k * Math.max(3, h / 70) + (int) Math.round(Math.sin(ph * 0.7) * 1.5);
            double half = w * (0.22 + 0.06 * Math.sin(ph)) * (1.0 - k * 0.1);
            int cx = w / 2 + (int) Math.round(Math.sin(ph * 0.5 + k) * w * 0.05);
            int a = (int) ((0x10 + 0x0A * Math.sin(ph * 1.3)) * (1.0 - k * 0.12));
            if (a > 2 && half > 1) {
                r.gradient(cx - (int) half, y, cx + (int) half, y + 2, MenuStyle.alpha(hot, a), MenuStyle.alpha(hot, 0));
            }
        }

        // Embers in three depths. Far: 1 px, dim, slow. Mid: 1 px, warmer. Near: 2 px core with a faint halo.
        if (density <= 0f) {
            return;
        }
        int nFar = Math.max(6, Math.min(30, (int) Math.round(w * h / 12000.0 * density)));
        int nMid = Math.max(4, Math.min(18, (int) Math.round(w * h / 20000.0 * density)));
        int nNear = Math.max(2, Math.min(7, (int) Math.round(w * h / 50000.0 * density)));
        int farCol = MenuStyle.mix(accent, deep, 0.35f);
        layer(r, w, h, t, nFar, 101, 2.5, 4.0, 3.0, 0x70, 1, false, farCol, farCol);
        layer(r, w, h, t, nMid, 211, 5.0, 6.0, 6.0, 0xB0, 1, false, accent, hot);
        layer(r, w, h, t, nNear, 307, 8.0, 7.0, 10.0, 0xE8, 2, true, accent, hot);
    }

    private static void layer(GuiRects r, int w, int h, double t, int n, int salt, double baseSpeed, double speedRange,
                              double swayAmp, int maxA, int size, boolean halo, int col, int bright) {
        double travel = h * 0.85 + 20.0;
        double centreHalf = w * 0.16;
        for (int i = 0; i < n; i++) {
            double r1 = MainMenuScenes.hash(i, salt);
            double r2 = MainMenuScenes.hash(i, salt + 1);
            double r3 = MainMenuScenes.hash(i, salt + 2);
            double speed = baseSpeed + r2 * speedRange;
            double rise = (t * speed + r1 * travel * 13.0) % travel;
            double y = h + 8.0 - rise;
            double x = r3 * w + Math.sin(t * (0.12 + r1 * 0.2) + i * 1.7) * swayAmp * (0.6 + r2);
            double progress = rise / travel;
            // Fade in over the first tenth, then out smoothly so nothing reaches the title.
            double life = Math.min(1.0, progress * 10.0) * (1.0 - progress) * (1.0 - progress);
            double flicker = 0.8 + 0.2 * Math.sin(t * (1.1 + r3 * 1.4) + i * 2.3);
            // Quieter behind the button column.
            double off = Math.abs(x - w * 0.5) / centreHalf;
            double clear = off >= 1.0 ? 1.0 : 0.35 + 0.65 * off * off;
            int a = (int) (maxA * life * flicker * clear);
            if (a <= 4) {
                continue;
            }
            int px = (int) Math.round(x);
            int py = (int) Math.round(y);
            if (halo) {
                r.fill(px - 2, py - 2, px + size + 2, py + size + 2, MenuStyle.alpha(col, a / 6));
            }
            r.fill(px, py, px + size, py + size, MenuStyle.alpha(r1 > 0.6 ? bright : col, a));
        }
    }

    /** A soft elliptical glow as {@code cols} vertical gradient columns: each fades from the centre line out to the
     *  ellipse's edge, and the columns dim toward the sides, so there are no horizontal bands to see. Two rectangles per
     *  column (one when the centre is off the bottom of the screen). */
    private static void softGlow(GuiRects r, int cx, int cy, double rx, double ry, int rgb, int maxAlpha, int cols, int h) {
        if (rx < 1 || ry < 1 || maxAlpha <= 0) {
            return;
        }
        double colW = 2.0 * rx / cols;
        for (int j = 0; j < cols; j++) {
            int x0 = (int) Math.round(cx - rx + j * colW);
            int x1 = (int) Math.round(cx - rx + (j + 1) * colW);
            double dx = ((x0 + x1) * 0.5 - cx) / rx;
            double q = 1.0 - dx * dx;
            if (q <= 0 || x1 <= x0) {
                continue;
            }
            int a = (int) Math.round(maxAlpha * q * q);
            if (a <= 0) {
                continue;
            }
            int hh = (int) Math.round(ry * Math.sqrt(q));
            int c = MenuStyle.alpha(rgb, a);
            r.gradient(x0, cy - hh, x1, cy, MenuStyle.alpha(rgb, 0), c);
            if (cy < h) {
                r.gradient(x0, cy, x1, cy + hh, c, MenuStyle.alpha(rgb, 0));
            }
        }
    }
}

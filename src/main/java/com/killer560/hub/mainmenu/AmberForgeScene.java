package com.killer560.hub.mainmenu;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.hud.GuiRects;

/**
 * Amber's richer main-menu background (killer560, 2026-10-15): a dungeon colonnade at dusk. A low forge-sun sits on
 * the horizon behind a row of far pillars and a lintel, its light running down the dark floor as a shimmering
 * reflection; heat shimmer ripples over the horizon; two near pillars frame each side with a swaying lantern whose
 * flame flickers and throws sparks; embers drift up from the floor. The middle stays dim and open so the menu reads.
 * <p>
 * Same rules as {@link MainMenuScenes}: every position is a function of the clock, the size and a per-item hash (no
 * state, nothing accumulates), and everything goes into the caller's one {@link GuiRects} batch. The code itself
 * allocates nothing (no arrays, strings, boxing or lambdas); the only objects per frame are the batch's own rectangle
 * parts, as in every other scene. {@link MainMenuScenes#AMBER_SCENE} chooses between this and the old embers.
 */
final class AmberForgeScene {

    private AmberForgeScene() {
    }

    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;

    static void draw(GuiRects r, int w, int h, double t, int accent, float density) {
        int hot = MenuStyle.mix(accent, WHITE, 0.5f);
        int warm = MenuStyle.mix(accent, WHITE, 0.2f);
        int skyTop = MenuStyle.mix(BLACK, accent, 0.05f);
        int skyMid = MenuStyle.mix(BLACK, accent, 0.26f);
        int horizon = MenuStyle.mix(BLACK, accent, 0.66f);
        int silhouette = MenuStyle.mix(BLACK, accent, 0.07f);
        int farCol = MenuStyle.mix(BLACK, accent, 0.11f);
        double s = Math.max(0.8, Math.min(2.4, h / 420.0));
        int hy = (int) (h * 0.62);
        double breathe = 0.88 + 0.12 * Math.sin(t * 0.55);

        // Sky, and the sun with its glow.
        r.gradient(0, 0, w, hy / 2, skyTop, skyMid);
        r.gradient(0, hy / 2, w, hy, skyMid, horizon);
        double sunX = w * 0.67 + Math.sin(t * 0.045) * w * 0.03;
        double sunR = Math.max(20, Math.min(64, h * 0.1));
        MainMenuScenes.glow(r, (int) sunX, hy - (int) (sunR * 0.3), w * 0.5, h * 0.42, hot, (int) (0x44 * breathe), 8);
        disc(r, sunX, hy - sunR * 0.35, sunR, MenuStyle.alpha(warm, 0xB8));
        disc(r, sunX, hy - sunR * 0.35, sunR * 0.62, MenuStyle.alpha(hot, 0xC8));
        // Haze lying on the horizon.
        r.gradient(0, hy - (int) (h * 0.13), w, hy, MenuStyle.alpha(hot, 0), MenuStyle.alpha(hot, 0x40));

        // Far colonnade: a lintel across the hall and the pillars under it, each with a rim of light on the sun's side.
        int lintelY = hy - (int) (h * 0.33);
        int beamH = (int) Math.max(4, 6 * s);
        r.fill(0, lintelY, w, lintelY + beamH, farCol);
        r.fill(0, lintelY + beamH, w, lintelY + beamH + 1, MenuStyle.alpha(hot, 0x22));
        int spacing = (int) Math.max(54, w / 13.0);
        int pillars = w / spacing + 2;
        for (int i = 0; i < pillars; i++) {
            double sway = Math.sin(t * 0.05 + i) * 1.5;
            int px = (int) (i * spacing + spacing * 0.3 + hash(i, 71) * spacing * 0.4 + sway);
            int pw = (int) ((7 + hash(i, 72) * 4) * s);
            r.fill(px, lintelY, px + pw, hy, farCol);
            r.fill(px - 2, lintelY, px + pw + 2, lintelY + 3, farCol);          // capital
            r.fill(px - 2, hy - 4, px + pw + 2, hy, farCol);                      // base
            boolean sunLeft = px + pw / 2.0 > sunX;
            int rimX = sunLeft ? px : px + pw - 1;
            r.fill(rimX, lintelY + beamH, rimX + 1, hy, MenuStyle.alpha(hot, 0x2E));
            // Its reflection in the floor.
            r.gradient(px, hy, px + pw, hy + (int) (h * 0.09), MenuStyle.alpha(farCol, 0x90), MenuStyle.alpha(farCol, 0));
        }

        // Heat shimmer over the horizon: thin warm and dark dashes sliding sideways.
        for (int k = 0; k < 11; k++) {
            int y = hy - 3 - k * 3;
            double ph = t * (0.9 + 0.1 * k) + k * 1.9;
            for (int j = 0; j < 3; j++) {
                double cx = w * (0.5 + 0.3 * Math.sin(ph * 0.4 + j * 2.1 + k)) + Math.sin(y * 0.3 + t * 2.0) * 4;
                int half = (int) ((26 + 22 * hash(k * 3 + j, 73)) * s);
                int a = (int) (0x1A * (0.5 + 0.5 * Math.sin(ph + j)) * (1.0 - k / 12.0));
                r.fill((int) cx - half, y, (int) cx + half, y + 1, MenuStyle.alpha(k % 2 == 0 ? hot : BLACK, a));
            }
        }

        // The floor and the sun's reflection running down it.
        int floorTop = MenuStyle.mix(horizon, BLACK, 0.45f);
        r.gradient(0, hy, w, h, floorTop, MenuStyle.mix(BLACK, accent, 0.03f));
        r.fill(0, hy, w, hy + 1, MenuStyle.alpha(hot, 0x40));
        int rows = Math.max(1, (int) ((h - hy) * 0.9 / 3));
        for (int i = 0; i < rows; i++) {
            double p = (double) i / rows;
            int y = hy + 2 + i * 3;
            double half = sunR * (0.9 + 2.4 * p) + Math.sin(t * 1.4 + i * 0.8) * (1.5 + p * 7);
            double centre = sunX + Math.sin(t * 1.1 + i * 0.5) * (1 + p * 6);
            int a = (int) (0x70 * (1.0 - p) * (0.55 + 0.45 * Math.sin(t * 2.1 + i * 1.7)));
            r.fill((int) (centre - half), y, (int) (centre + half), y + 2, MenuStyle.alpha(hot, a));
        }

        // Near pillars framing both sides, nearly black, lit on the inner edge; a lantern hangs beside each pair.
        for (int side = 0; side < 2; side++) {
            for (int q = 0; q < 2; q++) {
                int pw = (int) ((q == 0 ? 30 : 20) * s);
                double edge = q == 0 ? 0.05 : 0.17;
                int x0 = side == 0 ? (int) (w * edge - pw / 2.0) : (int) (w * (1 - edge) - pw / 2.0);
                r.fill(x0, 0, x0 + pw, h, q == 0 ? BLACK : silhouette);
                int inner = side == 0 ? x0 + pw : x0 - 3;
                r.gradient(inner, 0, inner + 3, h, MenuStyle.alpha(hot, 0x04), MenuStyle.alpha(hot, 0x40));
                r.fill(x0 - 3, 0, x0 + pw + 3, (int) (8 * s), q == 0 ? BLACK : silhouette);   // capital
            }
            lantern(r, side == 0 ? (int) (w * 0.255) : (int) (w * 0.745), h, t, side, s, hot, warm, accent, density);
        }

        // Embers lifting off the floor.
        if (density > 0f) {
            int n = (int) Math.max(Math.round(14 * density), Math.min(Math.round(46 * density), Math.round(w * h / 9000.0 * density)));
            double travel = hy + 40.0;
            for (int i = 0; i < n; i++) {
                double r1 = hash(i, 81);
                double r2 = hash(i, 82);
                double r3 = hash(i, 83);
                double rise = (t * (6 + r2 * 12) + r1 * travel * 9.0) % travel;
                double y = h + 6 - rise;
                double x = r3 * w + Math.sin(t * (0.3 + r1 * 0.4) + i * 1.3) * (6 + r2 * 10) + rise * 0.12;
                double life = Math.min(1.0, rise / travel * 7.0) * (1.0 - rise / travel);
                int a = (int) (200 * life * (0.7 + 0.3 * Math.sin(t * (2.0 + r3 * 2.5) + i * 1.7)));
                if (a <= 4) {
                    continue;
                }
                int px = (int) x;
                int py = (int) y;
                int size = r2 > 0.85 ? 2 : 1;
                r.fill(px - 1, py - 1, px + size + 1, py + size + 1, MenuStyle.alpha(accent, a / 4));
                r.fill(px, py, px + size, py + size, MenuStyle.alpha(r1 > 0.5 ? hot : warm, a));
            }
        }
    }

    /** A chain from the ceiling, swaying a little, a caged flame at its end, and sparks rising off it. */
    private static void lantern(GuiRects r, int x, int h, double t, int id, double s, int hot, int warm, int accent,
                                float density) {
        int len = (int) (h * 0.30);
        double swing = Math.sin(t * 0.7 + id * 2.0) * 3.0;
        int segs = 5;
        int prevX = x;
        for (int k = 1; k <= segs; k++) {
            int cx = x + (int) (swing * k / segs * k / segs);
            int y0 = len * (k - 1) / segs;
            int y1 = len * k / segs;
            r.fill(Math.min(prevX, cx), y0, Math.max(prevX, cx) + 1, y1, MenuStyle.alpha(0xFF000000 | MenuStyle.mix(accent, BLACK, 0.7f), 0xF0));
            prevX = cx;
        }
        int lx = x + (int) swing;
        double flicker = 0.75 + 0.25 * Math.sin(t * 7.3 + id * 1.9) * Math.sin(t * 3.1 + id);
        MainMenuScenes.glow(r, lx, len + (int) (6 * s), 46 * s, 46 * s, hot, (int) (0x70 * flicker), 8);
        int bw = (int) Math.max(3, 4 * s);
        int bh = (int) Math.max(5, 7 * s);
        r.fill(lx - bw - 1, len - 2, lx + bw + 1, len + bh + 3, BLACK);                   // cage
        r.fill(lx - bw, len, lx + bw, len + bh, MenuStyle.alpha(hot, (int) (0xF0 * (0.8 + 0.2 * flicker))));
        r.fill(lx - bw / 2, len + 1, lx + bw / 2, len + bh - 1, MenuStyle.alpha(WHITE, 0x90));
        if (density > 0f) {
            for (int j = 0; j < 5; j++) {
                double life = (t * (0.35 + hash(j, 91 + id) * 0.3) + hash(j, 95 + id)) % 1.0;
                double px = lx + Math.sin(t * 1.3 + j * 2.2 + id) * 10 * life;
                double py = len - life * 50 * s;
                int a = (int) (0xD0 * Math.sin(Math.PI * life));
                if (a > 4) {
                    r.fill((int) px, (int) py, (int) px + 1, (int) py + 1, MenuStyle.alpha(j % 2 == 0 ? hot : warm, a));
                }
            }
        }
    }

    private static void disc(GuiRects r, double cx, double cy, double radius, int color) {
        int rad = (int) Math.round(radius);
        int step = rad > 10 ? 2 : 1;
        for (int dy = -rad; dy < rad; dy += step) {
            double mid = dy + step * 0.5;
            double q = radius * radius - mid * mid;
            if (q <= 0) {
                continue;
            }
            int half = (int) Math.round(Math.sqrt(q));
            int y = (int) Math.round(cy + dy);
            r.fill((int) Math.round(cx) - half, y, (int) Math.round(cx) + half, y + step, color);
        }
    }

    private static double hash(int i, int salt) {
        return MainMenuScenes.hash(i, salt);
    }
}

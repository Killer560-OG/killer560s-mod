package com.killer560.hub.mainmenu;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.hud.GuiRects;

/**
 * The Green theme's main menu background (killer560, 2026-10-15): a calm viridian forest at dusk. Mist bands drift low
 * between the trunks, leaves fall slowly, and fireflies wander and pulse. Like every scene, each position is a function
 * of the clock and a per-particle hash, so nothing is allocated or remembered between frames; it draws into the
 * {@link GuiRects} batch {@link MainMenuScenes#draw} already opened.
 */
final class MainMenuFireflies {

    private MainMenuFireflies() {
    }

    static void draw(GuiRects r, int w, int h, double t, int accent, int panel, int text, float density) {
        int mint = MenuStyle.mix(accent, 0xFFFFFFFF, 0.55f);
        int deep = MenuStyle.mix(panel, 0xFF000000, 0.5f);
        int trunk = MenuStyle.mix(deep, accent, 0.07f);
        int trunkEdge = MenuStyle.mix(trunk, accent, 0.12f);

        // Moonlight from above and a canopy glow.
        MainMenuScenes.glow(r, w / 2, -10, w * 0.6, h * 0.35, mint, 0x22, 6);

        // Tree trunks: tall soft columns at fixed hash positions, wider and dimmer toward the back.
        int trunks = Math.max(5, w / 90);
        for (int i = 0; i < trunks; i++) {
            double depth = MainMenuScenes.hash(i, 71);
            int tw = 6 + (int) (depth * 16);
            int x = (int) ((i + 0.2 + MainMenuScenes.hash(i, 72) * 0.6) * w / trunks);
            int col = MenuStyle.alpha(trunk, (int) (0x70 + depth * 0x80));
            r.fill(x, 0, x + tw, h, col);
            r.fill(x + tw - 1, 0, x + tw, h, MenuStyle.alpha(trunkEdge, (int) (0x30 + depth * 0x40)));
        }

        // Mist: wide faint bands sliding sideways at the foot of the trees.
        for (int k = 0; k < 3; k++) {
            double drift = Math.sin(t * (0.05 + k * 0.02) + k * 2.0) * w * 0.15;
            int cy = h - 20 - k * Math.max(14, h / 9);
            int a = (int) (0x1E + 0x0C * Math.sin(t * 0.3 + k));
            MainMenuScenes.glow(r, (int) (w / 2 + drift), cy, w * 0.7, h * 0.09, mint, a, 5);
        }

        // Leaves drifting down, tumbling side to side.
        int leaves = scaled(w * h / 7000.0, density, 10, 44);
        int leafLight = MenuStyle.mix(accent, 0xFFFFFFFF, 0.25f);
        double travel = h + 30.0;
        for (int i = 0; i < leaves; i++) {
            double r1 = MainMenuScenes.hash(i, 81);
            double r2 = MainMenuScenes.hash(i, 82);
            double r3 = MainMenuScenes.hash(i, 83);
            double fall = (t * (6.0 + r2 * 9.0) + r1 * travel * 7.0) % travel;
            double y = fall - 15.0;
            double x = r3 * w + Math.sin(t * (0.5 + r1 * 0.6) + i * 1.9) * (14.0 + r2 * 20.0);
            int a = (int) (0xA0 * Math.min(1.0, fall / 30.0) * Math.min(1.0, (travel - fall) / 30.0));
            if (a <= 6) {
                continue;
            }
            int px = (int) Math.round(x);
            int py = (int) Math.round(y);
            boolean wide = Math.sin(t * (1.1 + r3) + i) > 0;
            int lw = wide ? 3 : 2;
            int lh = wide ? 2 : 3;
            r.fill(px, py, px + lw, py + lh, MenuStyle.alpha(r1 > 0.5 ? leafLight : accent, a));
        }

        // Fireflies: a slow looping wander, each pulsing on its own beat.
        int flies = scaled(w * h / 6500.0, density, 12, 56);
        for (int i = 0; i < flies; i++) {
            double r1 = MainMenuScenes.hash(i, 91);
            double r2 = MainMenuScenes.hash(i, 92);
            double r3 = MainMenuScenes.hash(i, 93);
            double x = r1 * w + Math.sin(t * (0.18 + r2 * 0.3) + i * 2.3) * (18 + r3 * 30);
            double y = (0.25 + r2 * 0.7) * h + Math.cos(t * (0.15 + r3 * 0.25) + i * 1.1) * (10 + r1 * 18);
            double pulse = Math.max(0.0, Math.sin(t * (0.7 + r3 * 1.1) + i * 3.7));
            int a = (int) (255 * pulse * pulse);
            if (a <= 8) {
                continue;
            }
            int px = (int) Math.round(x);
            int py = (int) Math.round(y);
            r.fill(px - 2, py - 2, px + 3, py + 3, MenuStyle.alpha(accent, a / 8));
            r.fill(px - 1, py - 1, px + 2, py + 2, MenuStyle.alpha(accent, a / 4));
            r.fill(px, py, px + 1, py + 1, MenuStyle.alpha(r1 > 0.6 ? text : mint, a));
        }
    }

    private static int scaled(double perArea, float density, int min, int max) {
        if (density <= 0f) {
            return 0;
        }
        int n = (int) Math.round(perArea * density);
        return Math.max(Math.round(min * density), Math.min(Math.round(max * density), n));
    }
}

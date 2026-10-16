package com.killer560.hub.mainmenu;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.hud.GuiRects;

/**
 * The Green theme's main menu background, the slime boss (the theme's designer, 2026-10-15, with a reference picture for
 * the composition only: "the hollow knight is just the silhouette of a minecraft looking character, and the radiance is
 * the slime boss. The slime would kind of be emerging from the acid lake"; and "everything else about the style of the cave
 * and fog looks good"). It is {@link AcidCaveScene}'s cave - walls, stalactites, pillars, waterfall, lake, fog and tint
 * unchanged - with a new composition in place of the ledge and the fight:
 * <ul>
 *   <li>right, in the waterfall's pool, a huge Minecraft slime rising half out of the lake, its core blazing, with soft
 *       bloom halos (layered translucent fills), rays fanning away from the menu, the lake lit and ringed around it, and a
 *       blocky crown of light (a nod to the reference's boss);</li>
 *   <li>left, cropped by the screen edge, a big close-up silhouette of the designer's Minecraft character (AGreenCatgirl:
 *       slim arms, a hat-layer head one pixel wider than the head, long hair down the back) facing the boss, sword held
 *       low, its edge lit by the glow. Cat ears only when {@link #EARS} is on (unconfirmed, so off).</li>
 * </ul>
 * A still for now (killer560: "ONLY the still image first"): the cave is drawn at a fixed moment ({@link #FROZEN_S}) and
 * nothing moves; the side-to-side sway comes only if the designer likes the picture. Allocation-free like the cave.
 */
final class SlimeBossScene {

    private SlimeBossScene() {
    }

    /** Cat ears on the character's head: unconfirmed by the designer, so off. */
    static final boolean EARS = false;

    /** The cave's clock is held here, so its streaks, bubbles and fog stand still. */
    static final double FROZEN_S = 37.0;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;
    private static final int SHADOW = 0xFF030704;

    static void draw(GuiRects r, int w, int h, double t, int accent, int panel, float density) {
        AcidCaveScene.draw(r, w, h, FROZEN_S, accent, panel, density, true);
    }

    /** The boss, drawn by the cave after its lake and before its fog. */
    static void boss(GuiRects r, int w, int h, int acid, int hot, int lakeY, float s) {
        double S = h * 0.42;
        int cx = (int) Math.round(w * 0.815);
        int top = (int) Math.round(lakeY - S * 0.64);
        int cy = top + (int) (S * 0.32);
        int glare = MenuStyle.mix(hot, WHITE, 0.3f);

        // Bloom over the cave around it, wide and faint, then tighter and brighter.
        MainMenuScenes.glow(r, cx, cy, w * 0.30, h * 0.55, acid, 0x30, 5);
        MainMenuScenes.glow(r, cx, cy, w * 0.18, h * 0.36, hot, 0x40, 5);
        rays(r, cx, cy, S, hot, s);

        int half = (int) Math.round(S / 2);
        int x0 = cx - half;
        int x1 = cx + half;
        int bottom = (int) Math.round(top + S);
        for (int k = 4; k >= 1; k--) {
            int g = (int) (k * 7 * s);
            r.fill(x0 - g, top - g, x1 + g, lakeY + g / 3, MenuStyle.alpha(glare, 0x16));
        }
        // A crown of three blocky spikes of light.
        int sp = Math.max(2, (int) (S * 0.07));
        for (int k = -1; k <= 1; k++) {
            int scx = cx + k * (int) (S * 0.28);
            int tall = (int) (S * (k == 0 ? 0.30 : 0.19));
            for (int j = 0; j < 4; j++) {
                int wj = Math.max(1, sp - j * sp / 4);
                int yj = top - tall * (j + 1) / 4;
                r.fill(scx - wj, yj, scx + wj, yj + tall / 4 + 1, MenuStyle.alpha(glare, 0xD0 - j * 0x22));
            }
        }
        // Outer jelly, then the core, brightest in the middle.
        r.fill(x0, top, x1, bottom, MenuStyle.alpha(MenuStyle.mix(acid, WHITE, 0.25f), 0xD8));
        int edge = Math.max(1, Math.round(2 * s));
        r.outline(x0, top, x1 - x0, bottom - top, MenuStyle.alpha(WHITE, 0x80));
        r.fill(x0 + edge, top + edge, x1 - edge, top + edge + (int) (S * 0.07), MenuStyle.alpha(WHITE, 0x50));
        int ci = (int) (S * 0.17);
        r.fill(x0 + ci, top + ci, x1 - ci, bottom - ci, MenuStyle.alpha(hot, 0xF0));
        int cj = (int) (S * 0.29);
        r.fill(x0 + cj, top + cj, x1 - cj, bottom - cj, MenuStyle.alpha(WHITE, 0xC8));
        // Its face, dark against the glare, turned toward the character (left).
        int eye = (int) (S * 0.12);
        int ey = top + (int) (S * 0.27);
        int face = 0xE0123814;
        r.fill(cx - (int) (S * 0.34), ey, cx - (int) (S * 0.34) + eye, ey + eye, face);
        r.fill(cx - (int) (S * 0.06), ey, cx - (int) (S * 0.06) + eye, ey + eye, face);
        int m = (int) (S * 0.08);
        r.fill(cx - (int) (S * 0.20), ey + (int) (S * 0.23), cx - (int) (S * 0.20) + m, ey + (int) (S * 0.23) + m, face);
        // Under the surface: the acid over its lower part, foam where it breaks the surface, rings spreading out.
        r.gradient(x0 - 1, lakeY, x1 + 1, bottom, MenuStyle.alpha(MenuStyle.mix(acid, BLACK, 0.45f), 0xA0),
                MenuStyle.alpha(MenuStyle.mix(acid, BLACK, 0.7f), 0xF0));
        int px = Math.max(1, Math.round(s));
        MainMenuScenes.glow(r, cx, lakeY + (int) (8 * s), S * 1.1, (h - lakeY) * 0.8, hot, 0x48, 4);
        r.fill(x0 - (int) (6 * s), lakeY - px, x1 + (int) (6 * s), lakeY + 2 * px, MenuStyle.alpha(glare, 0xC0));
        // Ripples spreading from it: short bright lines either side, longer and fainter further out.
        for (int k = 0; k < 5; k++) {
            int y = lakeY + (3 + k * 4) * px;
            int in = (int) (S * (0.55 + k * 0.16));
            int len = (int) (S * (0.14 + k * 0.06));
            int a = 0x90 - k * 0x18;
            r.fill(cx - in - len, y, cx - in, y + px, MenuStyle.alpha(hot, a));
            r.fill(cx + in, y, cx + in + len, y + px, MenuStyle.alpha(hot, a));
        }
    }

    /** Rays from the boss, each a run of overlapping translucent squares widening outward, all fanning away from the menu
     *  (up, right, and down-left over the lake). */
    private static void rays(GuiRects r, int cx, int cy, double size, int hot, float s) {
        double[] angles = {-102, -84, -66, -48, -30, -12, 152, 140};
        double[] lengths = {1.0, 1.3, 1.1, 1.25, 0.9, 0.8, 0.9, 0.75};
        for (int i = 0; i < angles.length; i++) {
            double a = Math.toRadians(angles[i]);
            double dx = Math.cos(a);
            double dy = Math.sin(a);
            double len = size * 1.5 * lengths[i];
            int n = 16;
            for (int k = 0; k < n; k++) {
                double f = (k + 0.5) / n;
                double d = size * 0.4 + len * f;
                int half = (int) Math.round((4 + 22 * f) * s);
                int x = (int) Math.round(cx + dx * d);
                int y = (int) Math.round(cy + dy * d);
                int al = (int) (0x1E * (1 - f));
                if (al > 0) {
                    r.fill(x - half, y - half, x + half, y + half, MenuStyle.alpha(hot, al));
                }
            }
        }
    }

    /**
     * The character, close up on the left facing right toward the boss, cropped by the left and bottom edges: drawn first
     * shifted toward the glow in the rim colour, then dark, so only the edges facing the boss are lit.
     */
    static void character(GuiRects r, int w, int h, int hot, float s) {
        float u = h * 0.0195f;
        float hx = w * 0.13f; // head centre
        float hy = h * 0.36f; // head top
        int shift = Math.max(1, Math.round(1.5f * s));
        int rim = MenuStyle.alpha(hot, 0xB8);
        // Part by part, back to front, each with its own lit edge, so the head, body and sword arm read apart.
        for (int part = 0; part < 4; part++) {
            body(r, hx + shift, hy - shift / 2f, u, part, rim);
            body(r, hx, hy, u, part, SHADOW);
        }
    }

    private static void body(GuiRects r, float hx, float hy, float u, int part, int c) {
        if (part == 0) {
            // Hair down the back (to the left, away from the boss), its ends uneven.
            rect(r, hx - 5.2f * u, hy + 0.8f * u, hx - 1.5f * u, hy + 11.5f * u, c);
            rect(r, hx - 3.4f * u, hy + 11f * u, hx - 2.0f * u, hy + 13.2f * u, c);
            rect(r, hx - 4.8f * u, hy + 11f * u, hx - 3.4f * u, hy + 12.4f * u, c);
            // The back arm, hanging.
            rect(r, hx - 6.4f * u, hy + 9.5f * u, hx - 3.2f * u, hy + 21.5f * u, c);
            return;
        }
        if (part == 1) {
            // Body and legs (the legs run off the bottom of the screen).
            rect(r, hx - 4f * u, hy + 9f * u, hx + 4f * u, hy + 21f * u, c);
            rect(r, hx - 4f * u, hy + 21f * u, hx - 0.2f * u, hy + 34f * u, c);
            rect(r, hx + 0.2f * u, hy + 21f * u, hx + 4f * u, hy + 34f * u, c);
            return;
        }
        if (part == 2) {
            // The head with its hat layer: nine pixels where the head is eight.
            rect(r, hx - 4.5f * u, hy, hx + 4.5f * u, hy + 9f * u, c);
            if (EARS) {
                rect(r, hx - 3.8f * u, hy - 1.1f * u, hx - 1.6f * u, hy, c);
                rect(r, hx - 3.2f * u, hy - 2.1f * u, hx - 2.2f * u, hy - 1f * u, c);
                rect(r, hx + 1.6f * u, hy - 1.1f * u, hx + 3.8f * u, hy, c);
                rect(r, hx + 2.2f * u, hy - 2.1f * u, hx + 3.2f * u, hy - 1f * u, c);
            }
            return;
        }
        // The sword arm reaching forward and down, the sword pointing at the lake in front.
        double shX = hx + 3.0 * u;
        double shY = hy + 10.5 * u;
        double handX = hx + 8.5 * u;
        double handY = hy + 19.5 * u;
        bar(r, shX, shY, handX, handY, 3.2 * u, c);
        double bx = 0.62;
        double by = 0.78;
        bar(r, handX - 2.5 * u * bx, handY - 2.5 * u * by, handX + 17 * u * bx, handY + 17 * u * by, 1.8 * u, c);
        bar(r, handX - 2.4 * u * by, handY + 2.4 * u * bx, handX + 2.4 * u * by, handY - 2.4 * u * bx, 1.5 * u, c);
    }

    private static void rect(GuiRects r, double x0, double y0, double x1, double y1, int c) {
        r.fill((int) Math.round(x0), (int) Math.round(y0), (int) Math.round(x1), (int) Math.round(y1), c);
    }

    /** A thick line as overlapping squares. */
    private static void bar(GuiRects r, double x0, double y0, double x1, double y1, double thick, int c) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double len = Math.sqrt(dx * dx + dy * dy);
        int n = Math.max(1, (int) Math.ceil(len / Math.max(1, thick * 0.5)));
        int half = Math.max(1, (int) Math.round(thick / 2));
        for (int i = 0; i <= n; i++) {
            int x = (int) Math.round(x0 + dx * i / n);
            int y = (int) Math.round(y0 + dy * i / n);
            r.fill(x - half, y - half, x + half, y + half, c);
        }
    }
}

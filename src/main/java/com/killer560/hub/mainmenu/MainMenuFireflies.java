package com.killer560.hub.mainmenu;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.hud.GuiRects;

/**
 * The Green theme's main menu background: a forest at dusk (killer560, 2026-10-15: "can you make the main menu more of a
 * forest?"). Three depth layers, back to front:
 * <ul>
 *   <li>far: thin hazy pale-green trunks and leafy crowns half lost in fog;</li>
 *   <li>mid: darker, thicker trunks and crowns, with bushes at their feet;</li>
 *   <li>near: a few big near-black trunks framing the edges with a moonlit rim, a dark canopy overhead, and undergrowth
 *       along the bottom - ferns, bushes and grass tufts.</li>
 * </ul>
 * Soft light shafts fall through the canopy between the far and mid layers, mist drifts along the ground, fireflies wander
 * between the layers (dim ones behind the mid trees, bright ones in front), and a few leaves fall. Each layer drifts
 * sideways by its own amount (parallax) and the crowns, ferns and grass sway; the middle third, where the title and
 * button column sit, has no near trunks and no light shafts so it stays dark and calm.
 * <p>
 * Every shape's place and size is built once per screen size into fixed arrays ({@link #layout}) and only offset by the
 * clock each frame; the arrays are allocated once with the class, so neither a frame nor a resize allocates anything
 * beyond the batch's own rectangle parts. Like every scene it draws into the {@link GuiRects} batch
 * {@link MainMenuScenes#draw} already opened.
 */
final class MainMenuFireflies {

    private MainMenuFireflies() {
    }

    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;

    private static final int FAR = 0;
    private static final int MID = 1;
    private static final int NEAR = 2;

    // ---- cached geometry (rebuilt only when the size changes) -----------------------------------------------------
    private static int builtW = -1;
    private static int builtH = -1;

    private static final int MAX_TRUNKS = 64;
    private static int trunks;
    private static final int[] trunkLayer = new int[MAX_TRUNKS];
    private static final float[] trunkX = new float[MAX_TRUNKS];
    private static final float[] trunkW = new float[MAX_TRUNKS];
    private static final float[] trunkTop = new float[MAX_TRUNKS];
    private static final float[] trunkBottom = new float[MAX_TRUNKS];

    /** Leafy crowns and bushes: each is an ellipse-ish lobe; overlapping lobes make the silhouette. */
    private static final int MAX_LOBES = 220;
    private static int lobes;
    private static final int[] lobeLayer = new int[MAX_LOBES];
    private static final float[] lobeX = new float[MAX_LOBES];
    private static final float[] lobeY = new float[MAX_LOBES];
    private static final float[] lobeRx = new float[MAX_LOBES];
    private static final float[] lobeRy = new float[MAX_LOBES];
    private static final float[] lobePhase = new float[MAX_LOBES];

    private static final int MAX_FERNS = 16;
    private static int ferns;
    private static final float[] fernX = new float[MAX_FERNS];
    private static final float[] fernSize = new float[MAX_FERNS];

    private static final int MAX_TUFTS = 80;
    private static int tufts;
    private static final float[] tuftX = new float[MAX_TUFTS];
    private static final float[] tuftH = new float[MAX_TUFTS];

    private static final int SHAFTS = 4;
    private static final float[] shaftX = new float[SHAFTS];
    private static final float[] shaftW = new float[SHAFTS];

    /** Lays every static shape out for a {@code w} x {@code h} screen. No allocation: the arrays are fixed. */
    private static void layout(int w, int h) {
        builtW = w;
        builtH = h;
        float s = (float) Math.max(0.7, Math.min(2.2, h / 540.0));
        trunks = 0;
        lobes = 0;

        // Far layer: many thin trunks across the whole width, crowns high up.
        int farCount = Math.min(26, Math.max(10, w / 38));
        for (int i = 0; i < farCount; i++) {
            float x = (float) ((i + 0.15 + h(i, 11) * 0.7) * w / farCount);
            float tw = (float) ((2.5 + h(i, 12) * 3.0) * s);
            float top = (float) (h * (0.06 + h(i, 13) * 0.10));
            addTrunk(FAR, x, tw, top, h * 0.80f);
            int n = 2 + (int) (h(i, 14) * 3);
            for (int k = 0; k < n; k++) {
                addLobe(FAR, x + (float) ((h(i * 7 + k, 15) - 0.5) * 36 * s), top + (float) ((h(i * 7 + k, 16) - 0.3) * 30 * s),
                        (float) ((14 + h(i * 7 + k, 17) * 14) * s), (float) ((10 + h(i * 7 + k, 18) * 10) * s));
            }
        }
        // A far crown line closing the gaps, so the treetops read as a forest and not a fence.
        for (int i = 0; i < 18 && lobes < MAX_LOBES; i++) {
            addLobe(FAR, (float) ((i + h(i, 19)) * w / 18.0), (float) (h * (0.04 + h(i, 20) * 0.08)),
                    (float) ((26 + h(i, 21) * 20) * s), (float) ((12 + h(i, 22) * 8) * s));
        }

        // Mid layer: fewer, thicker trunks; none straight behind the title's middle.
        int midCount = Math.min(14, Math.max(6, w / 85));
        for (int i = 0; i < midCount; i++) {
            float x = (float) ((i + 0.2 + h(i, 31) * 0.6) * w / midCount);
            if (Math.abs(x - w / 2f) < w * 0.07f) {
                x += (x < w / 2f ? -1 : 1) * w * 0.08f;
            }
            float tw = (float) ((6 + h(i, 32) * 6) * s);
            float top = (float) (h * (0.02 + h(i, 33) * 0.08));
            addTrunk(MID, x, tw, top, h * 0.88f);
            int n = 3 + (int) (h(i, 34) * 3);
            for (int k = 0; k < n; k++) {
                addLobe(MID, x + (float) ((h(i * 9 + k, 35) - 0.5) * 60 * s), top + (float) ((h(i * 9 + k, 36) - 0.4) * 34 * s),
                        (float) ((20 + h(i * 9 + k, 37) * 18) * s), (float) ((13 + h(i * 9 + k, 38) * 11) * s));
            }
            // A bush at its foot.
            for (int k = 0; k < 2; k++) {
                addLobe(MID, x + (float) ((h(i * 3 + k, 39) - 0.5) * 40 * s), h * 0.87f - (float) (h(i * 3 + k, 40) * 6 * s),
                        (float) ((14 + h(i * 3 + k, 41) * 12) * s), (float) ((7 + h(i * 3 + k, 42) * 6) * s));
            }
        }

        // Near layer: two or three big trunks each side, framing the menu.
        int perSide = w > 700 ? 3 : 2;
        for (int side = 0; side < 2; side++) {
            for (int i = 0; i < perSide; i++) {
                float f = (float) ((i + 0.15 + h(side * 5 + i, 51) * 0.5) / perSide * 0.24);
                float x = side == 0 ? f * w : w - f * w;
                float tw = (float) ((16 + h(side * 5 + i, 52) * 14) * s * (i == 0 ? 1.2 : 0.85));
                addTrunk(NEAR, x - tw / 2, tw, -10, h + 4);
            }
        }
        // The canopy overhead: dark lobes along the top edge, heavier at the sides.
        for (int i = 0; i < 26 && lobes < MAX_LOBES; i++) {
            float x = (float) ((i + h(i, 53)) * w / 26.0);
            float edge = Math.abs(x - w / 2f) / (w / 2f);
            float y = (float) (-6 * s + edge * h * 0.10 + h(i, 54) * 10 * s);
            addLobe(NEAR, x, y, (float) ((30 + h(i, 55) * 26 + edge * 30) * s), (float) ((14 + h(i, 56) * 10 + edge * 16) * s));
        }
        // Bushes at the bottom corners.
        for (int i = 0; i < 10 && lobes < MAX_LOBES; i++) {
            float f = (float) (h(i, 57) * 0.3);
            float x = i % 2 == 0 ? f * w : w - f * w;
            addLobe(NEAR, x, h - (float) ((4 + h(i, 58) * 12) * s), (float) ((22 + h(i, 59) * 22) * s),
                    (float) ((12 + h(i, 60) * 12) * s));
        }

        // Ferns: clumps along the bottom, mostly toward the sides.
        ferns = Math.min(MAX_FERNS, Math.max(6, w / 90));
        for (int i = 0; i < ferns; i++) {
            double u = h(i, 61);
            double edgeBias = u < 0.5 ? u * 0.7 : 1 - (1 - u) * 0.7;
            fernX[i] = (float) (edgeBias * w);
            fernSize[i] = (float) ((26 + h(i, 62) * 22) * s);
        }
        // Grass tufts all along the bottom.
        tufts = Math.min(MAX_TUFTS, Math.max(20, w / 14));
        for (int i = 0; i < tufts; i++) {
            tuftX[i] = (float) ((i + h(i, 63)) * w / tufts);
            tuftH[i] = (float) ((8 + h(i, 64) * 14) * s);
        }
        // Light shafts: two each side of the menu column.
        for (int i = 0; i < SHAFTS; i++) {
            double f = i < 2 ? 0.08 + i * 0.14 + h(i, 65) * 0.04 : 0.66 + (i - 2) * 0.14 + h(i, 65) * 0.04;
            shaftX[i] = (float) (f * w);
            shaftW[i] = (float) ((16 + h(i, 66) * 22) * s);
        }
    }

    private static void addTrunk(int layer, float x, float tw, float top, float bottom) {
        if (trunks >= MAX_TRUNKS) {
            return;
        }
        trunkLayer[trunks] = layer;
        trunkX[trunks] = x;
        trunkW[trunks] = tw;
        trunkTop[trunks] = top;
        trunkBottom[trunks] = bottom;
        trunks++;
    }

    private static void addLobe(int layer, float x, float y, float rx, float ry) {
        if (lobes >= MAX_LOBES) {
            return;
        }
        lobeLayer[lobes] = layer;
        lobeX[lobes] = x;
        lobeY[lobes] = y;
        lobeRx[lobes] = rx;
        lobeRy[lobes] = ry;
        lobePhase[lobes] = (float) (h(lobes, 70) * Math.PI * 2);
        lobes++;
    }

    private static double h(int i, int salt) {
        return MainMenuScenes.hash(i, salt);
    }

    // ---- drawing --------------------------------------------------------------------------------------------------

    static void draw(GuiRects r, int w, int h, double t, int accent, int panel, int text, float density) {
        if (w != builtW || h != builtH) {
            layout(w, h);
        }
        float s = (float) Math.max(0.7, Math.min(2.2, h / 540.0));
        int mint = MenuStyle.mix(accent, WHITE, 0.55f);
        int fog = MenuStyle.mix(panel, accent, 0.30f);
        int farTrunk = MenuStyle.mix(fog, panel, 0.30f);
        int farLeaf = MenuStyle.mix(fog, panel, 0.12f);
        int midTrunk = MenuStyle.mix(MenuStyle.mix(panel, BLACK, 0.25f), accent, 0.06f);
        int midLeaf = MenuStyle.mix(MenuStyle.mix(panel, BLACK, 0.15f), accent, 0.10f);
        int nearTrunk = MenuStyle.mix(panel, BLACK, 0.62f);
        int nearLeaf = MenuStyle.mix(panel, BLACK, 0.55f);
        int rim = MenuStyle.alpha(mint, 0x30);

        // Parallax: each layer drifts sideways by its own amount, slowly.
        double drift = Math.sin(t * 0.035);
        int farDx = (int) Math.round(drift * 2 * s);
        int midDx = (int) Math.round(drift * 5 * s);
        int nearDx = (int) Math.round(drift * 9 * s);

        // Light from above through the canopy.
        MainMenuScenes.glow(r, w / 2, -10, w * 0.55, h * 0.30, mint, 0x1C, 5);

        // ---- far layer, half lost in fog
        drawTrunks(r, FAR, farDx, farTrunk, 0, s);
        drawLobes(r, FAR, farDx, farLeaf, t, 0.6f * s);
        r.gradient(0, (int) (h * 0.72), w, (int) (h * 0.84), MenuStyle.alpha(fog, 0), MenuStyle.alpha(fog, 0xC0));
        r.fill(0, (int) (h * 0.84), w, h, MenuStyle.alpha(MenuStyle.mix(fog, panel, 0.5f), 0xE0));
        // Haze over the whole far layer: it sits back in the fog.
        r.gradient(0, 0, w, (int) (h * 0.8), MenuStyle.alpha(panel, 0x50), MenuStyle.alpha(fog, 0x40));

        // ---- light shafts falling through the gaps (not across the menu column)
        double breathe = 0.75 + 0.25 * Math.sin(t * 0.4);
        int bands = 14;
        for (int i = 0; i < SHAFTS; i++) {
            // Fanning outward from the middle as they fall: the left pair leans left, the right pair right.
            double slope = (i < 2 ? -0.10 : 0.10) * w / (double) h;
            double sx = shaftX[i] + Math.sin(t * 0.07 + i * 1.7) * 6 * s;
            int sw = (int) shaftW[i];
            double a0 = (0x26 + 0x0C * Math.sin(t * 0.3 + i * 2.1)) * breathe;
            for (int k = 0; k < bands; k++) {
                int y0 = h * k / bands;
                int y1 = h * (k + 1) / bands;
                int x = (int) Math.round(sx + slope * (y0 + y1) * 0.5);
                int a = (int) (a0 * (1.0 - k / (double) bands));
                if (a > 0) {
                    r.fill(x, y0, x + sw, y1, MenuStyle.alpha(mint, a));
                }
            }
        }

        // ---- fireflies behind the mid trees (dim, small)
        fireflies(r, w, h, t, accent, mint, text, density, 0);

        // ---- mid layer
        drawTrunks(r, MID, midDx, midTrunk, MenuStyle.alpha(mint, 0x14), s);
        drawLobes(r, MID, midDx, midLeaf, t, 1.0f * s);
        // The forest floor: a mossy band the undergrowth stands out against.
        int floor = MenuStyle.mix(panel, accent, 0.16f);
        r.gradient(0, (int) (h * 0.86), w, h, MenuStyle.alpha(floor, 0xB0), MenuStyle.alpha(MenuStyle.mix(floor, BLACK, 0.3f), 0xFF));

        // ---- mist along the ground
        for (int k = 0; k < 3; k++) {
            double md = Math.sin(t * (0.05 + k * 0.02) + k * 2.0) * w * 0.15;
            int cy = h - (int) (14 * s) - k * Math.max(12, h / 10);
            int a = (int) (0x1C + 0x0A * Math.sin(t * 0.3 + k));
            MainMenuScenes.glow(r, (int) (w / 2 + md), cy, w * 0.7, h * 0.07, mint, a, 3);
        }

        // ---- fireflies between the mid and near layers
        fireflies(r, w, h, t, accent, mint, text, density, 1);

        // ---- near layer: framing trunks, canopy overhead, undergrowth
        drawTrunks(r, NEAR, nearDx, nearTrunk, rim, s);
        drawLobes(r, NEAR, nearDx, nearLeaf, t, 1.6f * s);
        // Undergrowth, its tops caught by the light: dark fronds and moonlit ones side by side.
        drawFerns(r, h, t, nearDx, MenuStyle.mix(panel, accent, 0.22f), MenuStyle.mix(panel, accent, 0.50f), s);
        drawTufts(r, h, t, nearDx, MenuStyle.mix(panel, accent, 0.40f));

        // ---- falling leaves and the bright near fireflies
        leaves(r, w, h, t, accent, density);
        fireflies(r, w, h, t, accent, mint, text, density, 2);
    }

    private static void drawTrunks(GuiRects r, int layer, int dx, int colour, int rimColour, float s) {
        for (int i = 0; i < trunks; i++) {
            if (trunkLayer[i] != layer) {
                continue;
            }
            int x = Math.round(trunkX[i]) + dx;
            int tw = Math.max(1, Math.round(trunkW[i]));
            int top = Math.round(trunkTop[i]);
            int bottom = Math.round(trunkBottom[i]);
            r.fill(x, top, x + tw, bottom, colour);
            // Root flare at the foot.
            int flare = Math.max(1, tw / 3);
            int fh = Math.max(2, (int) (tw * 0.6f));
            r.fill(x - flare, bottom - fh, x + tw + flare, bottom, colour);
            if (rimColour != 0) {
                // Moonlight down the side facing the middle.
                boolean left = x + tw / 2 < builtW / 2;
                int rx = left ? x + tw - Math.max(1, Math.round(s)) : x;
                r.fill(rx, top, rx + Math.max(1, Math.round(s)), bottom - fh, rimColour);
            }
        }
    }

    /** Lobes as five horizontal bands following an ellipse, swaying {@code sway} at most. */
    private static void drawLobes(GuiRects r, int layer, int dx, int colour, double t, float sway) {
        for (int i = 0; i < lobes; i++) {
            if (lobeLayer[i] != layer) {
                continue;
            }
            double sw = Math.sin(t * 0.45 + lobePhase[i]) * sway;
            int cx = (int) Math.round(lobeX[i] + dx + sw);
            float ry = lobeRy[i];
            float rx = lobeRx[i];
            int y = Math.round(lobeY[i] - ry);
            int bandH = Math.max(1, Math.round(ry * 0.4f));
            for (int k = 0; k < 5; k++) {
                float f = -0.8f + 0.4f * k;
                int half = Math.round(rx * (float) Math.sqrt(1 - f * f));
                // Top bands sway a touch more than the bottom ones, like leaves on a branch.
                int lean = (int) Math.round(sw * (0.6 - k * 0.15));
                r.fill(cx - half + lean, y, cx + half + lean, y + bandH, colour);
                y += bandH;
            }
        }
    }

    /** Ferns: five arching fronds per clump, each a stem of shrinking leaflets that sway at the tips. */
    private static void drawFerns(GuiRects r, int h, double t, int dx, int dark, int lit, float s) {
        for (int i = 0; i < ferns; i++) {
            double baseX = fernX[i] + dx;
            double size = fernSize[i];
            for (int f = 0; f < 5; f++) {
                double ang = Math.toRadians(30 + f * 30); // 30..150 degrees, left to right
                double dirX = Math.cos(ang);
                double dirY = Math.sin(ang);
                double sway = Math.sin(t * 0.8 + i * 1.3 + f) * 0.12;
                int col = (f + i) % 2 == 0 ? dark : lit;
                for (int k = 1; k <= 5; k++) {
                    double u = k / 5.0;
                    // The frond droops as it goes out.
                    double px = baseX + (dirX + sway * u) * size * u;
                    double py = h - dirY * size * u * (1.15 - u * 0.55);
                    int leaf = (int) Math.max(1, (1.0 - u * 0.7) * size * 0.28);
                    int lh = Math.max(1, Math.round(s * 1.5f));
                    r.fill((int) px - leaf / 2, (int) py, (int) px + leaf / 2 + 1, (int) py + lh, col);
                }
            }
        }
    }

    /** Grass tufts: three blades each, the tips leaning with the breeze. */
    private static void drawTufts(GuiRects r, int h, double t, int dx, int colour) {
        for (int i = 0; i < tufts; i++) {
            int x = Math.round(tuftX[i]) + dx;
            int th = Math.round(tuftH[i]);
            int lean = (int) Math.round(Math.sin(t * 0.9 + i * 0.7) * 1.5);
            r.fill(x, h - th, x + 1, h, colour);
            r.fill(x - 2 + lean, h - th * 2 / 3, x - 1 + lean, h, colour);
            r.fill(x + 2 + lean, h - th * 3 / 4, x + 3 + lean, h, colour);
        }
    }

    private static void leaves(GuiRects r, int w, int h, double t, int accent, float density) {
        int n = scaled(w * h / 22000.0, density, 5, 16);
        int leafLight = MenuStyle.mix(accent, WHITE, 0.25f);
        double travel = h + 30.0;
        for (int i = 0; i < n; i++) {
            double r1 = MainMenuScenes.hash(i, 81);
            double r2 = MainMenuScenes.hash(i, 82);
            double r3 = MainMenuScenes.hash(i, 83);
            double fall = (t * (6.0 + r2 * 9.0) + r1 * travel * 7.0) % travel;
            double y = fall - 15.0;
            double x = r3 * w + Math.sin(t * (0.5 + r1 * 0.6) + i * 1.9) * (14.0 + r2 * 20.0);
            int a = (int) (0xB0 * Math.min(1.0, fall / 30.0) * Math.min(1.0, (travel - fall) / 30.0));
            if (a <= 6) {
                continue;
            }
            int px = (int) Math.round(x);
            int py = (int) Math.round(y);
            boolean wide = Math.sin(t * (1.1 + r3) + i) > 0;
            r.fill(px, py, px + (wide ? 3 : 2), py + (wide ? 2 : 3), MenuStyle.alpha(r1 > 0.5 ? leafLight : accent, a));
        }
    }

    /** One of three firefly groups: 0 behind the mid trees (dim, small), 1 between mid and near, 2 in front (bright). */
    private static void fireflies(GuiRects r, int w, int h, double t, int accent, int mint, int text, float density, int group) {
        int flies = scaled(w * h / 6500.0, density, 12, 56);
        for (int i = 0; i < flies; i++) {
            double r0 = MainMenuScenes.hash(i, 90);
            int g = r0 < 0.4 ? 0 : r0 < 0.8 ? 1 : 2;
            if (g != group) {
                continue;
            }
            double r1 = MainMenuScenes.hash(i, 91);
            double r2 = MainMenuScenes.hash(i, 92);
            double r3 = MainMenuScenes.hash(i, 93);
            double x = r1 * w + Math.sin(t * (0.18 + r2 * 0.3) + i * 2.3) * (18 + r3 * 30);
            double y = (0.25 + r2 * 0.7) * h + Math.cos(t * (0.15 + r3 * 0.25) + i * 1.1) * (10 + r1 * 18);
            double pulse = Math.max(0.0, Math.sin(t * (0.7 + r3 * 1.1) + i * 3.7));
            double depth = group == 0 ? 0.45 : group == 1 ? 0.75 : 1.0;
            int a = (int) (255 * pulse * pulse * depth);
            if (a <= 8) {
                continue;
            }
            int px = (int) Math.round(x);
            int py = (int) Math.round(y);
            if (group > 0) {
                r.fill(px - 2, py - 2, px + 3, py + 3, MenuStyle.alpha(accent, a / 8));
            }
            r.fill(px - 1, py - 1, px + 2, py + 2, MenuStyle.alpha(accent, a / 4));
            r.fill(px, py, px + 1, py + 1, MenuStyle.alpha(r1 > 0.6 ? text : mint, a));
        }
    }

    static int scaled(double perArea, float density, int min, int max) {
        if (density <= 0f) {
            return 0;
        }
        int n = (int) Math.round(perArea * density);
        return Math.max(Math.round(min * density), Math.min(Math.round(max * density), n));
    }
}

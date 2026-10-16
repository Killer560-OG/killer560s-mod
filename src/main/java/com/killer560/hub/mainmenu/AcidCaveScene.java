package com.killer560.hub.mainmenu;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.hud.GuiRects;

/**
 * The Green theme's main menu background, the acid cave (the theme's designer, 2026-10-15: "an acid looking cave with an
 * acid lake and waterfall looking thing in the back. I would like a silhouette of my character fighting a big green slime
 * (Minecraft style). The entire thing should have a green tint / green fog. Also animate it."). Back to front:
 * <ul>
 *   <li>the cave's back wall in blocky stone, fogged rock pillars, and an acid waterfall pouring out of a hole in the
 *       ceiling on the right (falling streaks, a foaming splash, droplets and rising mist);</li>
 *   <li>a glowing acid lake along the bottom with drifting ripples, the waterfall's broken reflection, bubbles that rise
 *       and pop, and rings spreading on the surface;</li>
 *   <li>green fog drifting over the lake and up the sides, and motes of it rising;</li>
 *   <li>the cave's frame: blocky side walls and stalactites (short over the title, long at the sides), some dripping;</li>
 *   <li>on a rock ledge bottom left, the designer's character as a dark backlit silhouette in a lunge, swinging a sword in
 *       a loop, facing a big Minecraft slime that hops and squishes and flashes when the blade lands.</li>
 * </ul>
 * The silhouette is a slim (3-pixel-arm) Minecraft player whose outline comes from the designer's public skin
 * (AGreenCatgirl): its hat layer covers the whole head, a pixel wider than the head on every side, with long hair down the
 * back (the overlay's back face is filled to its bottom rows), and a cat girl's two ears on top. The skin itself is not
 * shipped; only that outline, in the proportions below.
 * <p>
 * Nothing moves in the title band or the button column (the middle third), so the menu stays calm and dark there. Every
 * static shape is laid out once per screen size into fixed arrays; a frame allocates nothing beyond the batch's own
 * rectangle parts. Like every scene it draws into the {@link GuiRects} batch {@link MainMenuScenes#draw} opened.
 */
final class AcidCaveScene {

    private AcidCaveScene() {
    }

    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;
    /** The acid's own hue: a hot lime, pulled toward Green's accent below. */
    private static final int LIME = 0xFFB4FF3A;
    /** The silhouette: near black with a green cast. */
    private static final int SHADOW = 0xFF040905;

    // ---- cached geometry (rebuilt only when the size changes) -----------------------------------------------------
    private static int builtW = -1;
    private static int builtH = -1;

    private static final int MAX_BANDS = 320;
    private static int bands;
    private static int bandH;
    private static final int[] leftWall = new int[MAX_BANDS];
    private static final int[] rightWall = new int[MAX_BANDS];

    private static final int MAX_STALS = 48;
    private static int stals;
    private static final float[] stalX = new float[MAX_STALS];
    private static final float[] stalW = new float[MAX_STALS];
    private static final float[] stalLen = new float[MAX_STALS];
    /** Seconds between drips; 0 for a stalactite that does not drip. */
    private static final float[] stalDrip = new float[MAX_STALS];

    private static final int MAX_BLOCKS = 90;
    private static int blocks;
    private static final int[] blockX = new int[MAX_BLOCKS];
    private static final int[] blockY = new int[MAX_BLOCKS];
    private static final int[] blockS = new int[MAX_BLOCKS];
    private static final float[] blockShade = new float[MAX_BLOCKS];

    private static final int PILLARS = 4;
    private static final float[] pillarX = new float[PILLARS];
    private static final float[] pillarW = new float[PILLARS];

    private static final int MAX_STEPS = 8;
    private static int steps;
    private static final int[] stepX0 = new int[MAX_STEPS];
    private static final int[] stepX1 = new int[MAX_STEPS];
    private static final int[] stepTop = new int[MAX_STEPS];

    private static int lakeY;
    private static int ledgeY;
    private static float s;

    private static void layout(int w, int h) {
        builtW = w;
        builtH = h;
        s = (float) Math.max(0.7, Math.min(2.2, h / 540.0));
        lakeY = Math.round(h * 0.80f);
        ledgeY = Math.round(h * 0.865f);
        int bs = Math.max(2, Math.round(6 * s)); // one "block" of the blocky rock

        // Side walls: blocky, wide at the top, thin at the foot (the ledge and the lake), one width per band.
        bandH = Math.max(2, Math.round(6 * s));
        bands = Math.min(MAX_BANDS, h / bandH + 1);
        for (int i = 0; i < bands; i++) {
            double yf = i / (double) Math.max(1, bands - 1);
            int chunk = i / 3;
            double l = w * (0.022 + 0.07 * (1 - yf) * (1 - yf)) + (h(chunk, 1) - 0.5) * 14 * s;
            double r = w * (0.03 + 0.05 * (1 - yf) * (1 - yf) + 0.02 * yf * yf) + (h(chunk, 2) - 0.5) * 14 * s;
            leftWall[i] = Math.max(bs, (int) Math.round(l / bs) * bs);
            rightWall[i] = Math.max(bs, (int) Math.round(r / bs) * bs);
        }

        // Stalactites: long at the sides, short over the title.
        stals = Math.min(MAX_STALS, Math.max(16, w / 26));
        for (int i = 0; i < stals; i++) {
            float x = (float) ((i + 0.1 + h(i, 3) * 0.8) * w / stals);
            double e = Math.abs(x - w / 2.0) / (w / 2.0);
            stalX[i] = x;
            stalW[i] = (float) ((5 + h(i, 4) * 9) * s * (0.7 + 0.6 * e));
            stalLen[i] = (float) ((6 + h(i, 5) * 16 + e * e * 46) * s * (0.45 + 0.55 * e));
            boolean side = x < w * 0.28 || x > w * 0.72;
            stalDrip[i] = side && h(i, 6) > 0.45 ? (float) (2.5 + h(i, 7) * 3.5) : 0f;
        }

        // The back wall's stone blocks, a few shades either side of the wall.
        blocks = 0;
        for (int i = 0; i < MAX_BLOCKS; i++) {
            int size = Math.round((float) ((8 + h(i, 8) * 18) * s));
            blockX[blocks] = (int) (h(i, 9) * w);
            blockY[blocks] = (int) (h * (0.04 + h(i, 10) * 0.74));
            blockS[blocks] = Math.max(2, size / bs * bs);
            blockShade[blocks] = (float) (h(i, 11) - 0.5);
            blocks++;
        }

        // Fogged rock pillars, two each side of the menu (the waterfall falls between the right pair).
        float[] at = {0.06f, 0.22f, 0.725f, 0.885f};
        for (int i = 0; i < PILLARS; i++) {
            pillarX[i] = (float) (w * (at[i] + (h(i, 12) - 0.5) * 0.02));
            pillarW[i] = (float) ((16 + h(i, 13) * 14) * s);
        }

        // The ledge the fight is on: flat to ~30% of the width, then stepping down into the lake.
        steps = 0;
        int x = 0;
        int top = ledgeY;
        int end = Math.round(w * 0.30f);
        addStep(-4, end, top);
        x = end;
        for (int k = 1; k < 5 && steps < MAX_STEPS; k++) {
            int nx = x + Math.max(bs, Math.round((float) ((8 + h(k, 14) * 12) * s)));
            addStep(x, nx, top + k * bs + (int) (h(k, 15) * bs));
            x = nx;
        }
    }

    private static void addStep(int x0, int x1, int top) {
        stepX0[steps] = x0;
        stepX1[steps] = x1;
        stepTop[steps] = top;
        steps++;
    }

    private static double h(int i, int salt) {
        return MainMenuScenes.hash(i, salt + 400);
    }

    // ---- drawing --------------------------------------------------------------------------------------------------

    static void draw(GuiRects r, int w, int h, double t, int accent, int panel, float density) {
        if (w != builtW || h != builtH) {
            layout(w, h);
        }
        int acid = MenuStyle.mix(LIME, accent, 0.35f);
        int hot = MenuStyle.mix(acid, WHITE, 0.45f);
        int deep = MenuStyle.mix(panel, BLACK, 0.55f);
        int wall = MenuStyle.mix(MenuStyle.mix(panel, BLACK, 0.35f), acid, 0.04f);
        int rockNear = MenuStyle.mix(panel, BLACK, 0.72f);

        // ---- the cave behind: dark above, lit green from the lake below
        r.gradient(0, 0, w, (int) (h * 0.55), deep, wall);
        r.gradient(0, (int) (h * 0.55), w, lakeY, wall, MenuStyle.mix(wall, acid, 0.22f));
        for (int i = 0; i < blocks; i++) {
            int c = blockShade[i] >= 0 ? MenuStyle.mix(wall, acid, 0.03f + blockShade[i] * 0.08f)
                    : MenuStyle.mix(wall, BLACK, -blockShade[i] * 0.5f);
            fill(r, blockX[i], blockY[i], blockX[i] + blockS[i], blockY[i] + blockS[i], c);
        }
        // Far fog settling toward the lake.
        r.gradient(0, (int) (h * 0.45), w, lakeY, MenuStyle.alpha(acid, 0), MenuStyle.alpha(acid, 0x2C));

        // ---- fogged pillars (stalactite meets stalagmite)
        int pillar = MenuStyle.mix(MenuStyle.mix(wall, acid, 0.10f), BLACK, 0.15f);
        int pStep = Math.max(3, Math.round(8 * s));
        for (int i = 0; i < PILLARS; i++) {
            for (int y = 0; y < lakeY; y += pStep) {
                double yf = (y + pStep * 0.5) / lakeY;
                double pinch = 0.42 + 0.58 * Math.pow(Math.abs(2 * yf - 1.1) / 1.1, 0.8);
                int half = Math.max(2, (int) Math.round(pillarW[i] * pinch + (h(i * 97 + y / pStep, 16) - 0.5) * 4 * s) / 2);
                int cx = Math.round(pillarX[i]);
                fill(r, cx - half, y, cx + half, Math.min(lakeY, y + pStep), pillar);
            }
        }

        waterfall(r, w, h, t, acid, hot, density);
        lake(r, w, h, t, acid, hot, density);

        // ---- green fog over the lake and up the sides
        for (int k = 0; k < 3; k++) {
            double span = w * 1.6;
            double fx = ((t * (6 + k * 3) * s + h(k, 17) * span) % span) - w * 0.3;
            int fy = lakeY - Math.round((10 + k * 10) * s);
            MainMenuScenes.glow(r, (int) fx, fy, w * 0.28, h * 0.045, acid, 0x26, 3);
        }
        for (int side = 0; side < 2; side++) {
            double fy = h * (0.42 + 0.10 * Math.sin(t * 0.13 + side * 2.2));
            int fx = side == 0 ? (int) (w * 0.10) : (int) (w * 0.90);
            MainMenuScenes.glow(r, fx, (int) fy, w * 0.13, h * 0.20, acid, 0x16, 3);
        }
        motes(r, w, h, t, hot, density);

        // ---- the frame: ceiling, stalactites, side walls
        int frame = MenuStyle.mix(panel, BLACK, 0.80f);
        int ceil = Math.round(5 * s);
        r.gradient(0, 0, w, ceil * 3, frame, MenuStyle.alpha(frame, 0));
        stalactites(r, h, t, frame, acid, hot, ceil);
        int litEdge = MenuStyle.alpha(acid, 0x38);
        int px = Math.max(1, Math.round(s));
        for (int i = 0; i < bands; i++) {
            int y0 = i * bandH;
            int y1 = Math.min(h, y0 + bandH);
            fill(r, 0, y0, leftWall[i], y1, frame);
            fill(r, w - rightWall[i], y0, w, y1, frame);
            if (y0 > h * 0.35) {
                // The lake lights the walls' inner faces from below.
                fill(r, leftWall[i] - px, y0, leftWall[i], y1, litEdge);
                fill(r, w - rightWall[i], y0, w - rightWall[i] + px, y1, litEdge);
            }
        }

        // ---- the ledge and the fight
        int lip = MenuStyle.alpha(hot, 0x60);
        for (int i = 0; i < steps; i++) {
            fill(r, stepX0[i], stepTop[i], stepX1[i], h, rockNear);
            fill(r, stepX0[i], stepTop[i], stepX1[i], stepTop[i] + px, lip);
        }
        // Bottom-right rocks so the lake has a far shore.
        int bs = Math.max(2, Math.round(6 * s));
        for (int k = 0; k < 5; k++) {
            int x0 = w - rightWall[bands - 1] - Math.round((float) ((k + 1) * 16 * s));
            int top = h - Math.round((float) ((4 + h(k, 18) * 10) * s)) / bs * bs - bs;
            fill(r, x0, top, x0 + Math.round(18 * s), h, rockNear);
            fill(r, x0, top, x0 + Math.round(18 * s), top + px, lip);
        }
        fight(r, w, t, acid, hot);

        // ---- the green tint over everything
        r.fill(0, 0, w, h, MenuStyle.alpha(acid, 0x0A));
    }

    private static void fill(GuiRects r, int x0, int y0, int x1, int y1, int c) {
        if (x0 != x1 && y0 != y1) {
            r.fill(x0, y0, x1, y1, c);
        }
    }

    // ---- the waterfall ---------------------------------------------------------------------------------------------

    private static void waterfall(GuiRects r, int w, int h, double t, int acid, int hot, float density) {
        double cx = w * 0.80;
        int top = Math.round(h * 0.09f);
        double w0 = Math.max(12, w * 0.040);
        double w1 = w0 * 1.6;
        int len = lakeY - top;
        MainMenuScenes.glow(r, (int) cx, top + len / 2, w1 * 1.7, len * 0.6, acid, 0x22, 3);
        // The hole it pours out of.
        int mouth = (int) (w0 * 0.75);
        fill(r, (int) cx - mouth, 0, (int) cx + mouth, top + Math.round(2 * s), 0xFF020402);
        r.gradient((int) cx - mouth + 2, top - Math.round(8 * s), (int) cx + mouth - 2, top + 1, MenuStyle.alpha(acid, 0),
                MenuStyle.alpha(hot, 0x70));

        // The sheet: ten bands widening as it falls, brighter toward the foot, a lighter core down the middle.
        int segs = 10;
        for (int k = 0; k < segs; k++) {
            int y0 = top + len * k / segs;
            int y1 = top + len * (k + 1) / segs;
            double ww = w0 + (w1 - w0) * (k + 0.5) / segs;
            int x0 = (int) Math.round(cx - ww / 2);
            int x1 = (int) Math.round(cx + ww / 2);
            int a0 = 0x58 + 0x40 * k / segs;
            int a1 = 0x58 + 0x40 * (k + 1) / segs;
            r.gradient(x0, y0, x1, y1, MenuStyle.alpha(acid, a0), MenuStyle.alpha(acid, a1));
            int c0 = (int) Math.round(cx - ww * 0.18);
            int c1 = (int) Math.round(cx + ww * 0.18);
            fill(r, c0, y0, c1, y1, MenuStyle.alpha(hot, 0x22));
            int e = Math.max(1, Math.round(s));
            fill(r, x0, y0, x0 + e, y1, MenuStyle.alpha(hot, 0x50));
            fill(r, x1 - e, y0, x1, y1, MenuStyle.alpha(hot, 0x50));
        }
        // Falling streaks: bright ones, and a few dark ones for texture.
        int n = scaled(30, density, 10, 40);
        for (int i = 0; i < n; i++) {
            double u = h(i, 20);
            double speed = (95 + h(i, 21) * 90) * s;
            double sl = (10 + h(i, 22) * 26) * s;
            double travel = len + sl;
            double pos = (t * speed + h(i, 23) * travel * 7) % travel;
            int y1 = (int) Math.min(lakeY, top + pos);
            int y0 = (int) Math.max(top, top + pos - sl);
            if (y1 - y0 < 2) {
                continue;
            }
            double ww = w0 + (w1 - w0) * ((y0 + y1) * 0.5 - top) / len;
            int x = (int) Math.round(cx - ww / 2 + 1 + u * (ww - 3));
            int sw = h(i, 24) > 0.6 ? Math.max(2, Math.round(2 * s)) : Math.max(1, Math.round(s));
            int c = i % 5 == 0 ? MenuStyle.alpha(BLACK, 0x48) : MenuStyle.alpha(hot, 0x50 + (int) (h(i, 25) * 0x80));
            r.gradient(x, y0, x + sw, y1, MenuStyle.alpha(c, 0), c);
        }

        // The splash: foam, a glow, droplets thrown out and mist rising.
        double foam = w1 * (0.62 + 0.06 * Math.sin(t * 5.3));
        MainMenuScenes.glow(r, (int) cx, lakeY, w1 * 1.5, 9 * s, hot, 0x58, 3);
        fill(r, (int) (cx - foam), lakeY - Math.round(2 * s), (int) (cx + foam), lakeY + Math.round(2 * s),
                MenuStyle.alpha(hot, 0x80));
        int drops = scaled(18, density, 6, 24);
        for (int i = 0; i < drops; i++) {
            double ph = (t * (0.9 + h(i, 26) * 0.9) + h(i, 27)) % 1.0;
            double dir = h(i, 28) < 0.5 ? -1 : 1;
            double x = cx + (h(i, 29) - 0.5) * w1 + dir * (12 + h(i, 30) * 30) * s * ph;
            double hmax = (8 + h(i, 31) * 20) * s;
            double y = lakeY - 4 * hmax * ph * (1 - ph);
            int a = (int) (0xC0 * (1 - ph));
            int d = h(i, 32) > 0.5 ? Math.max(1, Math.round(2 * s)) : Math.max(1, Math.round(s));
            if (a > 8) {
                fill(r, (int) x, (int) y, (int) x + d, (int) y + d, MenuStyle.alpha(hot, a));
            }
        }
        for (int k = 0; k < 2; k++) {
            double ph = (t * 0.25 + k * 0.5) % 1.0;
            int my = (int) (lakeY - (6 + ph * 40) * s);
            int a = (int) (0x2A * Math.sin(Math.PI * ph));
            MainMenuScenes.glow(r, (int) (cx + Math.sin(t * 0.4 + k) * 8 * s), my, w1 * (1.0 + ph), 12 * s, hot, a, 3);
        }
    }

    // ---- the lake --------------------------------------------------------------------------------------------------

    private static void lake(GuiRects r, int w, int h, double t, int acid, int hot, float density) {
        int surface = MenuStyle.mix(acid, BLACK, 0.38f);
        int bottom = MenuStyle.mix(acid, BLACK, 0.80f);
        // The glow it throws on the air above it, then the acid itself.
        r.gradient(0, lakeY - (int) (h * 0.10), w, lakeY, MenuStyle.alpha(acid, 0), MenuStyle.alpha(acid, 0x48));
        r.gradient(0, lakeY, w, h, surface, bottom);
        int px = Math.max(1, Math.round(s));
        fill(r, 0, lakeY, w, lakeY + px, MenuStyle.alpha(hot, 0xA0));
        int depth = h - lakeY;

        // The waterfall's reflection, broken by the ripples.
        double cx = w * 0.80;
        double w1 = Math.max(12, w * 0.040) * 1.6;
        for (int k = 0; k < 12; k++) {
            int y = lakeY + 2 * px + k * depth / 13;
            double ww = w1 * (1 - k / 16.0) * (0.8 + 0.2 * Math.sin(t * 3 + k));
            int x = (int) Math.round(cx - ww / 2 + Math.sin(t * 2 + k * 0.9) * 3 * s);
            fill(r, x, y, x + (int) ww, y + Math.max(1, Math.round(2 * s)), MenuStyle.alpha(hot, (int) (0x58 * (1 - k / 13.0))));
        }
        // Ripples: short bright lines drifting, longer and thicker toward the front.
        int n = scaled(44, density, 14, 60);
        for (int i = 0; i < n; i++) {
            double d = Math.pow(h(i, 40), 1.4);
            int y = lakeY + 2 * px + (int) (d * (depth - 4 * px));
            double len = (8 + h(i, 41) * 36) * s * (0.4 + 0.8 * d) * (0.75 + 0.25 * Math.sin(t * 0.9 + i));
            double dir = h(i, 42) < 0.5 ? -1 : 1;
            double span = w + len;
            double x = ((h(i, 43) * span + dir * t * (3 + h(i, 44) * 7) * s) % span + span) % span - len;
            int a = (int) (0x26 + 0x50 * (0.5 + 0.5 * Math.sin(t * 1.3 + i * 2.1)));
            int th = d > 0.55 ? Math.max(1, Math.round(2 * s)) : Math.max(1, Math.round(s));
            fill(r, (int) x, y, (int) (x + len), y + th, MenuStyle.alpha(hot, a));
        }
        // Bubbles rising and popping at the surface.
        int b = scaled(24, density, 8, 32);
        for (int i = 0; i < b; i++) {
            double ph = (t * (0.10 + h(i, 45) * 0.14) + h(i, 46)) % 1.0;
            double x = h(i, 47) * w + Math.sin(t * 2.0 + i) * 2 * s;
            if (ph < 0.93) {
                double y = h - 2 - (ph / 0.93) * (depth - 2);
                int sz = Math.max(1, (int) Math.round((1 + h(i, 48) * 2.2) * s * (0.6 + 0.4 * ph)));
                fill(r, (int) x, (int) y, (int) x + sz, (int) y + sz, MenuStyle.alpha(hot, 0x70));
                fill(r, (int) x, (int) y, (int) x + Math.max(1, sz / 2), (int) y + Math.max(1, sz / 2), MenuStyle.alpha(WHITE, 0x60));
            } else {
                double q = (ph - 0.93) / 0.07;
                int rr = (int) Math.round(q * 7 * s);
                int a = (int) (0xB0 * (1 - q));
                fill(r, (int) x - rr - px, lakeY - px, (int) x - rr + px, lakeY + px, MenuStyle.alpha(hot, a));
                fill(r, (int) x + rr - px, lakeY - px, (int) x + rr + px, lakeY + px, MenuStyle.alpha(hot, a));
                int up = (int) Math.round(Math.sin(Math.PI * q) * 4 * s);
                fill(r, (int) x, lakeY - px - up, (int) x + px, lakeY - up, MenuStyle.alpha(hot, a));
            }
        }
        // Rings spreading on the surface.
        for (int i = 0; i < 6; i++) {
            double ph = (t * 0.35 + h(i, 49)) % 1.0;
            int x = (int) (h(i, 50) * w);
            int y = lakeY + 2 * px + (int) (h(i, 51) * depth * 0.5);
            int rw = (int) Math.round((3 + ph * 18) * s);
            int rh = Math.max(2, rw / 4);
            r.outline(x - rw, y - rh / 2, rw * 2, rh, MenuStyle.alpha(hot, (int) (0x80 * (1 - ph))));
        }
    }

    // ---- motes and drips -------------------------------------------------------------------------------------------

    private static void motes(GuiRects r, int w, int h, double t, int hot, float density) {
        int n = scaled(26, density, 8, 36);
        for (int i = 0; i < n; i++) {
            double ph = (t * (0.02 + h(i, 60) * 0.03) + h(i, 61)) % 1.0;
            double x = (i % 2 == 0 ? h(i, 62) * 0.27 : 1 - h(i, 62) * 0.25) * w + Math.sin(t * 0.5 + i) * 8 * s;
            double y = lakeY - ph * lakeY * 0.85;
            int a = (int) (0xA0 * Math.sin(Math.PI * ph) * (0.5 + 0.5 * Math.sin(t * 1.7 + i * 3.1)));
            if (a > 8) {
                int d = Math.max(1, Math.round(s));
                fill(r, (int) x, (int) y, (int) x + d, (int) y + d, MenuStyle.alpha(hot, a));
            }
        }
    }

    private static void stalactites(GuiRects r, int h, double t, int frame, int acid, int hot, int ceil) {
        int step = Math.max(2, Math.round(3 * s));
        for (int i = 0; i < stals; i++) {
            int len = Math.round(stalLen[i]);
            int x = Math.round(stalX[i]);
            int rows = Math.max(1, len / step);
            for (int k = 0; k < rows; k++) {
                int half = Math.max(1, Math.round(stalW[i] * (1 - k / (float) rows) / 2));
                fill(r, x - half, ceil + k * step, x + half, ceil + (k + 1) * step, frame);
            }
            fill(r, x - Math.round(stalW[i] / 2), 0, x + Math.round(stalW[i] / 2), ceil, frame);
            int tip = ceil + rows * step;
            // Its tip catches the lake's light.
            fill(r, x - 1, tip - step, x + 1, tip, MenuStyle.alpha(acid, 0x40));
            if (stalDrip[i] > 0) {
                double ph = (t / stalDrip[i] + h(i, 70)) % 1.0;
                int d = Math.max(1, Math.round(s));
                if (ph < 0.7) {
                    int grow = (int) Math.round(ph / 0.7 * 2 * s);
                    fill(r, x - d / 2 - 0, tip, x - d / 2 + d, tip + d + grow, MenuStyle.alpha(hot, 0x90));
                } else {
                    double ft = (ph - 0.7) * stalDrip[i];
                    double y = tip + 0.5 * 260 * s * ft * ft;
                    if (y < lakeY) {
                        fill(r, x - d / 2, (int) y, x - d / 2 + d, (int) y + 3 * d, MenuStyle.alpha(hot, 0xB0));
                    }
                }
            }
        }
    }

    // ---- the fight -------------------------------------------------------------------------------------------------

    /** Seconds per sword swing (and per slime hop). */
    private static final double PERIOD = 1.6;

    private static void fight(GuiRects r, int w, double t, int acid, int hot) {
        float u = builtH * 0.0058f; // one skin pixel
        float px = w * 0.10f;
        float feet = ledgeY;
        double p = (t / PERIOD) % 1.0;

        // The swing: wind up behind the head, a fast overhead strike that lands at p 0.62, then recover.
        double theta;
        if (p < 0.5) {
            theta = 30 + 160 * ease(p / 0.5);
        } else if (p < 0.62) {
            double q = (p - 0.5) / 0.12;
            theta = 190 - 180 * q * q;
        } else {
            theta = 10 + 20 * ease((p - 0.62) / 0.38);
        }
        double lunge = p >= 0.5 && p < 0.8 ? Math.sin(Math.PI * (p - 0.5) / 0.3) : 0;

        // The slime: hops, lands just before the blade, squashes under it.
        double S = 36 * u;
        double q = (p + 0.155) % 1.0;
        double air = 0;
        double sx = 1;
        double sy = 1;
        if (q < 0.55) {
            double a = q / 0.55;
            air = 15 * u * 4 * a * (1 - a);
            double stretch = 0.10 * Math.sin(Math.PI * a);
            sx = 1 - stretch;
            sy = 1 + stretch;
        } else {
            double sq = Math.sin(Math.PI * (q - 0.55) / 0.45);
            sx = 1 + 0.20 * sq;
            sy = 1 - 0.20 * sq;
        }
        double scx = px + 37 * u + lunge * 2 * u;
        int sw = (int) Math.round(S * sx);
        int shh = (int) Math.round(S * sy);
        int sl = (int) Math.round(scx - sw / 2.0);
        int sb = (int) Math.round(feet - air);
        int st = sb - shh;

        // Light behind them both, so the silhouette reads against the wall.
        MainMenuScenes.glow(r, (int) (px + 18 * u), (int) (feet - 18 * u), 34 * u, 22 * u, acid, 0x30, 3);
        MainMenuScenes.glow(r, (int) scx, (int) (feet - air - shh / 2.0), S * 0.95, S * 0.75, acid, 0x34, 3);
        // The slime's shadow, smaller while it is in the air.
        double shadow = sw * 0.55 * (1 - air / (15 * u) * 0.45);
        MainMenuScenes.glow(r, (int) scx, (int) feet, shadow, 2.5 * u, BLACK, 0xA0, 2);

        // The slime: a translucent outer cube, a solid inner one, its face turned toward the player.
        int slimeOuter = MenuStyle.mix(LIME, acid, 0.5f);
        fill(r, sl, st, sl + sw, sb, MenuStyle.alpha(slimeOuter, 0x9C));
        int ci = (int) Math.round(sw * 0.2);
        int cj = (int) Math.round(shh * 0.2);
        fill(r, sl + ci, st + cj, sl + sw - ci, sb - cj, MenuStyle.alpha(MenuStyle.mix(acid, BLACK, 0.30f), 0xE0));
        fill(r, sl, st, sl + sw, st + Math.max(1, shh / 9), MenuStyle.alpha(WHITE, 0x38));
        fill(r, sl + sw - Math.max(1, sw / 7), st, sl + sw, sb, MenuStyle.alpha(BLACK, 0x36));
        r.outline(sl, st, sw, shh, MenuStyle.alpha(MenuStyle.mix(acid, BLACK, 0.55f), 0xC0));
        int eye = Math.max(2, (int) Math.round(sw * 0.13));
        int eyeY = st + (int) Math.round(shh * 0.30);
        int faceDark = 0xF0102A12;
        fill(r, sl + (int) (sw * 0.14), eyeY, sl + (int) (sw * 0.14) + eye, eyeY + eye, faceDark);
        fill(r, sl + (int) (sw * 0.44), eyeY, sl + (int) (sw * 0.44) + eye, eyeY + eye, faceDark);
        int mouth = Math.max(1, (int) Math.round(sw * 0.08));
        int mouthY = st + (int) Math.round(shh * 0.62);
        fill(r, sl + (int) (sw * 0.30), mouthY, sl + (int) (sw * 0.30) + mouth, mouthY + mouth, faceDark);
        if (p >= 0.6 && p < 0.7) {
            fill(r, sl, st, sl + sw, sb, MenuStyle.alpha(WHITE, (int) (0x60 * (1 - (p - 0.6) / 0.1))));
        }

        // The player: an acid rim one pixel out, then the silhouette over it.
        int rim = MenuStyle.alpha(hot, 0x78);
        int e = Math.max(1, Math.round(s));
        figure(r, px, feet, u, p, theta, lunge, e, rim);
        figure(r, px, feet, u, p, theta, lunge, 0, SHADOW);

        // The blade's trail through the strike.
        if (p >= 0.5 && p < 0.72) {
            double fade = p < 0.62 ? 1 : 1 - (p - 0.62) / 0.10;
            double shX = px + 3.5 * u + lunge * 1.5 * u;
            double shY = feet - 11 * u + lunge * u - 10.5 * u;
            for (int k = 1; k <= 7; k++) {
                double ang = Math.min(190, theta + k * 15);
                int a = (int) (0x90 * (1 - k / 8.0) * fade);
                if (a <= 6) {
                    continue;
                }
                double hx = shX + 12 * u * Math.sin(Math.toRadians(ang));
                double hy = shY + 12 * u * Math.cos(Math.toRadians(ang));
                double b = Math.toRadians(ang + 60);
                for (int j = 2; j <= 3; j++) {
                    double tx = hx + 5 * j * u * Math.sin(b);
                    double ty = hy + 5 * j * u * Math.cos(b);
                    int d = (int) Math.max(1, u);
                    fill(r, (int) tx - d / 2, (int) ty - d / 2, (int) tx + d / 2 + 1, (int) ty + d / 2 + 1, MenuStyle.alpha(hot, a));
                }
            }
        }
    }

    private static double ease(double x) {
        return x * x * (3 - 2 * x);
    }

    /**
     * The player at {@code px}, feet on {@code feet}, facing right, every part grown by {@code e} pixels (the rim pass).
     * Slim model: 3-pixel arms, 4-pixel legs, the head one hat-layer pixel wider all round, hair down the back, two ears.
     */
    private static void figure(GuiRects r, float px, float feet, float u, double p, double theta, double lunge, int e, int c) {
        double bob = Math.sin(p * Math.PI * 2) * 0.4 * u;
        double hipX = px + lunge * 1.5 * u;
        double hipY = feet - 11 * u + lunge * u + bob;
        // Legs in a lunge: the front foot forward, the back foot behind.
        bar(r, hipX, hipY, px + 6 * u, feet - 2 * u, 4 * u, e, c);
        bar(r, hipX, hipY, px - 6 * u, feet - 2 * u, 4 * u, e, c);
        rect(r, px + 4 * u, feet - 2.5 * u, px + 9 * u, feet, e, c);
        rect(r, px - 8.5 * u, feet - 2.5 * u, px - 4 * u, feet, e, c);
        // The back arm, swinging a little behind.
        double neckX = hipX + 2 * u;
        double neckY = hipY - 12 * u;
        double back = Math.toRadians(-25 + 10 * Math.sin(p * Math.PI * 2));
        bar(r, neckX - 1 * u, neckY + 1.5 * u, neckX - u + 11 * u * Math.sin(back), neckY + 1.5 * u + 11 * u * Math.cos(back),
                3 * u, e, c);
        // The body, leaning into the swing.
        bar(r, hipX, hipY - 1 * u, neckX, neckY + 1.5 * u, 5.5 * u, e, c);
        // The head with its hat layer (9 pixels where the head is 8), the hair down its back and the ears on top.
        double hx = neckX + 1 * u;
        double hy = neckY - 4.5 * u;
        rect(r, hx - 4.5 * u, hy - 4.5 * u, hx + 4.5 * u, hy + 4.5 * u, e, c);
        rect(r, hx - 5 * u, hy - 3.5 * u, hx - 2.5 * u, hy + 6.5 * u, e, c);
        rect(r, hx - 4.5 * u, hy + 4 * u, hx - 3 * u, hy + 8 * u, e, c);
        rect(r, hx - 3 * u, hy + 4 * u, hx - 1.8 * u, hy + 6 * u, e, c);
        ear(r, hx - 3.5 * u, hy - 4.5 * u, u, e, c);
        ear(r, hx + 2 * u, hy - 4.5 * u, u, e, c);
        // The sword arm and the sword: the blade 60 degrees on from the arm, a crossguard at the hand.
        double shX = px + 3.5 * u + lunge * 1.5 * u;
        double shY = hipY - 10.5 * u;
        double a = Math.toRadians(theta);
        double handX = shX + 12 * u * Math.sin(a);
        double handY = shY + 12 * u * Math.cos(a);
        bar(r, shX, shY, handX, handY, 3 * u, e, c);
        double b = a + Math.toRadians(60);
        double bx = Math.sin(b);
        double by = Math.cos(b);
        bar(r, handX - 2.5 * u * bx, handY - 2.5 * u * by, handX + 15 * u * bx, handY + 15 * u * by, 1.7 * u, e, c);
        bar(r, handX - 2.2 * u * by, handY + 2.2 * u * bx, handX + 2.2 * u * by, handY - 2.2 * u * bx, 1.4 * u, e, c);
    }

    /** A pointed cat ear, three steps, its base at {@code x}..{@code x + 2.5u} on the head's top {@code y}. */
    private static void ear(GuiRects r, double x, double y, float u, int e, int c) {
        rect(r, x, y - 1.2 * u, x + 2.6 * u, y + 0.2, e, c);
        rect(r, x + 0.4 * u, y - 2.2 * u, x + 2.0 * u, y - 1.1 * u, e, c);
        rect(r, x + 0.8 * u, y - 3.0 * u, x + 1.5 * u, y - 2.1 * u, e, c);
    }

    private static void rect(GuiRects r, double x0, double y0, double x1, double y1, int e, int c) {
        fill(r, (int) Math.round(x0) - e, (int) Math.round(y0) - e, (int) Math.round(x1) + e, (int) Math.round(y1) + e, c);
    }

    /** A thick line as overlapping squares: rotated limbs out of axis-aligned fills. */
    private static void bar(GuiRects r, double x0, double y0, double x1, double y1, double thick, int e, int c) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double len = Math.sqrt(dx * dx + dy * dy);
        double step = Math.max(1, thick * 0.5);
        int n = Math.max(1, (int) Math.ceil(len / step));
        int half = Math.max(1, (int) Math.round(thick / 2));
        for (int i = 0; i <= n; i++) {
            int x = (int) Math.round(x0 + dx * i / n);
            int y = (int) Math.round(y0 + dy * i / n);
            fill(r, x - half - e, y - half - e, x + half + e, y + half + e, c);
        }
    }

    private static int scaled(int base, float density, int min, int max) {
        if (density <= 0f) {
            return 0;
        }
        return Math.max(Math.round(min * density), Math.min(Math.round(max * density), Math.round(base * density)));
    }
}

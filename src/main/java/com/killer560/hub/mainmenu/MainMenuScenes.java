package com.killer560.hub.mainmenu;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.hud.GuiRects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Util;

/**
 * The main menu's animated backgrounds (killer560, 2026-10-15: "You can make custom things like the waterfall theme is
 * more like falling water"), one per theme and all drawn in that theme's colours: Embers (Amber), Waterfall (Ocean),
 * Starfield (Dark), Soft Light (Light), Aurora (Custom) and Plain.
 * <p>
 * Every position is a function of the clock, the screen size and a per-particle hash - no per-frame state, so a resize
 * or a GUI-scale change costs nothing and nothing accumulates. All of a scene is ONE render element through
 * {@link GuiRects} (docs/LESSONS-GUI.md: every separate fill is placed against every earlier one), a few hundred quads.
 */
public final class MainMenuScenes {

    private MainMenuScenes() {
    }

    /** Which background Amber's scene draws (killer560, 2026-10-15: "send me a quick video of it and I will tell you to
     *  swap back or not"). NEW is {@link AmberForgeScene} (the dusk colonnade); EMBERS is the original drifting embers,
     *  kept below; SIMPLE is {@link AmberCalmScene}, the embers refined (depth, a low warm glow, faint heat haze). He
     *  preferred the simpler style, so the default went back to EMBERS until he picks between EMBERS and SIMPLE. AURORA is
     *  {@link AmberAuroraScene}, his orange aurora picture animated (2026-10-16), Amber's scene only with
     *  {@link #previewScenes()} ({@link #AMBER_SCENE_PREVIEW}). */
    public enum AmberScene { NEW, EMBERS, SIMPLE, AURORA }

    public static final AmberScene AMBER_SCENE = AmberScene.EMBERS;
    /** Amber's scene in the Title Screen GUI instance ({@link #previewScenes()}) until he approves it for the others. */
    public static final AmberScene AMBER_SCENE_PREVIEW = AmberScene.AURORA;

    /** Which background Green's Fireflies scene draws (killer560, 2026-10-15: "can you make the main menu more of a
     *  forest?"): FOREST is {@link MainMenuFireflies} (layered trees, canopy, undergrowth, light shafts); SIMPLE is
     *  {@link MainMenuFirefliesSimple}, the first lighter scene; ACID is {@link AcidCaveScene}, the designer's acid cave
     *  (2026-10-15: "an acid looking cave with an acid lake and waterfall ... a silhouette of my character fighting a big
     *  green slime"); BOSS is {@link SlimeBossScene}, the designer's second idea (2026-10-15: "the hollow knight is just the
     *  silhouette of a minecraft looking character, and the radiance is the slime boss ... the only animation being the
     *  screen swaying a little side to side"); TARGET is {@link TargetScene}, the designer's own picture of it, animated
     *  (2026-10-16: "match THIS image almost exactly"), the default. FOREST, SIMPLE, ACID and BOSS stay selectable here. */
    public enum GreenScene { FOREST, SIMPLE, ACID, BOSS, TARGET }

    public static final GreenScene GREEN_SCENE = GreenScene.TARGET;

    /** Dark's background (killer560, 2026-10-16: "can you make the starfield more milky way themed?", then "For the
     *  starfield one make one without the character as it is building now and one with", his own picture, and "mainly
     *  black and white"): STARS is the original drifting starfield; MILKY_WAY_HERO is {@link MilkyWayTexScene}, his
     *  picture graded black and white and animated ({@link PictureScene}); MILKY_WAY the same without the character. */
    public enum DarkScene { STARS, MILKY_WAY, MILKY_WAY_HERO }

    public static final DarkScene DARK_SCENE = DarkScene.MILKY_WAY_HERO;
    public static final DarkScene DARK_SCENE_RELEASED = DarkScene.STARS;

    private static DarkScene darkScene;

    /** The Dark scene drawn now: the session's pick, else {@link #DARK_SCENE} with {@link #previewScenes()}, else the
     *  released one. */
    public static DarkScene darkScene() {
        DarkScene d = darkScene;
        return d != null ? d : previewScenes() ? DARK_SCENE : DARK_SCENE_RELEASED;
    }

    /** Draws {@code scene} for Dark this session (the Title Screen Switcher); null goes back to the default. */
    public static void setDarkScene(DarkScene scene) {
        darkScene = scene;
    }

    /** Test hook (testkit 1680-1683): the named Dark scene; null or empty goes back to the default. */
    public static void setDarkSceneForTest(String name) {
        darkScene = name == null || name.isEmpty() ? null : DarkScene.valueOf(name);
    }

    /** Test probe: tiles a picture scene's ripples (a character's cape) redrew in the last frame (0 = none). */
    public static int heroRects;

    /** What every other instance shows until killer560 approves the new pictures (2026-10-16: "for these new pictures only
     *  push them to my title screen instance not the others yet"): the scene that was last deployed. */
    public static final GreenScene GREEN_SCENE_RELEASED = GreenScene.FOREST;

    /** True in the Title Screen GUI instance (its marker) or with -Dkiller560smod.previewScenes=true (the testkit's scene
     *  checks): the new main menu pictures. Everywhere else the released ones. */
    public static boolean previewScenes() {
        return TitleScreenLooks.enabled() || Boolean.getBoolean("killer560smod.previewScenes");
    }

    /** The Green scene this game shows by default: {@link #GREEN_SCENE} with {@link #previewScenes()}, otherwise
     *  {@link #GREEN_SCENE_RELEASED}. */
    public static GreenScene defaultGreenScene() {
        // Released to every instance 2026-10-16 ("push it to all instances as the viridian theme").
        return GREEN_SCENE;
    }

    private static GreenScene greenScene;

    /** Test hook (testkit 1651/1653): draws the named Green scene ({@code "FOREST"}, {@code "SIMPLE"}, {@code "ACID"},
     *  {@code "BOSS"}, {@code "TARGET"});
     *  null or empty goes back to the constant's choice. */
    public static void setGreenSceneForTest(String name) {
        greenScene = name == null || name.isEmpty() ? null : GreenScene.valueOf(name);
    }

    /** The session's Amber pick (the Title Screen Switcher, a test), or null for the default. */
    private static AmberScene amberScene;
    private static long testClockMs = -1L;

    /** The Amber scene drawn now: the session's pick, else {@link #AMBER_SCENE_PREVIEW} with {@link #previewScenes()},
     *  else {@link #AMBER_SCENE}. */
    public static AmberScene amberScene() {
        AmberScene a = amberScene;
        return a != null ? a : previewScenes() ? AMBER_SCENE_PREVIEW : AMBER_SCENE;
    }

    /** The Green scene drawn now (see {@link #amberScene()}). */
    public static GreenScene greenScene() {
        GreenScene g = greenScene;
        return g != null ? g : defaultGreenScene();
    }

    /** Draws {@code scene} for Amber this session (the Title Screen Switcher); null goes back to the default
     *  ({@link #amberScene()}). Changes no setting. */
    public static void setAmberScene(AmberScene scene) {
        amberScene = scene;
    }

    /** Draws {@code scene} for Green this session (the Title Screen Switcher); null goes back to {@link #GREEN_SCENE}. */
    public static void setGreenScene(GreenScene scene) {
        greenScene = scene;
    }

    /** Test hook (killer560s-mod-testkit): fixes the scene clock to {@code ms}; a negative value releases it. */
    public static void setClockForTest(double ms) {
        testClockMs = ms < 0 ? -1L : Math.round(ms);
    }

    /** Test hook: draws the old embers ({@code true}) or the constant's choice ({@code false}) for Amber. */
    public static void setOldEmbersForTest(boolean old) {
        amberScene = old ? AmberScene.EMBERS : null;
    }

    /** Test hook (testkit 1568/1569/1695): draws the named Amber scene ({@code "NEW"}, {@code "EMBERS"}, {@code "SIMPLE"},
     *  {@code "AURORA"}); null or empty goes back to the default. */
    public static void setAmberSceneForTest(String name) {
        amberScene = name == null || name.isEmpty() ? null : AmberScene.valueOf(name);
    }

    /** Test probe (testkit 1568): while set, {@link #draw} adds the bytes it allocated, the frames and the rectangles
     *  it batched to these. Off in play. */
    public static boolean allocProbe;
    public static long allocBytes;
    public static long allocFrames;
    public static long allocQuads;
    private static java.lang.reflect.Field quadCount;

    private static long allocatedNow() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean())
                .getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    /** Seconds on the scene clock (speed applied), wrapped every hour so floats stay precise. */
    static double clock(float speed) {
        long ms = testClockMs >= 0 ? testClockMs : Util.getMillis();
        return (ms % 3_600_000L) / 1000.0 * speed;
    }

    public static void draw(GuiGraphicsExtractor g, int w, int h, MainMenuLook look) {
        if (w <= 0 || h <= 0) {
            return;
        }
        long probeStart = allocProbe ? allocatedNow() : 0L;
        double t = clock(look.speed());
        int accent = look.accent() | 0xFF000000;
        int panel = look.panelColour() | 0xFF000000;
        int text = look.text() | 0xFF000000;
        boolean light = MenuStyle.luminance(panel) > 0.5f;
        boolean target = look.scene() == MainMenuThemeConfig.Scene.FIREFLIES && greenScene() == GreenScene.TARGET;
        if (target) {
            // The designer's picture is the base, blitted before the batch so the batch's overlays land on top of it.
            TargetScene.drawImage(g, w, h, t);
            heroRects = TargetScene.SCENE.rippleTiles;
        }
        GuiRects r = GuiRects.begin(g);
        if (target) {
            TargetScene.drawOverlays(r, w, h, t);
            r.submit();
            if (allocProbe) {
                allocBytes += allocatedNow() - probeStart;
                allocFrames++;
            }
            return;
        }
        if (look.scene() == MainMenuThemeConfig.Scene.EMBERS && amberScene() == AmberScene.AURORA) {
            // His picture is the base (through its shader), drawn before the batch so the embers land on top of it.
            AmberAuroraScene.drawImage(g, w, h, t);
            heroRects = 0;
            AmberAuroraScene.drawOverlays(r, w, h, t, look.density());
            r.submit();
            probe(r, probeStart);
            return;
        }
        DarkScene dark = darkScene();
        if (look.scene() == MainMenuThemeConfig.Scene.STARFIELD
                && (dark == DarkScene.MILKY_WAY || dark == DarkScene.MILKY_WAY_HERO)) {
            boolean hero = dark == DarkScene.MILKY_WAY_HERO;
            // His picture is the base, blitted before the batch so the batch's overlays land on top of it.
            PictureScene sc = MilkyWayTexScene.scene(hero);
            sc.drawImage(g, w, h, t);
            heroRects = sc.rippleTiles;
            sc.drawOverlays(r, w, h, t, look.density());
            r.submit();
            probe(r, probeStart);
            return;
        }
        base(r, w, h, accent, panel, light);
        switch (look.scene()) {
            case EMBERS -> {
                switch (amberScene()) {
                    case NEW -> AmberForgeScene.draw(r, w, h, t, accent, look.density());
                    case SIMPLE -> AmberCalmScene.draw(r, w, h, t, accent, look.density());
                    default -> embers(r, w, h, t, accent, look.density());
                }
            }
            case WATERFALL -> waterfall(r, w, h, t, accent, panel, text, look.density());
            case STARFIELD -> starfield(r, w, h, t, accent, text, look.density());
            case AURORA -> aurora(r, w, h, t, accent, look.switchOn() | 0xFF000000, look.density());
            case FIREFLIES -> {
                switch (greenScene()) {
                    case SIMPLE -> MainMenuFirefliesSimple.draw(r, w, h, t, accent, panel, text, look.density());
                    case FOREST -> MainMenuFireflies.draw(r, w, h, t, accent, panel, text, look.density());
                    case ACID -> AcidCaveScene.draw(r, w, h, t, accent, panel, look.density());
                    default -> SlimeBossScene.draw(r, w, h, t, accent, panel, look.density());
                }
            }
            case SOFT_LIGHT -> softLight(r, w, h, t, accent, panel, light, look.density());
            default -> glow(r, w / 2, 55, Math.min(w * 0.55, 300), Math.min(h * 0.4, 110), accent, 0x24, 9);
        }
        // The acid cave and the slime boss light themselves (the lake, the boss): the vignette would put them out.
        if (look.scene() != MainMenuThemeConfig.Scene.FIREFLIES
                || (greenScene() != GreenScene.ACID && greenScene() != GreenScene.BOSS)) {
            vignette(r, w, h, light);
        }
        heroRects = 0;
        r.submit();
        probe(r, probeStart);
    }

    private static void probe(GuiRects r, long probeStart) {
        if (allocProbe) {
            allocBytes += allocatedNow() - probeStart;
            allocFrames++;
            try {
                if (quadCount == null) {
                    quadCount = GuiRects.class.getDeclaredField("count");
                    quadCount.setAccessible(true);
                }
                allocQuads += quadCount.getInt(r);
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- shared pieces

    private static void base(GuiRects r, int w, int h, int accent, int panel, boolean light) {
        int top;
        int bottom;
        if (light) {
            top = MenuStyle.mix(panel, 0xFFFFFFFF, 0.35f);
            bottom = MenuStyle.mix(panel, accent, 0.14f);
        } else {
            top = MenuStyle.mix(MenuStyle.mix(panel, 0xFF000000, 0.25f), accent, 0.07f);
            bottom = MenuStyle.mix(panel, 0xFF000000, 0.78f);
        }
        r.gradient(0, 0, w, h, top, bottom);
    }

    private static void vignette(GuiRects r, int w, int h, boolean light) {
        int edge = light ? 0x30000000 : 0x80000000;
        r.gradient(0, 0, w, Math.max(1, h / 5), edge, 0x00000000);
        r.gradient(0, h - Math.max(1, h / 4), w, h, 0x00000000, light ? 0x38000000 : 0xA0000000);
        int strips = 10;
        int stripW = Math.max(1, w / 60);
        int maxA = light ? 0x28 : 0x70;
        for (int i = 0; i < strips; i++) {
            int c = MenuStyle.alpha(0xFF000000, (int) (maxA * (1.0 - (double) i / strips)));
            r.fill(i * stripW, 0, (i + 1) * stripW, h, c);
            r.fill(w - (i + 1) * stripW, 0, w - i * stripW, h, c);
        }
    }

    /** Soft elliptical glow: nested ellipses drawn as horizontal bands of low alpha. */
    static void glow(GuiRects r, int cx, int cy, double rx, double ry, int rgb, int maxAlpha, int layers) {
        if (rx < 1 || ry < 1 || maxAlpha <= 0) {
            return;
        }
        int color = MenuStyle.alpha(rgb, Math.max(1, maxAlpha / layers));
        for (int k = layers; k >= 1; k--) {
            double s = (double) k / layers;
            double lrx = rx * s;
            double lry = ry * s;
            int step = Math.max(2, (int) Math.ceil(lry / 20.0));
            for (int y = (int) Math.floor(cy - lry); y < Math.ceil(cy + lry); y += step) {
                double dy = ((y + step * 0.5) - cy) / lry;
                double q = 1.0 - dy * dy;
                if (q <= 0) {
                    continue;
                }
                int half = (int) Math.round(lrx * Math.sqrt(q));
                if (half > 0) {
                    r.fill(cx - half, y, cx + half, y + step, color);
                }
            }
        }
    }

    /** A filled disc as horizontal spans. */
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

    static double hash(int i, int salt) {
        double v = Math.sin(i * 12.9898 + salt * 78.233) * 43758.5453;
        return v - Math.floor(v);
    }

    private static int count(double perArea, int w, int h, float density, int min, int max) {
        if (density <= 0f) {
            return 0;
        }
        int n = (int) Math.round(w * h / perArea * density);
        return Math.max(Math.round(min * density), Math.min(Math.round(max * density), n));
    }

    // ---------------------------------------------------------------- Embers (Amber)

    private static void embers(GuiRects r, int w, int h, double t, int accent, float density) {
        int logoCx = w / 2 + (int) Math.round(Math.sin(t * 0.21) * Math.min(40, w * 0.06));
        int logoCy = 55 + (int) Math.round(Math.cos(t * 0.17) * 6);
        double breathe = 0.85 + 0.15 * Math.sin(t * 0.6);
        glow(r, logoCx, logoCy, Math.min(w * 0.55, 300), Math.min(h * 0.40, 110), accent, (int) (0x2C * breathe), 9);
        int lowCx = w / 2 - (int) Math.round(Math.sin(t * 0.13) * w * 0.12);
        glow(r, lowCx, h + 10, w * 0.75, h * 0.42, accent, 0x26, 9);

        int lightAccent = MenuStyle.mix(accent, 0xFFFFFFFF, 0.3f);
        int n = count(3500, w, h, density, 24, 70);
        double travel = h + 24.0;
        for (int i = 0; i < n; i++) {
            double r1 = hash(i, 1);
            double r2 = hash(i, 2);
            double r3 = hash(i, 3);
            double speed = 5.0 + r2 * 13.0;
            double rise = (t * speed + r1 * travel * 11.0) % travel;
            double y = h + 12.0 - rise;
            double x = r3 * w + Math.sin(t * (0.25 + r1 * 0.45) + i * 1.3) * (5.0 + r2 * 12.0);
            double progress = rise / travel;
            double life = Math.min(1.0, progress * 8.0) * (1.0 - progress);
            double flicker = 0.7 + 0.3 * Math.sin(t * (2.0 + r3 * 2.5) + i * 1.7);
            int a = (int) (215 * life * flicker);
            if (a <= 4) {
                continue;
            }
            int px = (int) Math.round(x);
            int py = (int) Math.round(y);
            int size = r2 > 0.82 ? 2 : 1;
            r.fill(px - 1, py - 1, px + size + 1, py + size + 1, MenuStyle.alpha(accent, a / 4));
            r.fill(px, py, px + size, py + size, MenuStyle.alpha(r1 > 0.5 ? lightAccent : accent, a));
        }
    }

    // ---------------------------------------------------------------- Waterfall (Ocean)

    /**
     * A wide fall of water behind the menu: rock walls with ragged edges on both sides, a translucent curtain, hundreds
     * of streaks pouring down at different speeds (faster and brighter at the front), white water and mist boiling up
     * where it lands, and ripples running across the pool.
     */
    private static void waterfall(GuiRects r, int w, int h, double t, int accent, int panel, int text, float density) {
        int water = MenuStyle.mix(accent, 0xFFFFFFFF, 0.25f);
        int foam = MenuStyle.mix(accent, 0xFFFFFFFF, 0.75f);
        int rock = MenuStyle.mix(panel, 0xFF000000, 0.55f);
        int rockEdge = MenuStyle.mix(rock, accent, 0.12f);

        int left = (int) (w * 0.16);
        int right = (int) (w * 0.84);
        int poolY = h - Math.max(34, h / 7);

        // Light coming over the lip at the top, and the curtain of falling water.
        glow(r, w / 2, -6, (right - left) * 0.6, h * 0.22, water, 0x40, 4);
        r.gradient(left, 0, right, poolY, MenuStyle.alpha(accent, 0x18), MenuStyle.alpha(accent, 0x40));
        // Sheets: wide, faint vertical bands of water that brighten and fade, so the fall has body between the streaks.
        int sheets = 7;
        for (int k = 0; k < sheets; k++) {
            int sx = left + (int) ((right - left) * (k + 0.5) / sheets + Math.sin(t * 0.3 + k) * 6);
            int sw = 10 + (int) (hash(k, 16) * 18);
            int a = (int) (0x18 + 0x14 * Math.sin(t * (0.6 + hash(k, 17)) + k * 1.7));
            r.gradient(sx - sw / 2, 0, sx + sw / 2, poolY, MenuStyle.alpha(water, a / 2), MenuStyle.alpha(water, a));
        }

        // Streaks: a back layer (slow, dim, thin) and a front layer (fast, bright).
        int n = count(120, right - left, h, density, 80, 480);
        for (int i = 0; i < n; i++) {
            double r1 = hash(i, 11);
            double r2 = hash(i, 12);
            double r3 = hash(i, 13);
            double r4 = hash(i, 14);
            boolean front = r4 > 0.55;
            double len = (front ? 16 : 8) + r2 * (front ? 34 : 18);
            double speed = (front ? 120 : 60) + r3 * (front ? 120 : 50);
            double cycle = poolY + len;
            double y = (t * speed + r1 * cycle * 7.0) % cycle - len;
            int x = left + (int) (hash(i, 15) * (right - left));
            int a = (int) ((front ? 0x80 : 0x38) + r2 * (front ? 0x60 : 0x28));
            int col = front ? (r3 > 0.8 ? foam : water) : accent;
            int wd = front && r1 > 0.8 ? 2 : 1;
            int y0 = (int) Math.round(y);
            int y1 = (int) Math.round(Math.min(y + len, poolY));
            if (y1 > y0) {
                r.gradient(x, y0, x + wd, y1, MenuStyle.alpha(col, 0), MenuStyle.alpha(col, a));
            }
        }

        // Rock walls: ragged columns either side (static shape, from the hash).
        int step = Math.max(4, h / 48);
        for (int y = 0, k = 0; y < h; y += step, k++) {
            int lw = left + (int) ((hash(k, 21) - 0.5) * 14) + (int) (Math.sin(k * 0.45) * 6);
            int rw = right + (int) ((hash(k, 22) - 0.5) * 14) + (int) (Math.cos(k * 0.38) * 6);
            r.fill(0, y, Math.max(0, lw), y + step, rock);
            r.fill(Math.max(0, lw) - 1, y, Math.max(0, lw) + 1, y + step, rockEdge);
            r.fill(Math.min(w, rw), y, w, y + step, rock);
            r.fill(Math.min(w, rw) - 1, y, Math.min(w, rw) + 1, y + step, rockEdge);
        }
        // Wet sheen running down the rock faces beside the water.
        r.gradient(left - 10, 0, left, h, MenuStyle.alpha(accent, 0), MenuStyle.alpha(accent, 0x18));
        r.gradient(right, 0, right + 10, h, MenuStyle.alpha(accent, 0), MenuStyle.alpha(accent, 0x18));

        // The pool.
        r.gradient(0, poolY, w, h, MenuStyle.alpha(MenuStyle.mix(panel, accent, 0.35f), 0xE0),
                MenuStyle.alpha(MenuStyle.mix(panel, 0xFF000000, 0.6f), 0xF0));
        r.fill(0, poolY, w, poolY + 1, MenuStyle.alpha(foam, 0x50));
        for (int k = 0; k < 4; k++) {
            int ry = poolY + 3 + k * Math.max(3, (h - poolY) / 5);
            double drift = (t * (14 + k * 6)) % 60;
            for (int s = -1; s < w / 60 + 2; s++) {
                int x0 = (int) (s * 60 + drift + hash(k * 31 + s, 23) * 20);
                int len = 14 + (int) (hash(k * 17 + s, 24) * 24);
                int a = (int) (0x40 * (0.6 + 0.4 * Math.sin(t * 1.3 + s + k)));
                r.fill(x0, ry, x0 + len, ry + 1, MenuStyle.alpha(foam, Math.max(0, a - k * 8)));
            }
        }

        // White water where it lands, and mist rising off it.
        glow(r, w / 2, poolY, (right - left) * 0.55, Math.max(14, h * 0.07), foam, 0x40, 4);
        int m = count(9000, w, h, density, 16, 46);
        for (int i = 0; i < m; i++) {
            double r1 = hash(i, 31);
            double r2 = hash(i, 32);
            double life = (t * (0.18 + r2 * 0.22) + r1) % 1.0;
            double x = left + hash(i, 33) * (right - left) + Math.sin(t * 0.7 + i) * 10 * life;
            double y = poolY - life * (30 + r2 * 50);
            int a = (int) (0x50 * Math.sin(Math.PI * life));
            int size = 2 + (int) (r2 * 3);
            if (a > 3) {
                r.fill((int) x, (int) y, (int) x + size, (int) y + size, MenuStyle.alpha(foam, a));
            }
        }
    }

    // ---------------------------------------------------------------- Starfield (Dark)

    private static void starfield(GuiRects r, int w, int h, double t, int accent, int text, float density) {
        glow(r, (int) (w * 0.28 + Math.sin(t * 0.05) * 30), (int) (h * 0.35), w * 0.35, h * 0.28, accent, 0x1C, 9);
        glow(r, (int) (w * 0.74 - Math.sin(t * 0.04) * 30), (int) (h * 0.62), w * 0.32, h * 0.24,
                MenuStyle.mix(accent, text, 0.5f), 0x14, 9);
        int star = MenuStyle.mix(text, 0xFFFFFFFF, 0.4f);
        int n = count(450, w, h, density, 60, 380);
        for (int i = 0; i < n; i++) {
            double r1 = hash(i, 41);
            double r2 = hash(i, 42);
            double r3 = hash(i, 43);
            int layer = r3 < 0.6 ? 0 : r3 < 0.9 ? 1 : 2;
            double speed = layer == 0 ? 1.5 : layer == 1 ? 4.0 : 9.0;
            double x = ((r1 * w - t * speed) % w + w) % w;
            double y = r2 * h;
            double tw = 0.55 + 0.45 * Math.sin(t * (0.8 + r3 * 2.4) + i * 2.1);
            int a = (int) ((layer == 0 ? 0x50 : layer == 1 ? 0x90 : 0xE0) * tw);
            int px = (int) x;
            int py = (int) y;
            int col = r1 > 0.92 ? MenuStyle.mix(accent, 0xFFFFFFFF, 0.5f) : star;
            if (layer == 2) {
                r.fill(px - 1, py, px + 2, py + 1, MenuStyle.alpha(col, a / 3));
                r.fill(px, py - 1, px + 1, py + 2, MenuStyle.alpha(col, a / 3));
            }
            r.fill(px, py, px + 1, py + 1, MenuStyle.alpha(col, a));
        }
        // A shooting star every eight seconds or so, somewhere new each time.
        double period = 8.0;
        int shot = (int) Math.floor(t / period);
        double p = (t - shot * period) / 1.1;
        if (p < 1.0 && density > 0f) {
            double sx = w * (0.15 + 0.7 * hash(shot, 44));
            double sy = h * (0.05 + 0.3 * hash(shot, 45));
            double dist = 140 + 80 * hash(shot, 46);
            double hx = sx + dist * p;
            double hy = sy + dist * 0.45 * p;
            for (int k = 0; k < 18; k++) {
                double f = k / 18.0;
                int a = (int) (0xE0 * (1 - f) * Math.sin(Math.PI * Math.min(1, p * 1.2)));
                int x = (int) (hx - f * 40);
                int y = (int) (hy - f * 18);
                r.fill(x, y, x + 2, y + 1, MenuStyle.alpha(star, a));
            }
        }
    }

    // ---------------------------------------------------------------- Aurora (Custom)

    private static void aurora(GuiRects r, int w, int h, double t, int accent, int second, float density) {
        int star = 0xFFE8F0FF;
        int n = count(1400, w, h, density, 20, 120);
        for (int i = 0; i < n; i++) {
            int x = (int) (hash(i, 51) * w);
            int y = (int) (hash(i, 52) * h * 0.7);
            int a = (int) (0x70 * (0.5 + 0.5 * Math.sin(t * (0.7 + hash(i, 53)) + i)));
            r.fill(x, y, x + 1, y + 1, MenuStyle.alpha(star, a));
        }
        int step = 3;
        int[] colours = {accent, second, MenuStyle.mix(accent, second, 0.5f)};
        for (int band = 0; band < 3; band++) {
            int col = colours[band];
            double phase = band * 2.1;
            double baseY = h * (0.26 + band * 0.07);
            for (int x = 0; x < w; x += step) {
                double cy = baseY + Math.sin(x * 0.011 + t * 0.33 + phase) * h * 0.08
                        + Math.sin(x * 0.029 - t * 0.57 + phase) * h * 0.03;
                double height = h * 0.20 * (0.55 + 0.45 * Math.sin(x * 0.017 + t * 0.45 + phase * 1.7));
                double flicker = 0.75 + 0.25 * Math.sin(x * 0.06 + t * 1.6 + phase);
                int a = (int) ((band == 2 ? 0x48 : 0x70) * flicker);
                int top = (int) (cy - height);
                int mid = (int) cy;
                int bottom = (int) (cy + height * 0.18);
                r.gradient(x, top, x + step, mid, MenuStyle.alpha(col, 0), MenuStyle.alpha(col, a));
                r.gradient(x, mid, x + step, bottom, MenuStyle.alpha(col, a), MenuStyle.alpha(col, 0));
            }
        }
        glow(r, w / 2, h + 20, w * 0.7, h * 0.3, accent, 0x1A, 4);
    }

    // ---------------------------------------------------------------- Soft Light (Light)

    private static void softLight(GuiRects r, int w, int h, double t, int accent, int panel, boolean light, float density) {
        // Slanted rays from the top left.
        int ray = light ? 0xFFFFFFFF : MenuStyle.mix(accent, 0xFFFFFFFF, 0.5f);
        for (int k = 0; k < 4; k++) {
            int a = (int) ((light ? 0x22 : 0x12) * (0.6 + 0.4 * Math.sin(t * 0.25 + k * 1.4)));
            int x0 = (int) (w * (0.05 + k * 0.17));
            int width = 16 + k * 6;
            int rows = Math.max(1, h / 6);
            for (int i = 0; i < rows; i++) {
                int y = i * 6;
                int x = x0 + (int) (y * 0.55);
                double fade = 1.0 - (double) i / rows;
                r.fill(x, y, x + width, y + 6, MenuStyle.alpha(ray, (int) (a * fade)));
            }
        }
        int n = count(9000, w, h, density, 12, 34);
        int[] cols = {accent, MenuStyle.mix(accent, 0xFFFFFFFF, 0.5f), light ? 0xFFFFFFFF : MenuStyle.mix(panel, 0xFFFFFFFF, 0.3f)};
        for (int i = 0; i < n; i++) {
            double r1 = hash(i, 61);
            double r2 = hash(i, 62);
            double radius = 6 + r2 * 20;
            double travel = h + radius * 4;
            double y = h + radius - ((t * (3 + r1 * 6) + r1 * travel * 5) % travel);
            double x = hash(i, 63) * w + Math.sin(t * 0.2 + i) * 18;
            int a = (int) ((light ? 0x26 : 0x18) * (0.6 + 0.4 * Math.sin(t * 0.5 + i * 0.7)));
            int col = cols[i % cols.length];
            disc(r, x, y, radius, MenuStyle.alpha(col, a));
            disc(r, x - radius * 0.25, y - radius * 0.25, radius * 0.45, MenuStyle.alpha(0xFFFFFFFF, a / 2));
        }
        glow(r, w / 2, 55, Math.min(w * 0.55, 300), Math.min(h * 0.4, 110), accent, light ? 0x18 : 0x24, 9);
    }
}

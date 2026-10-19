package com.killer560.hub.livemap;

import com.killer560.hub.gui.MenuConfig;
import com.killer560.hub.gui.MenuTheme;

/**
 * The Dungeon Map's colour themes (killer560, 2026-10-15: "For map theme have the default as real map like it is, have
 * options for each of our mod themes as well as a match theme option, then a custom. Make it so you can only adjust room
 * colors on custom").
 * <p>
 * A palette is ten colours in {@link #SLOT_NAMES} order. Real Map is Hypixel's own map colours; Amber is the hand-tuned
 * amber set from 2026-09-20; Dark, Light and Ocean are worked out from the menu preset of the same name by
 * {@link #derive}; Match Menu Theme follows whatever the mod menu is drawn in right now (Amber's hand-tuned set when the
 * menu is Amber, otherwise {@link #derive} of the menu's five colours); Custom is one of his saved schemes.
 * <p>
 * Every derived room keeps the hue that tells its type apart (entrance green, puzzle purple, trap orange, miniboss
 * yellow, fairy pink, blood red), pulled a little toward the theme's accent, so a themed map still reads as a dungeon
 * map: only Normal, Rare, Unexplored and the wither door take the theme's own panel, card and text colours.
 */
public final class MapThemes {

    public static final int NORMAL = 0;
    public static final int ENTRANCE = 1;
    public static final int PUZZLE = 2;
    public static final int TRAP = 3;
    public static final int MINIBOSS = 4;
    public static final int FAIRY = 5;
    public static final int BLOOD = 6;
    public static final int RARE = 7;
    public static final int UNOPENED = 8;
    public static final int WITHER_DOOR = 9;
    public static final int COUNT = 10;

    public static final String[] SLOT_NAMES = {"Normal", "Entrance", "Puzzle", "Trap", "Miniboss", "Fairy", "Blood",
            "Rare", "Unexplored", "Wither Door"};

    /**
     * The default map colours (theme "Real Map", saved as {@code REAL}). killer560, 2026-10-19: "I love how their map
     * looks and ours should essentially match that style" - these are NoammAddons' {@code MapConfig.kt} defaults
     * (lines 59-71: normal 121,70,0; entrance 0,255,0; puzzle 123,0,123; trap 255,130,0; miniboss 255,200,0; fairy
     * 227,155,226; rare 178,178,178; unopened 65,65,65; wither door 16,16,16). Blood stays our #FF0000, which he asked for
     * on 2026-10-19 (NoammAddons' is 178,0,0). Until then this was {@link #HYPIXEL_ITEM}.
     */
    static final int[] REAL = {0xFF794600, 0xFF00FF00, 0xFF7B007B, 0xFFFF8200, 0xFFFFC800, 0xFFE39BE2, 0xFFFF0000,
            0xFFB2B2B2, 0xFF414141, 0xFF101010};

    /** The map item's own colours decoded from Hypixel's MapColor bytes: {@link #REAL} before 2026-10-19. Kept for the
     *  pre-2026-10-15 config migration, which tells a stock palette from a hand-picked one by comparing against it. */
    static final int[] HYPIXEL_ITEM = {0xFF724318, 0xFF00FF00, 0xFFB24CD8, 0xFFD87F33, 0xFFE5E533, 0xFFF27FA5, 0xFFFF0000,
            0xFFB2B2B2, 0xFF414141, 0xFF101010};

    /** killer560, 2026-09-20: "a custom one matching the mod's amber look". Blood stays reddish and trap warning-red so
     *  danger still reads as danger; everything else is a shade of amber, brown or gold. */
    static final int[] AMBER = {0xFF6B3A11, 0xFFFFAA00, 0xFFCC6600, 0xFFFF5555, 0xFFFFDD88, 0xFFF2C9A0, 0xFF8B0000,
            0xFFB2872B, 0xFF3D332B, 0xFF1A1A1A};

    /** The Map Theme choices, in the order the setting steps through them. */
    public enum Theme {
        REAL("Real Map"),
        AMBER("Amber"),
        DARK("Dark"),
        LIGHT("Light"),
        OCEAN("Ocean"),
        GREEN(com.killer560.hub.gui.GreenTheme.NAME),
        MATCH("Match Menu Theme"),
        CUSTOM("Custom");

        public final String label;

        Theme(String label) {
            this.label = label;
        }

        /** A saved name; anything unknown is Real Map. */
        public static Theme parse(String s) {
            if (s != null) {
                for (Theme t : values()) {
                    if (t.name().equalsIgnoreCase(s.trim())) {
                        return t;
                    }
                }
            }
            return REAL;
        }
    }

    private MapThemes() {
    }

    /** A copy of Hypixel's own colours. */
    public static int[] real() {
        return REAL.clone();
    }

    private static int[] matchCache;
    private static int matchVersion = Integer.MIN_VALUE;

    /** The palette a non-Custom theme paints with. Callers must not change the array. */
    static int[] preset(Theme theme) {
        return switch (theme) {
            case AMBER -> AMBER;
            case DARK -> DARK_CACHE;
            case LIGHT -> LIGHT_CACHE;
            case OCEAN -> OCEAN_CACHE;
            case GREEN -> GREEN_CACHE;
            case MATCH -> match();
            default -> REAL;
        };
    }

    private static final int[] DARK_CACHE = derive(MenuTheme.DARK.base());
    private static final int[] LIGHT_CACHE = derive(MenuTheme.LIGHT.base());
    private static final int[] OCEAN_CACHE = derive(MenuTheme.OCEAN.base());
    private static final int[] GREEN_CACHE = derive(MenuTheme.GREEN.base());

    /** The menu's own palette right now, re-derived only when the menu's theme or colours change. */
    private static int[] match() {
        int v = MenuConfig.version();
        if (matchCache == null || v != matchVersion) {
            MenuConfig menu = MenuConfig.getInstance();
            matchCache = menu.getTheme() == MenuTheme.AMBER ? AMBER : derive(menu.activeColours());
            matchVersion = v;
        }
        return matchCache;
    }

    /**
     * A map palette from a menu theme's five colours ({@link MenuTheme.Slot} order: accent, panel, card, text,
     * switch-on).
     */
    public static int[] derive(int[] base) {
        int accent = base[0];
        int panel = base[1];
        int card = base[2];
        int text = base[3];
        int[] out = new int[COUNT];
        out[NORMAL] = mix(card, accent, 0.32f);
        out[ENTRANCE] = mix(0xFF2ECC40, accent, 0.18f);
        out[PUZZLE] = mix(0xFFB24CD8, accent, 0.18f);
        out[TRAP] = mix(0xFFD87F33, accent, 0.18f);
        out[MINIBOSS] = mix(0xFFE5E533, accent, 0.18f);
        out[FAIRY] = mix(0xFFF27FA5, accent, 0.18f);
        out[BLOOD] = mix(0xFFE02020, accent, 0.12f);
        out[RARE] = mix(text, card, 0.45f);
        out[UNOPENED] = mix(panel, text, 0.22f);
        // A wither door must be dark on any theme: it is the one thing on the map that says "locked".
        out[WITHER_DOOR] = mix(darker(panel, card), 0xFF000000, 0.55f);
        for (int i = 0; i < COUNT; i++) {
            out[i] |= 0xFF000000;
        }
        return out;
    }

    private static int darker(int a, int b) {
        return lum(a) <= lum(b) ? a : b;
    }

    private static float lum(int c) {
        return (0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * (c & 0xFF)) / 255f;
    }

    static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** One saved Custom scheme: a name and its ten colours. */
    public static final class Scheme {
        public String name;
        public final int[] colors;

        public Scheme(String name, int[] colors) {
            this.name = name;
            this.colors = new int[COUNT];
            int[] src = colors == null ? REAL : colors;
            for (int i = 0; i < COUNT; i++) {
                this.colors[i] = i < src.length ? src[i] : REAL[i];
            }
        }
    }

    public static final int MAX_SCHEME_NAME = 24;

    static String cleanName(String s) {
        String n = s == null ? "" : s.trim();
        return n.length() > MAX_SCHEME_NAME ? n.substring(0, MAX_SCHEME_NAME) : n;
    }
}

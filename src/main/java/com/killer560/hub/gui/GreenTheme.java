package com.killer560.hub.gui;

import com.killer560.hub.hud.GuiRects;

/**
 * The Green menu theme's name, five base colours and panel background (killer560, 2026-10-15: "make a default viridian
 * green style theme and call it green for now, we will rename it in a bit"). The shown name is {@link #NAME} and nothing
 * else: renaming the theme is one edit here. Its saved id is the enum constant {@code MenuTheme.GREEN}, so a rename never
 * breaks a saved setting.
 *
 * <p>Second pass, from the theme's designer (2026-10-15: "The color is more teal than green, I think making it more green
 * would be good. Also can you do gradients for the background or no?"): every colour moved from hue ~160 (viridian, a
 * teal) to ~130 (forest green), and the menu's panel draws one of the {@link Gradient}s below instead of the plain
 * two-stop bevel. Only this theme draws one: {@link MenuStyle#derive} asks {@link #background} and gets null for any other
 * set of colours, and {@link MenuStyle#panel} then draws exactly the bevel it always did.
 */
public final class GreenTheme {

    /** What the theme is called in every list and label. */
    public static final String NAME = "Green";

    /** Hue ~135: a leaf green that reads on the panel (contrast 8.6 there). */
    public static final int ACCENT = 0xFF62C27A;
    /** Hue ~131: near-black forest green. */
    public static final int PANEL = 0xFF0F1A11;
    /** Hue ~128: one step up from the panel. */
    public static final int CARD = 0xFF172619;
    /** Hue ~129: an off white with a green tint. */
    public static final int TEXT = 0xFFE4F2E6;
    /** Hue ~134: forest green, the switch when on. */
    public static final int SWITCH_ON = 0xFF3F8A50;

    /** The panel backgrounds the designer is choosing between (screenshots in killer560s-mod-logs/theme-green-v2). */
    public enum Gradient {
        /** A: deep forest green at the top fading to near black at the bottom. */
        FOREST_NIGHT,
        /** B: dark at the top, through the panel green, to a lighter moss at the foot. */
        MOSS_FLOOR,
        /** C: forest night with a soft canopy glow spreading down from the top centre. */
        CANOPY_GLOW
    }

    /** The one shown. Not a setting (yet): the designer picks one and it becomes the only one or a choice. */
    private static Gradient gradient = Gradient.FOREST_NIGHT;

    public static Gradient gradient() {
        return gradient;
    }

    /** Switches the background (the testkit's screenshots of each variant); the next {@link MenuStyle#use} re-derives. */
    public static void setGradient(Gradient g) {
        if (g != null && g != gradient) {
            gradient = g;
            MenuStyle.invalidate();
        }
    }

    /** The panel background for these five colours: Green's when they are exactly Green's, otherwise null (every other
     *  theme, and Custom, keeps the plain bevel). */
    static Background background(int accent, int panel, int card, int text, int switchOn) {
        if ((accent | 0xFF000000) != ACCENT || (panel | 0xFF000000) != PANEL || (card | 0xFF000000) != CARD
                || (text | 0xFF000000) != TEXT || (switchOn | 0xFF000000) != SWITCH_ON) {
            return null;
        }
        return switch (gradient) {
            case FOREST_NIGHT -> FOREST_NIGHT_BG;
            case MOSS_FLOOR -> MOSS_FLOOR_BG;
            case CANOPY_GLOW -> CANOPY_GLOW_BG;
        };
    }

    // Built once: the stops and glow rings are constants, so drawing a panel allocates nothing beyond the fills themselves.
    // A and C share their stops on purpose, so C's screenshot differs from A's by the glow alone.
    private static final int[] NIGHT = {0xFF17301C, 0xFF0F1D12, 0xFF070D08};
    private static final Background FOREST_NIGHT_BG = new Background(NIGHT, 0, 0);
    private static final Background MOSS_FLOOR_BG = new Background(new int[]{0xFF0A140C, 0xFF112015, 0xFF1D3622}, 0, 0);
    private static final Background CANOPY_GLOW_BG = new Background(NIGHT, ACCENT, 0x08);

    /**
     * A theme background: vertical stops spaced evenly from top to bottom for the outer panel ({@link #panel}), the
     * sidebar and drawer ({@link #side}, a little deeper) and the well the cards sit in ({@link #well}, deeper still), and
     * optionally a glow of one colour spreading down from the top centre of the well, drawn as nested translucent boxes
     * that stack brighter toward the middle (a radial falloff out of plain fills).
     */
    static final class Background {
        private static final int RINGS = 8;
        final int[] panel;
        final int[] side;
        final int[] well;
        final int[] ringColours;

        Background(int[] stops, int glowColour, int glowAlpha) {
            this.panel = stops;
            this.side = new int[stops.length];
            this.well = new int[stops.length];
            for (int i = 0; i < stops.length; i++) {
                side[i] = MenuStyle.mix(stops[i], 0xFF000000, 0.15f);
                well[i] = MenuStyle.mix(stops[i], 0xFF000000, 0.5f);
            }
            this.ringColours = new int[glowAlpha > 0 ? RINGS : 0];
            for (int i = 0; i < ringColours.length; i++) {
                ringColours[i] = MenuStyle.alpha(glowColour, glowAlpha);
            }
        }

        /** Fills x0..x1, y0..y1 with {@code stops}, and the glow over it when {@code glow}. */
        void fill(GuiRects r, int x0, int y0, int x1, int y1, int[] stops, boolean glow) {
            int h = y1 - y0;
            int n = stops.length - 1;
            for (int i = 0; i < n; i++) {
                int a = y0 + h * i / n;
                int b = y0 + h * (i + 1) / n;
                r.gradient(x0, a, x1, b, stops[i], stops[i + 1]);
            }
            if (!glow) {
                return;
            }
            int w = x1 - x0;
            int cx = x0 + w / 2;
            for (int i = 0; i < ringColours.length; i++) {
                // ring i: widest first, each fading to clear at its foot; the smaller ones stack on top toward the centre
                float f = 1f - i / (float) RINGS;
                int hw = Math.round(w * 0.5f * f);
                int hh = Math.round(h * 0.6f * f);
                r.gradient(cx - hw, y0, cx + hw, y0 + hh, ringColours[i], ringColours[i] & 0x00FFFFFF);
            }
        }
    }

    private GreenTheme() {
    }
}

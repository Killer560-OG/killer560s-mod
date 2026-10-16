package com.killer560.hub.mainmenu;

/**
 * Dark's Milky Way after killer560's inspiration picture (2026-10-16): a Minecraft-styled night - a blocky galaxy band
 * running from the top left down to a bright core low on the right, dark dust lanes, crisp cross-shaped stars, a small
 * blocky caped character sitting on a stepped grass-block hill at the lower left looking up, blocky pines and dark rolling
 * hills - graded almost black and white ("I still want it to be a black theme though, not so much colour"), a faint warm
 * hint left at the core.
 * <p>
 * The base is the picture itself ({@code textures/gui/milkyway.png}, 1920x1080, built from his image by
 * {@code killer560s-mod-logs/scenes-dark-aurora/ref/make_textures.py}); without the character a small patch
 * ({@code milkyway_nochar_patch.png}) fills her place from the sky and hills beside her. On top, in the picture's own
 * 1920x1080 space: the bright stars twinkling as crisp crosses, a few faint ones flickering, a blocky shooting star every
 * few seconds (never across the title), the core's glow breathing, faint haze drifting along the hills and, with the
 * character, her cape fluttering (that region redrawn as small tiles of the picture offset by a travelling sine). Built on
 * {@link PictureScene}.
 */
final class MilkyWayTexScene {

    private MilkyWayTexScene() {
    }

    private static final int TW = 1920;
    private static final int TH = 1080;

    /** The brightest stars of the picture (x, y in its 1920x1080 space), found by make_textures.py. */
    private static final int[] STARS = {743, 87, 49, 511, 633, 579, 840, 199, 223, 689, 1356, 37, 1842, 176, 1347, 417, 1701,
            374, 96, 304, 1552, 221, 165, 416, 1207, 625, 348, 622, 1605, 500, 1069, 584, 1328, 654, 1820, 511, 1267, 559, 1170,
            574, 579, 776, 968, 513};
    /** The title band, where no faint star flickers. */
    private static final int[] CALM = {634, 0, 1286, 194};

    private static PictureScene build(boolean hero) {
        PictureScene s = PictureScene.of("textures/gui/milkyway.png", TW, TH);
        if (hero) {
            // her cape: attached along its right (her shoulders and back), free to the left and at its hem
            s.ripple(PictureScene.ripple(448, 806, 552, 892).tile(3)
                    .attached(PictureScene.Ripple.Anchor.RIGHT, 6, 70).ramp(0.35, 806, 60).fade(8, 10, 6, 1)
                    .waveX(3.2, 2.1, 0.06, 0.03).waveY(0.4 * 3.2, 1.6, 0.05));
        } else {
            // her place filled from the sky and hills beside her
            s.patch("textures/gui/milkyway_nochar_patch.png", 439, 751, 183, 143);
        }
        // the core breathing
        s.overlay(new PictureScene.Glow(1660, 790, 300, 170, 0xFFF4ECE0, 0.5, 0.5, 0.55, 9, 11, 0, 0, 0, 0, true));
        s.overlay(new PictureScene.Glow(1660, 800, 120, 70, 0xFFFFF6EA, 0.5, 0.5, 0.55, 8, 10, 0, 0, 0, 0, true));
        // haze drifting along the hills
        s.overlay(new PictureScene.Fog(4, TW, 880, 26, 380, 26, 9, 5, 0xFF9EA4AE, 11, 0, true, 1201));
        s.overlay(new PictureScene.Twinkles(STARS, 70, (int) (TH * 0.66), CALM, 0xFFFFFFFF, 1211));
        s.overlay(new PictureScene.Meteor(7.0, TW, TH, 1.6, 0xFFFFFFFF, 1231));
        return s;
    }

    /** With the character, and without her. */
    static final PictureScene HERO = build(true);
    static final PictureScene PLAIN = build(false);

    static PictureScene scene(boolean hero) {
        return hero ? HERO : PLAIN;
    }
}

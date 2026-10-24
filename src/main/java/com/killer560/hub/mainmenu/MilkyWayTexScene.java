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
 * character, her cape fluttering (that region redrawn as small tiles of the picture offset by a traveling sine). Built on
 * {@link PictureScene}.
 */
final class MilkyWayTexScene {

    private MilkyWayTexScene() {
    }

    /** The picture's space is its texture's: 2560x1440 (killer560, 2026-10-16: "You can make it high res"), sampled
     *  linearly (milkyway.png.mcmeta). Every size below was laid out at 1920x1080 and is scaled by {@link #K}. */
    private static final int TW = 2560;
    private static final int TH = 1440;
    private static final double K = TW / 1920.0;

    /** The brightest stars of the picture (x, y in its pixels), found by make_textures.py. */
    private static final int[] STARS = {
            990, 115, 65, 682, 844, 772, 1120, 265, 1807, 44, 298, 918, 1796, 556, 2465, 235, 2269, 498, 128, 406,
            1545, 1036, 220, 555, 2141, 666, 2069, 294, 470, 830, 2421, 682, 1426, 779};
    /** Every other star of the picture (x, y, brightness), lifted out of the texture so all of them fade; none on the
     *  character or the land (make_textures.py). */
    private static final int[] POINTS = PictureScene.loadPoints("/assets/killer560smod/textures/gui/milkyway_stars.txt");
    /** The title band, where no faint star flickers. */
    private static final int[] CALM = {(int) (634 * K), 0, (int) (1286 * K), (int) (194 * K)};

    private static PictureScene build(boolean hero) {
        PictureScene s = PictureScene.of("textures/gui/milkyway.png", TW, TH);
        if (hero) {
            // her cape: attached along its right (her shoulders and back), free to the left and at its hem
            s.ripple(PictureScene.ripple(px(448), px(806), px(552), px(892)).tile(4)
                    .attached(PictureScene.Ripple.Anchor.RIGHT, 6 * K, 70 * K).ramp(0.35, 806 * K, 60 * K)
                    .fade(8 * K, 10 * K, 6 * K, 1 * K)
                    .waveX(3.2 * K, 2.1, 0.06 / K, 0.03 / K).waveY(0.4 * 3.2 * K, 1.6, 0.05 / K));
        } else {
            // her place filled from the sky and hills beside her (make_textures.py prints the patch's origin and size)
            s.patch("textures/gui/milkyway_nochar_patch.png", 585, 1001, 245, 192);
        }
        // the core breathing
        s.overlay(new PictureScene.Glow(px(1660), px(790), 300 * K, 170 * K, 0xFFF4ECE0, 0.5, 0.5, 0.55, 9, 11, 0, 0, 0, 0,
                true));
        s.overlay(new PictureScene.Glow(px(1660), px(800), 120 * K, 70 * K, 0xFFFFF6EA, 0.5, 0.5, 0.55, 8, 10, 0, 0, 0, 0,
                true));
        // haze drifting along the hills
        s.overlay(new PictureScene.Fog(4, TW, px(880), px(26), 380 * K, 26 * K, 9 * K, 5 * K, 0xFF9EA4AE, 11, 0, true, 1201));
        s.overlay(new PictureScene.Twinkles(STARS, POINTS, 50, (int) (TH * 0.66), CALM, 0xFFFFFFFF, 1211, TW, K));
        s.overlay(new PictureScene.Meteor(7.0, TW, TH, 1.6 * K, 0xFFFFFFFF, 1231, K));
        return s;
    }

    private static int px(double v1920) {
        return (int) Math.round(v1920 * K);
    }

    /** With the character, and without her. */
    static final PictureScene HERO = build(true);
    static final PictureScene PLAIN = build(false);

    static PictureScene scene(boolean hero) {
        return hero ? HERO : PLAIN;
    }
}

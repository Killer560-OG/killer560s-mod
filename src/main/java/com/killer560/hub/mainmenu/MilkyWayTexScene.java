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
    private static final int[] STARS = {
            743, 87, 49, 511, 633, 579, 840, 199, 223, 689, 1356, 37, 1842, 176, 1347, 417, 1701, 374, 96, 304, 1552,
            221, 165, 416, 1207, 625, 348, 622, 1605, 500, 1069, 584, 1328, 654, 1820, 511, 1267, 559, 1170, 574, 579,
            776, 968, 513};
    /** Medium stars of the picture that shimmer live (make_textures.py's stars_mid.txt). */
    private static final int[] MIDS = {
            1238, 557, 1416, 717, 1351, 606, 1539, 704, 1617, 646, 1329, 630, 1603, 657, 1573, 731, 1702, 638, 1216,
            648, 1818, 740, 1471, 686, 1358, 652, 1763, 671, 1440, 714, 1412, 752, 964, 759, 1882, 604, 1762, 650,
            1222, 551, 1423, 689, 1265, 592, 1362, 572, 1362, 668, 735, 640, 1172, 272, 1167, 545, 1310, 680, 1473,
            467, 1541, 667, 1336, 677, 840, 713, 1219, 586, 1883, 798, 1058, 492, 1176, 530, 1308, 571, 1177, 648,
            1163, 649, 1504, 657, 1160, 518, 1270, 618, 1447, 685, 1284, 558, 510, 76, 1153, 563, 1431, 636, 1697, 470,
            959, 537, 1469, 660, 910, 695, 814, 446, 959, 575, 1813, 629, 1379, 636, 1248, 612, 558, 769, 1427, 662,
            1799, 681, 817, 369, 1339, 448, 1227, 599, 1231, 630, 1547, 648, 1313, 640, 1796, 407, 1298, 621, 401, 292,
            808, 467, 1205, 490, 1115, 528, 494, 553, 1055, 571, 1351, 686, 1386, 793, 1556, 548, 929, 585, 905, 539,
            1532, 604, 1211, 682, 1580, 612, 1115, 629, 1357, 788, 1033, 509, 261, 160, 790, 361, 846, 368, 846, 390,
            817, 407, 1400, 629, 748, 427, 1017, 487, 1060, 509, 1857, 325, 1828, 686, 290, 174, 1025, 440, 1607, 584,
            1393, 604, 1911, 765, 1096, 531, 1124, 554, 694, 397, 747, 454, 1742, 527, 1004, 584, 1197, 610, 1060, 473,
            795, 518, 1885, 670, 1389, 572, 1759, 583, 1083, 725, 1239, 752, 70, 773, 998, 379, 1687, 379, 802, 394,
            1698, 428, 1075, 467, 925, 480, 1048, 790, 729, 335, 1672, 556, 1857, 176, 1388, 376, 911, 472, 1435, 546,
            1467, 625, 536, 771, 269, 534, 1069, 616, 557, 694, 1524, 67, 357, 264, 1310, 548, 1178, 601, 997, 598,
            1140, 682, 1431, 500, 1759, 526, 1743, 557, 210, 693, 489, 330, 993, 363, 408, 226, 1445, 319, 452, 172,
            1081, 439, 1312, 535, 797, 756, 788, 302, 1223, 474, 735, 481, 737, 497, 1374, 523, 1058, 697, 882, 789,
            1015, 208, 730, 284};
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
        s.overlay(new PictureScene.Twinkles(STARS, MIDS, 110, (int) (TH * 0.66), CALM, 0xFFFFFFFF, 1211));
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

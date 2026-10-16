package com.killer560.hub.mainmenu;

import com.killer560.hub.hud.GuiRects;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The Green theme's main menu as the theme's designer drew it (2026-10-16, relayed by killer560: "match THIS image almost
 * exactly ... make her hair flow a little bit and the cape flow and whatnot ... a rough draw-up of the real animation").
 * <p>
 * The base is the designer's own picture (shipped as {@code textures/gui/green_boss_target.png}, 1920x1080, with the title,
 * buttons and "Playing as" chip it had baked in filled from the surrounding fog, so the real menu draws on it cleanly). On
 * top, all in the picture's own 1920x1080 space:
 * <ul>
 *   <li>the cape and the hair flow: those regions are redrawn as small tiles of the picture whose source is offset by a
 *       travelling sine, growing from nothing where they attach (shoulders, crown) to the most at the free ends;</li>
 *   <li>streaks falling down the acid waterfall and foam flickering where it lands on the slime's crown;</li>
 *   <li>the slime's glow pulsing gently, shimmer drifting across the lake, fog drifting over the water, a few motes rising
 *       at the sides;</li>
 *   <li>glowing droplets dripping from the cave ceiling at three depths (killer560, 2026-10-16): each forms on the
 *       rock, stretches, falls with gravity trailing a short streak, and lands with a ripple on the lake or a splat on the
 *       ledge; only two faint far ones fall through the middle;</li>
 * </ul>
 * The view itself never moves (killer560, 2026-10-16: the side-to-side sway was removed); only those parts do. Built on
 * {@link PictureScene}, which was generalised from this class (2026-10-16) so a further painted picture needs only a spec.
 */
final class TargetScene {

    private TargetScene() {
    }

    private static final int TW = 1920;
    private static final int TH = 1080;

    private static final int LIME = 0xFFB4FF4A;
    private static final int HOT = 0xFFDCFFB8;

    /** How many droplet sources the ceiling has (testkit 1651 checks the scene has them). */
    static final int DROPS = 18; // 24 until killer560, 2026-10-16: "a hair too much water"

    static final PictureScene SCENE = PictureScene.of("textures/gui/green_boss_target.png", TW, TH)
            // The cape: attached along its right (the shoulders and the body), free to the left and at its hem.
            .ripple(PictureScene.ripple(60, 455, 252, 760).tile(5)
                    .attached(PictureScene.Ripple.Anchor.RIGHT, 5, 130).ramp(0.45, 470, 250).fade(14, 26, 16, 10)
                    .waveX(9.0, 1.7, 0.018, 0.012).waveY(0.45 * 9.0, 1.3, 0.025))
            // The hair at the side of the hood: attached at the top, free at the tips.
            .ripple(PictureScene.ripple(232, 440, 272, 528).tile(4)
                    .attached(PictureScene.Ripple.Anchor.TOP, 0, 528 - 440).fade(14, 26, 16, 10)
                    .waveX(3.0, 2.1, 0, 0.05))
            // the slime's glow, pulsing
            .overlay(new PictureScene.Glow(1551, 700, 360, 300, LIME, 0.5, 0.5, 1.2, 0x10, 0x18, 0, 0, 0, 4, false))
            .overlay(new PictureScene.Glow(1551, 690, 230, 190, HOT, 0.5, 0.5, 1.2, 0x08, 0x10, 0, 0, 0, 3, false))
            // the waterfall: streaks falling, foam where it lands on the crown
            .overlay(new PictureScene.Streaks(34, 1492, 72, 86, 548, 320, 260, 30, 70, HOT, 0xFF0A2A0A, 701))
            .overlay(new PictureScene.Glow(1528, 552, 70, 18, HOT, 0.6, 0.4, 6.1, 0x20, 0x40, 10, 0, 0, 3, false))
            .overlay(new PictureScene.Splash(14, 1528, 552, 70, 50, 14, 22, 1.1, 3, HOT, 707, true))
            // shimmer drifting across the lake
            .overlay(new PictureScene.Shimmer(40, 470, 900, 862, 200, 20, 70, 8, 14, HOT, 712))
            // fog drifting over the water and round the cliff
            .overlay(new PictureScene.Fog(4, TW, 820, 40, 420, 46, 14, 6, LIME, 0x1A, 3, false, 716))
            .overlay(new PictureScene.Glow(160, 900, 260, 120, LIME, 0, 0, 0, 0x14, 0, 0, 30, 0.2, 3, false))
            // droplets dripping from the ceiling: two faint far ones through the middle (behind the menu), the rest over
            // the ledge and the near lake (left) and either side of the waterfall (right); gravity 1130 = 2300 x 0.49, so
            // drops land at 70% of the old speed ("falling a bit too fast")
            .overlay(new PictureScene.Drips(DROPS, 2, 640, 640, 70, 480, 1360, 480, 1462, 1596, 150, 24, 70, 1130, 520, 842,
                    852, HOT, 730))
            // motes rising at the sides
            .overlay(new PictureScene.Motes(26, 40, 480, 1400, 460, 900, 820, 0.02, 0.03, 3, HOT, 717, true));

    static void drawImage(GuiGraphicsExtractor g, int w, int h, double t) {
        SCENE.drawImage(g, w, h, t);
    }

    static void drawOverlays(GuiRects r, int w, int h, double t) {
        SCENE.drawOverlays(r, w, h, t, 1f);
    }
}

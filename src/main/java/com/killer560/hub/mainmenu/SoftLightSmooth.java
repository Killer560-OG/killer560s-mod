package com.killer560.hub.mainmenu;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.hud.GuiRects;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;

/**
 * Light's Soft Light scene drawn smooth (killer560, 2026-10-16: "can you smooth out the light one"): the same rays, drifting
 * lights and title glow as {@link MainMenuScenes}' soft light, at the same places, colours and speeds, but at the screen's
 * real resolution instead of GUI-scale blocks:
 * <ul>
 *   <li>each slanted ray is one sheared gradient fading down the screen, built of nested layers so its sides fall off
 *       softly, instead of a staircase of 6-unit rows;</li>
 *   <li>each drifting light is an antialiased disc (edge pixels carry their partial coverage) placed at its sub-pixel
 *       position, with its soft highlight, instead of rounded spans that jump a whole GUI unit;</li>
 *   <li>the glow behind the title is a soft Gaussian ({@link Px#softGlow}) instead of nine stepped ellipses.</li>
 * </ul>
 * Shown with {@link MainMenuScenes#previewScenes()} only until he approves it. Allocates nothing per frame beyond the batch's
 * own parts (the shear is one constant matrix).
 */
final class SoftLightSmooth {

    private SoftLightSmooth() {
    }

    /** x' = x + 0.55 y: the rays' slant. */
    private static final Matrix3x2f SHEAR = new Matrix3x2f(1f, 0f, 0.55f, 1f, 0f, 0f);

    static void draw(GuiRects r, int w, int h, double t, int accent, int panel, boolean light, int n) {
        int s = Math.max(1, net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale());
        Matrix3x2fStack pose = r.graphics().pose();
        pose.pushMatrix();
        pose.scale(1f / s, 1f / s);
        double sw = w * (double) s;
        double sh = h * (double) s;

        // Slanted rays from the top left: x0 + 0.55 y, fading to nothing at the bottom.
        int ray = light ? 0xFFFFFFFF : MenuStyle.mix(accent, 0xFFFFFFFF, 0.5f);
        pose.pushMatrix();
        pose.mul(SHEAR);
        for (int k = 0; k < 4; k++) {
            double a = (light ? 0x22 : 0x12) * (0.6 + 0.4 * Math.sin(t * 0.25 + k * 1.4));
            double x0 = sw * (0.05 + k * 0.17);
            double width = (16 + k * 6) * (double) s;
            double mid = x0 + width / 2;
            // three nested layers (a core and two wider, fainter ones) so the sides fall off softly; few enough that each
            // still moves the 8-bit colour (six faint ones each rounded away on the light backdrop and the ray vanished)
            for (int l = 0; l < 3; l++) {
                double half = width * (l == 0 ? 0.40 : l == 1 ? 0.50 : 0.60);
                int la = (int) Math.round(a * (l == 0 ? 0.62 : l == 1 ? 0.26 : 0.14));
                if (la <= 0) {
                    continue;
                }
                r.gradient((int) Math.round(mid - half), 0, (int) Math.round(mid + half), (int) Math.ceil(sh),
                        MenuStyle.alpha(ray, la), MenuStyle.alpha(ray, 0));
            }
        }
        pose.popMatrix();

        // The drifting lights: the same paths as the blocky ones, in real pixels.
        int[] cols = COLS;
        cols[0] = accent;
        cols[1] = MenuStyle.mix(accent, 0xFFFFFFFF, 0.5f);
        cols[2] = light ? 0xFFFFFFFF : MenuStyle.mix(panel, 0xFFFFFFFF, 0.3f);
        for (int i = 0; i < n; i++) {
            double r1 = MainMenuScenes.hash(i, 61);
            double r2 = MainMenuScenes.hash(i, 62);
            double radius = 6 + r2 * 20;
            double travel = h + radius * 4;
            double y = h + radius - ((t * (3 + r1 * 6) + r1 * travel * 5) % travel);
            double x = MainMenuScenes.hash(i, 63) * w + Math.sin(t * 0.2 + i) * 18;
            double a = (light ? 0x26 : 0x18) * (0.6 + 0.4 * Math.sin(t * 0.5 + i * 0.7));
            int col = cols[i % cols.length];
            aaDisc(r, x * s, y * s, radius * s, col, a);
            aaDisc(r, (x - radius * 0.25) * s, (y - radius * 0.25) * s, radius * 0.45 * s, 0xFFFFFFFF, a / 2);
        }

        // The glow behind the title.
        Px.softGlow(GuiPx.of(r), sw / 2, 55.0 * s, Math.min(w * 0.55, 300) * s * 0.85, Math.min(h * 0.4, 110) * s * 0.85,
                accent, (light ? 0x18 : 0x24) / 255.0);
        pose.popMatrix();
    }

    private static final int[] COLS = new int[3];

    /**
     * An antialiased disc in the current pose's pixels: placed at its sub-pixel centre (the pose moved by the fraction),
     * one row per pixel, each row's two edge pixels carrying the partial coverage. {@code alpha} 0..255, unrounded.
     */
    static void aaDisc(GuiRects r, double cx, double cy, double radius, int rgb, double alpha) {
        if (radius < 0.5 || alpha < 0.5) {
            return;
        }
        Matrix3x2fStack pose = r.graphics().pose();
        double fx = Math.floor(cx);
        double fy = Math.floor(cy);
        pose.pushMatrix();
        pose.translate((float) (cx - fx), (float) (cy - fy));
        int ix = (int) fx;
        int iy = (int) fy;
        int rows = (int) Math.ceil(radius);
        int core = MenuStyle.alpha(rgb, (int) Math.round(alpha));
        for (int dy = -rows; dy < rows; dy++) {
            double m = dy + 0.5;
            double q = radius * radius - m * m;
            if (q <= 0) {
                // the top and bottom rows: a faint sliver where the circle only grazes the row
                continue;
            }
            double half = Math.sqrt(q);
            int in = (int) Math.floor(half);
            double frac = half - in;
            if (in > 0) {
                r.fill(ix - in, iy + dy, ix + in, iy + dy + 1, core);
            }
            int ea = (int) Math.round(alpha * frac);
            if (ea > 0) {
                int e = MenuStyle.alpha(rgb, ea);
                r.fill(ix - in - 1, iy + dy, ix - in, iy + dy + 1, e);
                r.fill(ix + in, iy + dy, ix + in + 1, iy + dy + 1, e);
            }
        }
        pose.popMatrix();
    }

    /**
     * The main menu's vignette without its steps: the same top and bottom gradients, and the sides as true horizontal
     * gradients (a vertical gradient turned a quarter) instead of ten strips of falling alpha.
     */
    static void vignette(GuiRects r, int w, int h, boolean light) {
        int edge = light ? 0x30000000 : 0x80000000;
        r.gradient(0, 0, w, Math.max(1, h / 5), edge, 0x00000000);
        r.gradient(0, h - Math.max(1, h / 4), w, h, 0x00000000, light ? 0x38000000 : 0xA0000000);
        int span = Math.max(1, w / 6);
        int maxA = light ? 0x28 : 0x70;
        Matrix3x2fStack pose = r.graphics().pose();
        // left: x from 0 (dark) to span (clear); drawn as a vertical gradient in a frame turned so its y runs along x
        pose.pushMatrix();
        pose.rotate((float) (-Math.PI / 2));          // (x, y) -> (y, -x)
        r.gradient(-h, 0, 0, span, MenuStyle.alpha(0xFF000000, maxA), 0x00000000);
        pose.popMatrix();
        pose.pushMatrix();
        pose.translate(w, 0);
        pose.rotate((float) (Math.PI / 2));           // (x, y) -> (-y, x): +y runs toward -x from the right edge
        r.gradient(0, 0, h, span, MenuStyle.alpha(0xFF000000, maxA), 0x00000000);
        pose.popMatrix();
    }
}

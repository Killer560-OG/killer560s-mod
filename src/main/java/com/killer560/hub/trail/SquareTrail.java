package com.killer560.hub.trail;

import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.world.phys.AABB;

/**
 * The Squares trail for one player (killer560's item 8.4: "one square per tick, 1-100, orange while grounded, blue
 * while airborne, nothing while standing still"): a fixed-capacity ring of the feet positions sampled each tick, drawn
 * as thin flat boxes. The local trail ({@link TrailFeature}) and each shared one ({@link RemoteTrails}) own one.
 * <p>
 * Parallel primitive arrays sized to {@link TrailConfig#MAX_LENGTH} instead of a List, so sampling and drawing never
 * allocate; the length setting only decides how much of the ring is drawn.
 */
final class SquareTrail {

    /** The airborne colour - a bright blue that reads clearly against the grounded green. */
    private static final int AIRBORNE_BLUE = 0x2299FF;
    /** Green while grounded (killer560, 2026-09-21: "make the on ground color green instead of orange"). */
    private static final int GROUNDED_GREEN = 0x55FF55;
    /** How thick (in blocks) each flat square is drawn - just enough to not z-fight with the ground. */
    private static final float SQUARE_THICKNESS = 0.04f;
    /** Squared distance (blocks) below which the player is standing still - filters idle jitter. */
    private static final double STILL_EPSILON_SQ = 0.02 * 0.02;

    private static final float[] GROUNDED_RGB = rgbToFloats(GROUNDED_GREEN);
    private static final float[] AIRBORNE_RGB = rgbToFloats(AIRBORNE_BLUE);

    private final double[] xs = new double[TrailConfig.MAX_LENGTH];
    private final double[] ys = new double[TrailConfig.MAX_LENGTH];
    private final double[] zs = new double[TrailConfig.MAX_LENGTH];
    private final boolean[] grounded = new boolean[TrailConfig.MAX_LENGTH];
    private int head = 0;
    private int size = 0;

    private double lastX;
    private double lastY;
    private double lastZ;
    private boolean hasLast = false;

    int size() {
        return size;
    }

    void clear() {
        head = 0;
        size = 0;
        hasLast = false;
    }

    /** Forgets the last position (so the next sample lays nothing), keeping the squares already laid. */
    void forgetLast() {
        hasLast = false;
    }

    /** One tick's feet position: lays a square when it moved since the last sample. */
    void sample(double x, double y, double z, boolean onGround) {
        if (hasLast) {
            double dx = x - lastX;
            double dy = y - lastY;
            double dz = z - lastZ;
            if (dx * dx + dy * dy + dz * dz >= STILL_EPSILON_SQ) {
                xs[head] = x;
                ys[head] = y;
                zs[head] = z;
                grounded[head] = onGround;
                head = (head + 1) % TrailConfig.MAX_LENGTH;
                if (size < TrailConfig.MAX_LENGTH) {
                    size++;
                }
            }
        }
        lastX = x;
        lastY = y;
        lastZ = z;
        hasLast = true;
    }

    /** Draws the newest {@code length} squares, {@code squareSize} blocks across, fading toward the tail when asked.
     *  Returns how many were drawn. */
    int render(LevelRenderContext context, int length, float squareSize, int opacityPercent, boolean fade) {
        int visible = Math.min(size, length);
        if (visible <= 0) {
            return 0;
        }
        float half = squareSize / 2f;
        float baseAlpha = opacityPercent / 100f;
        int drawn = 0;
        for (int i = 0; i < visible; i++) {
            int idx = Math.floorMod(head - 1 - i, TrailConfig.MAX_LENGTH);
            float alpha = baseAlpha;
            if (fade && visible > 1) {
                alpha *= 1f - ((float) i / (visible - 1));
            }
            if (alpha <= 0.01f) {
                continue;
            }
            float[] rgb = grounded[idx] ? GROUNDED_RGB : AIRBORNE_RGB;
            double x = xs[idx];
            double y = ys[idx];
            double z = zs[idx];
            AABB box = new AABB(x - half, y, z - half, x + half, y + SQUARE_THICKNESS, z + half);
            WorldRenderUtils.renderFilledBox(context, box, rgb[0], rgb[1], rgb[2], alpha);
            drawn++;
        }
        return drawn;
    }

    private static float[] rgbToFloats(int rgb) {
        return new float[]{((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f};
    }
}

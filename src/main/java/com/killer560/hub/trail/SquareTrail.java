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
    private int teleports = 0;
    /** Blocks between two squares laid across a teleport. */
    private static final double BRIDGE_SPACING = 0.6;

    private double lastX;
    private double lastY;
    private double lastZ;
    private boolean hasLast = false;

    int size() {
        return size;
    }

    /** Teleports this trail has sampled. */
    int teleports() {
        return teleports;
    }

    void clear() {
        head = 0;
        size = 0;
        teleports = 0;
        hasLast = false;
    }

    /** Forgets the last position (so the next sample lays nothing), keeping the squares already laid. */
    void forgetLast() {
        hasLast = false;
    }

    /** The newest square's position ({@code fromNewest} 0) or an older one's, as x y z; null past what is held. */
    double[] position(int fromNewest) {
        if (fromNewest < 0 || fromNewest >= size) {
            return null;
        }
        int idx = Math.floorMod(head - 1 - fromNewest, TrailConfig.MAX_LENGTH);
        return new double[]{xs[idx], ys[idx], zs[idx]};
    }

    private void lay(double x, double y, double z, boolean onGround) {
        xs[head] = x;
        ys[head] = y;
        zs[head] = z;
        grounded[head] = onGround;
        head = (head + 1) % TrailConfig.MAX_LENGTH;
        if (size < TrailConfig.MAX_LENGTH) {
            size++;
        }
    }

    /** One tick's feet position: lays a square when it moved since the last sample. A step past
     *  {@link TeleportMode#MIN_JUMP} is a teleport: Break lays only the landing square (a gap before it), Connect and Arc
     *  lay squares along the straight line or the arc from where he left to where he landed. */
    void sample(double x, double y, double z, boolean onGround, TeleportMode onTeleport) {
        if (hasLast) {
            double dx = x - lastX;
            double dy = y - lastY;
            double dz = z - lastZ;
            double dsq = dx * dx + dy * dy + dz * dz;
            if (TeleportMode.isTeleport(Math.sqrt(dsq))) {
                teleports++;
                if (onTeleport != TeleportMode.BREAK) {
                    double d = Math.sqrt(dsq);
                    int steps = TeleportMode.bridgeSteps(d, BRIDGE_SPACING);
                    for (int i = 1; i < steps; i++) {
                        double t = i / (double) steps;
                        lay(lastX + dx * t, lastY + dy * t + onTeleport.lift(t, d), lastZ + dz * t, false);
                    }
                }
                lay(x, y, z, onGround);
            } else if (dsq >= STILL_EPSILON_SQ) {
                lay(x, y, z, onGround);
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
        BATCH.clear();
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
            // The same box renderFilledBox drew per square, collected and drawn in one call (FPS pass, 2026-10-15: an
            // AABB, a lambda and a camera-space push per square per frame before, up to 100 squares).
            BATCH.add(x - half, y, z - half, x + half, y + SQUARE_THICKNESS, z + half, rgb[0], rgb[1], rgb[2], alpha);
            drawn++;
        }
        BATCH.drawFilled(context, net.minecraft.client.renderer.rendertype.RenderTypes.debugFilledBox());
        return drawn;
    }

    /** Render-thread scratch for {@link #render}. */
    private final com.killer560.hub.util.BoxBatch BATCH = new com.killer560.hub.util.BoxBatch();

    private static float[] rgbToFloats(int rgb) {
        return new float[]{((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f};
    }
}

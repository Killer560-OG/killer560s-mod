package com.killer560.hub.trail;

import com.killer560.hub.mainmenu.MainMenuTheme;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * killer560's item 8.4, verbatim: "Trail: one square per tick, 1-100, orange while grounded, blue while
 * airborne, nothing while standing still."
 * <p>
 * Ticks from {@link ClientTickEvents#END_CLIENT_TICK} (real ticks, not render frames) and stores each
 * point in a fixed-capacity ring buffer sized to {@link TrailConfig#MAX_LENGTH} - drawing runs every
 * frame, so allocating here would show up as frame stutter (see {@code wave/fps-report.md}'s per-frame
 * cost audit). {@link TrailConfig#getLength()} only controls how much of that buffer is currently drawn;
 * the buffer itself never resizes, so dragging the length slider is free.
 * <p>
 * Purely visual, client-side, no gameplay effect - legit in both builds. Renders through the same
 * {@code LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES} + {@link WorldRenderUtils} path as Etherwarp's
 * waypoint boxes ({@link com.killer560.hub.etherwarp.EtherwarpFeature#onWorldRender}), reusing
 * {@link WorldRenderUtils#renderFilledBox} rather than a second vertex-buffer approach: each trail
 * "square" is a thin, flat filled box sitting on the ground at the recorded feet position.
 */
public final class TrailFeature {

    /** The airborne colour - a bright blue chosen to read clearly against the mod's amber/orange
     *  (see {@link MainMenuTheme#ORANGE}, used here for the grounded colour). */
    private static final int AIRBORNE_BLUE = 0x2299FF;

    /** How thick (in blocks) each flat square is drawn - just enough to not z-fight with the ground. */
    private static final float SQUARE_THICKNESS = 0.04f;

    /** Squared epsilon distance (blocks) below which the player is considered "standing still" - filters
     *  out tiny idle jitter (looking around, sub-pixel physics settling) so it doesn't spam squares. */
    private static final double STILL_EPSILON_SQ = 0.02 * 0.02;

    /** Green while grounded (killer560, 2026-09-21: "make the on ground color green instead of orange"). */
    private static final float[] GROUNDED_RGB = rgbToFloats(0x55FF55);
    private static final float[] AIRBORNE_RGB = rgbToFloats(AIRBORNE_BLUE);

    // Fixed-capacity ring buffer, sized to the max possible length regardless of the current setting -
    // see the class doc for why. Parallel primitive arrays instead of a List<Point> so tracking a trail
    // never allocates on the hot tick/render path.
    private static final double[] xs = new double[TrailConfig.MAX_LENGTH];
    private static final double[] ys = new double[TrailConfig.MAX_LENGTH];
    private static final double[] zs = new double[TrailConfig.MAX_LENGTH];
    private static final boolean[] grounded = new boolean[TrailConfig.MAX_LENGTH];
    private static int head = 0;
    private static int size = 0;

    private static double lastX;
    private static double lastY;
    private static double lastZ;
    private static boolean hasLast = false;
    private static boolean wasEnabled = false;
    private static ClientLevel lastLevel = null;

    private TrailFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(TrailFeature::onWorldRender);
        // Disconnecting drops client.level to null anyway (tick() below would catch that on its own next
        // tick), but clearing right away avoids a one-tick window where a stale trail could show through
        // on whatever's drawn immediately after - same belt-and-suspenders reset ChunkCacheManager uses.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    }

    private static void tick() {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level != lastLevel) {
            // World or dimension change (including join/leave) - old squares belong to a place that no
            // longer exists, so don't let them draw stale in the new world.
            clear();
            lastLevel = level;
        }

        TrailConfig cfg = TrailConfig.getInstance();
        boolean enabledNow = cfg.isEnabled();
        if (wasEnabled && !enabledNow) {
            // Same "leaving true -> false" edge-triggered reset every other per-run state in this mod
            // uses - avoids a big stale jump reappearing if the trail is re-enabled later.
            clear();
        }
        wasEnabled = enabledNow;

        LocalPlayer player = client.player;
        if (!enabledNow || level == null || player == null) {
            hasLast = false;
            return;
        }

        Vec3 pos = player.position();
        if (hasLast) {
            double dx = pos.x - lastX;
            double dy = pos.y - lastY;
            double dz = pos.z - lastZ;
            if (dx * dx + dy * dy + dz * dz >= STILL_EPSILON_SQ) {
                push(pos.x, pos.y, pos.z, player.onGround());
            }
        }
        lastX = pos.x;
        lastY = pos.y;
        lastZ = pos.z;
        hasLast = true;
    }

    private static void push(double x, double y, double z, boolean onGround) {
        xs[head] = x;
        ys[head] = y;
        zs[head] = z;
        grounded[head] = onGround;
        head = (head + 1) % TrailConfig.MAX_LENGTH;
        if (size < TrailConfig.MAX_LENGTH) {
            size++;
        }
    }

    private static void onWorldRender(LevelRenderContext context) {
        TrailConfig cfg = TrailConfig.getInstance();
        if (!cfg.isEnabled() || size == 0) {
            return;
        }
        int visible = Math.min(size, cfg.getLength());
        if (visible <= 0) {
            return;
        }
        float half = cfg.getSquareSize() / 2f;
        float baseAlpha = cfg.getOpacityPercent() / 100f;
        boolean fade = cfg.isFadeOut();

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
        }
    }

    /** Drops every recorded point - called on world/dimension change, disconnect, and the trail being
     *  toggled off. */
    public static void clear() {
        head = 0;
        size = 0;
        hasLast = false;
    }

    private static float[] rgbToFloats(int rgb) {
        return new float[]{((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f};
    }
}

package com.killer560.hub.zoom;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.KeyUtil;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * Zoom (killer560, 2026-10-19). While the zoom key is held in the world, the view narrows to the zoom level; letting go eases
 * back to normal. While it is held, the mouse wheel changes the level (scroll up zooms in). Only the field of view changes:
 * the view is never moved, and nothing is clicked or sent.
 * <p>
 * Smooth mode (the default) pans in and out over {@link #PAN_NANOS} with a quintic ease, evaluated on every rendered frame
 * rather than every game tick, so the motion has no steps. Instant mode jumps straight to the level. killer560: "smooth
 * smoothly pans into max zoom (smoother than current), instant is instant".
 */
public final class ZoomFeature {

    /** How long a smooth pan takes, in nanoseconds. */
    private static final long PAN_NANOS = 450_000_000L;

    /** The zoom factor the ease starts from, and where it is heading. 1 is normal. */
    private static float from = 1f;
    private static float to = 1f;
    /** When the current pan started (System.nanoTime). */
    private static long startedAt;
    private static boolean held;
    /** The level the wheel has set during this press, or -1 for the configured level. */
    private static float wheelLevel = -1f;

    private ZoomFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ZoomFeature::tick);
    }

    private static void tick(Minecraft client) {
        ZoomConfig cfg = ZoomConfig.getInstance();
        held = cfg.isEnabled() && client.getWindow() != null && McCompat.screen(client) == null
                && KeyUtil.isBindDown(client.getWindow(), cfg.getKeyCode()); // the tab accepts mouse buttons too
        if (!held) {
            wheelLevel = -1f;
        }
        float target = held ? (wheelLevel > 0f ? wheelLevel : cfg.getZoomLevel()) : 1f;
        if (target != to) {
            long now = System.nanoTime();
            from = factorAt(now);
            to = target;
            startedAt = now;
        }
        if (!cfg.isSmooth()) {
            from = to;
        }
    }

    /** The zoom factor at {@code now}: eased from the last start towards the target, or the target once the pan is over. */
    private static float factorAt(long now) {
        if (!ZoomConfig.getInstance().isSmooth()) {
            return to;
        }
        double t = Math.min(1.0, Math.max(0.0, (now - startedAt) / (double) PAN_NANOS));
        // Quintic smootherstep: starts and stops with no jerk, so the pan reads as one even movement.
        double eased = t * t * t * (t * (t * 6 - 15) + 10);
        return (float) (from + (to - from) * eased);
    }

    /** The field of view to draw with, given the normal one in degrees. Narrows it on the tangent so the zoom is even. */
    public static float applyFov(float fovDegrees) {
        float current = factorAt(System.nanoTime());
        if (current <= 1.0001f) {
            return fovDegrees;
        }
        double half = Math.toRadians(fovDegrees) / 2.0;
        return (float) Math.toDegrees(2.0 * Math.atan(Math.tan(half) / current));
    }

    /** The mouse wheel, while zoom is held. @return true when the wheel was used for the zoom (so it does not change the hotbar). */
    public static boolean onScroll(double yOffset) {
        ZoomConfig cfg = ZoomConfig.getInstance();
        if (!held || !cfg.isScrollWhileZoomed() || yOffset == 0.0) {
            return false;
        }
        float level = wheelLevel > 0f ? wheelLevel : cfg.getZoomLevel();
        level *= (float) Math.pow(1.1, yOffset);
        wheelLevel = Math.max(ZoomConfig.MIN_LEVEL, Math.min(ZoomConfig.MAX_LEVEL, level));
        return true;
    }
}

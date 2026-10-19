package com.killer560.hub.zoom;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.KeyUtil;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * Zoom (killer560, 2026-10-19). While the zoom key is held in the world, the view narrows to the zoom level; letting go eases
 * back to normal. While it is held, the mouse wheel changes the level (scroll up zooms in). Only the field of view changes:
 * the view is never moved, and nothing is clicked or sent.
 */
public final class ZoomFeature {

    /** The factor the view is zoomed by right now: 1 is normal, the level while held. Eases towards its target each tick. */
    private static float current = 1f;
    private static float target = 1f;
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
        target = held ? (wheelLevel > 0f ? wheelLevel : cfg.getZoomLevel()) : 1f;
        current += (target - current) * 0.3f;
        if (Math.abs(target - current) < 0.001f) {
            current = target;
        }
    }

    /** The field of view to draw with, given the normal one in degrees. Narrows it on the tangent so the zoom is even. */
    public static float applyFov(float fovDegrees) {
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

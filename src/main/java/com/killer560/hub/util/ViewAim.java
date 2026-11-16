package com.killer560.hub.util;

import com.killer560.hub.autoroutes.RouteRotation;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Turns the view toward a point for one owner at a time (cheat features: the Carnival autos, the Wither Aimbot), only
 * through Auto Routes' {@link RouteRotation}: eased per frame, yaw a delta on the running value (never wrapped), pitch
 * within -90..+90, optionally behind {@link ViewFreeze} so the screen holds still while the body turns. It yields to a
 * running Auto Route or AP3 and to the Emergency Cancel Key. The per-frame step runs only while an owner aims.
 * Lifted out of {@code carnival/CarnivalInput} (witheraim-1111) unchanged.
 */
public final class ViewAim {

    private static final Logger LOGGER = ModLog.get("killer560smod-viewaim");
    /** Who is turning the view now (a feature name), or null. */
    private static String aimOwner;
    private static boolean registered;
    /** A moving target this far from the look is a new one: flicked to, then followed (when asked for). */
    static final float FLICK_DEGREES = 6f;
    static final int FLICK_TICKS = 10;
    private static long flickTick = -100;

    private ViewAim() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            try {
                if (aimOwner != null && !routeRunning()) {
                    RouteRotation.frame();
                }
            } catch (RuntimeException e) {
                LOGGER.error("[ViewAim] aim frame failed", e);
            }
        });
    }

    private static boolean routeRunning() {
        return com.killer560.hub.autoroutes.RouteExecutor.isRunning() || com.killer560.hub.ap3.Ap3Executor.isRunning();
    }

    /** Yaw and pitch from the eye to {@code point}; yaw as a delta on the running yaw (never wrapped). */
    public static float[] lookAt(LocalPlayer player, Vec3 point) {
        Vec3 eye = player.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
        float pitch = Mth.clamp((float) (-Mth.atan2(dy, h) * (180.0 / Math.PI)), -90f, 90f);
        return new float[]{player.getYRot() + Mth.wrapDegrees(yaw - player.getYRot()), pitch};
    }

    /** Angular distance (degrees) from the current look to {@code point}. */
    public static float error(LocalPlayer player, Vec3 point) {
        float[] r = lookAt(player, point);
        return (float) Math.hypot(Mth.wrapDegrees(r[0] - player.getYRot()), r[1] - player.getXRot());
    }

    /**
     * Turns toward {@code point} for {@code owner}: a fresh humanized approach for a still point ({@code moving} false),
     * a plain ease that follows a moving one. {@code freeCamera} holds the screen still while the body turns.
     * Does nothing while another owner aims or a route runs.
     */
    public static void aim(String owner, LocalPlayer player, Vec3 point, boolean moving, boolean freeCamera) {
        aim(owner, player, point, moving, freeCamera, false);
    }

    /**
     * As {@link #aim(String, LocalPlayer, Vec3, boolean, boolean)}; with {@code flick} a moving target more than
     * FLICK_DEGREES off gets a rolled humanized approach first, then a follow once it lands or FLICK_TICKS pass.
     */
    public static void aim(String owner, LocalPlayer player, Vec3 point, boolean moving, boolean freeCamera,
                           boolean flick) {
        if (routeRunning() || (aimOwner != null && !aimOwner.equals(owner))) {
            return;
        }
        // The Emergency Cancel Key holds everything automatic (MacroStop); ActionGate already holds the clicks.
        if (com.killer560.hub.macrostop.MacroStop.emergencyHeld()) {
            release(owner);
            return;
        }
        if (aimOwner == null) {
            RouteRotation.clearUserMoved();
        }
        aimOwner = owner;
        float[] r = lookAt(player, point);
        long now = ActionGate.currentTick();
        if (moving && flick) {
            // Following from the first tick turned every acquisition into the same machine-smooth curve.
            if (!RouteRotation.isActive() || error(player, point) > FLICK_DEGREES && now - flickTick > FLICK_TICKS) {
                RouteRotation.beginApproach(r[0], r[1], false, 0f, 0f);
                flickTick = now;
            } else if (now - flickTick > FLICK_TICKS || RouteRotation.settled(1.5f)) {
                RouteRotation.follow(r[0], r[1]);
            }
        } else if (moving) {
            RouteRotation.follow(r[0], r[1]);
        } else if (!RouteRotation.isActive() || RouteRotation.settled(1.0f)
                || Math.abs(Mth.wrapDegrees(r[0] - player.getYRot())) > 3f) {
            RouteRotation.beginApproach(r[0], r[1], false, 0f, 0f);
        }
        if (freeCamera) {
            ViewFreeze.hold(player.getYRot(), player.getXRot());
        }
    }

    /** {@code owner} stops turning the view. */
    public static void release(String owner) {
        if (owner.equals(aimOwner)) {
            aimOwner = null;
            RouteRotation.clear();
        }
    }

    /** True while {@code owner} is the one turning the view. */
    public static boolean aiming(String owner) {
        return owner.equals(aimOwner);
    }

    /** Whether the player turned the mouse while {@code owner} was aiming. */
    public static boolean userTookOver(String owner, LocalPlayer player) {
        return owner.equals(aimOwner) && RouteRotation.userMovedCamera(player);
    }
}

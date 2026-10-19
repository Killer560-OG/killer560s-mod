package com.killer560.hub.autoroutes;

import com.killer560.hub.util.ModLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * One short line per route event, all under the tag {@code [AutoRoutes] trace}, so a run's node-by-node story can be
 * grepped out of his log in one pass (killer560, 2026-10-18: "Auto routes seems to be having issues on main getting secrets
 * but not teleporting or teleporting to the wrong blocks"). Only logs: nothing here changes what the route does.
 * <ul>
 * <li>{@code start} - a node began: where he stood, the block it aims at (its landing block, else the node's own block),
 * eye-to-box distance to that block, and what its await waits for.</li>
 * <li>{@code sent} - a warp or a click went out: from where, at which block, how far, along which look.</li>
 * <li>{@code landed} - the server put him somewhere: before and after, whether that is the planned block, whether the
 * server's position packet came (a real teleport) and how many ticks it took.</li>
 * <li>{@code correction} - a server position packet the route did not ask for.</li>
 * <li>{@code await met} / {@code await waiting} - the secret count the node waited on.</li>
 * <li>{@code idle} - nothing fired for a while because he stands in no next node's ring (where it is, how far).</li>
 * <li>WARN {@code timeout} - a step gave up, naming the condition that never came.</li>
 * </ul>
 * Through {@link ModLog}, so a release jar drops these (only ERROR is forwarded there) and a dev or cheat build logs them.
 */
final class RouteTrace {

    private static final Logger LOGGER = ModLog.get("killer560smod-autoroutes");

    private RouteTrace() {
    }

    static void start(int index, RouteNode.Type type, Vec3 player, BlockPos target, String targetBlock, String await) {
        LOGGER.info("[AutoRoutes] trace #{} {} start | player {} | target {} | reach {} | await {}", index, type,
                fmt(player), block(target, targetBlock), dist(eyeToBox(player, target)), await);
    }

    static void sent(int index, String what, Vec3 from, BlockPos target, String targetBlock, float yaw, float pitch) {
        LOGGER.info("[AutoRoutes] trace #{} {} sent | from {} | target {} | reach {} | look {}/{}", index, what, fmt(from),
                block(target, targetBlock), dist(eyeToBox(from, target)), String.format(Locale.US, "%.2f", yaw),
                String.format(Locale.US, "%.2f", pitch));
    }

    static void landed(int index, String what, Vec3 before, Vec3 after, BlockPos target, boolean onTarget,
                       boolean serverTeleport, int ticks) {
        LOGGER.info("[AutoRoutes] trace #{} {} landed | before {} | after {} | target {} on it {} | server teleport {} | {} tick(s)",
                index, what, fmt(before), fmt(after), target == null ? "none" : target.toShortString(),
                target == null ? "-" : onTarget ? "yes" : "NO", serverTeleport ? "yes" : "no", ticks);
    }

    static void correction(int index, String type, Vec3 now, double moved, String what) {
        LOGGER.info("[AutoRoutes] trace #{} {} correction | moved {} | now {} | {}", index, type, dist(moved), fmt(now), what);
    }

    static void awaitMet(int index, RouteNode.Type type, int have, int need, int remembered, int ticks) {
        LOGGER.info("[AutoRoutes] trace #{} {} await met | {}/{} secrets ({} remembered) | after ~{} tick(s)", index, type, have,
                need, remembered, ticks);
    }

    static void awaitWaiting(int index, RouteNode.Type type, String condition, int have, int need, int remembered,
                             int seconds, Vec3 player) {
        LOGGER.warn("[AutoRoutes] trace #{} {} await waiting | {} {}/{} ({} remembered) for {} s | player {} - nothing it "
                + "counts has come (a left click skips it)", index, type, condition, have, need, remembered, seconds, fmt(player));
    }

    static void idle(int index, RouteNode.Type type, int ticks, Vec3 player, Vec3 node, double distance) {
        LOGGER.info("[AutoRoutes] trace idle {} tick(s) | next node #{} {} not entered | player {} | node {} | {} blocks off",
                ticks, index, type, fmt(player), fmt(node), dist(distance));
    }

    static void timeout(int index, String what, String condition, int ticks, Vec3 player) {
        LOGGER.warn("[AutoRoutes] trace #{} {} timeout | {} | after {} tick(s) | player {}", index, what, condition, ticks,
                fmt(player));
    }

    /** From an eye 1.62 over {@code feet} to the nearest point of the block's unit box. */
    static double eyeToBox(Vec3 feet, BlockPos b) {
        if (feet == null || b == null) {
            return Double.NaN;
        }
        Vec3 eye = feet.add(0, 1.62, 0);
        return KeySpots.boxDistance(eye, b);
    }

    private static String block(BlockPos pos, String name) {
        return pos == null ? "none" : pos.toShortString() + (name == null ? "" : " (" + name + ")");
    }

    private static String dist(double d) {
        return Double.isNaN(d) ? "-" : String.format(Locale.US, "%.2f", d);
    }

    private static String fmt(Vec3 v) {
        return v == null ? "null" : String.format(Locale.US, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }
}

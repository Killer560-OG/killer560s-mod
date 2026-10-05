package com.killer560.hub.autoroutes;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.autoclear.ClearExecutor;
import com.killer560.hub.livemap.autoclear.EtherwarpPathfinder;
import com.killer560.hub.livemap.autoclear.TeleportUtils;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Plans a PATH pair's etherwarps ONCE and saves them on the pair's first node (killer560, 2026-10-05: "It only needs
 * to generate the movement once and then save it not generate it every time").
 *
 * <p>The plan is the Interactive Map's own: {@link EtherwarpPathfinder#findDungeonPath} with the map's
 * {@link ClearExecutor#pathConfig()} and {@link ClearExecutor#hopRange()}, from the first node's spot to the block
 * under the second - the call {@code ClearExecutor} makes for a map press. Each hop is stored room-relative (where it
 * is warped from, the look, the block it lands on, and the landing), so it replays the same in any rotation and on
 * Hypixel, and {@link RouteExecutor} flies it with its own etherwarp step - the one ew nodes use, with the same sneak,
 * landing proof and missing-block wait.
 *
 * <p>A saved plan is valid while both nodes still stand where they did when it was made ({@link RouteNode#planFromX}
 * and {@link RouteNode#planToX}): moving, deleting or undoing either one makes it stale, and the next look at it
 * (after the edit, or the route firing it) plans it again.
 */
public final class RoutePathPlanner {

    private static final Logger LOGGER = ModLog.get("killer560smod-autoroutes");
    private static final ExecutorService PLANNER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-autoroutes-path");
        t.setDaemon(true);
        return t;
    });
    /** Plans started since the game opened - read by the testkit to prove a replay did not plan again. */
    private static volatile int plansStarted;
    private static volatile boolean planning;

    private RoutePathPlanner() {
    }

    public static int plansStarted() {
        return plansStarted;
    }

    public static boolean isPlanning() {
        return planning;
    }

    /** True when {@code src} is the first of a complete pair and its saved warps still describe that pair. */
    public static boolean valid(Route route, RouteNode src) {
        RouteNode dest = route == null ? null : route.pathDestination(src);
        return dest != null && !src.pathHops.isEmpty()
                && near(src.planFromX, src.x) && near(src.planFromY, src.y) && near(src.planFromZ, src.z)
                && near(src.planToX, dest.x) && near(src.planToY, dest.y) && near(src.planToZ, dest.z);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 0.002;
    }

    /**
     * Plans every complete pair of {@code route} whose saved warps are stale, one after another, and says how many
     * warps each saved. Does nothing while a route runs (the executor plans what it fires) or a plan is under way.
     */
    public static void refreshStale(Route route, RouteCoords.Frame frame) {
        if (route == null || frame == null || planning || RouteExecutor.isRunning()) {
            return;
        }
        for (RouteNode n : route.nodes()) {
            if (n.type == RouteNode.Type.PATH && route.pathDestination(n) != null && !valid(route, n)) {
                plan(route, frame, n, why -> {
                    if (why == null) {
                        refreshStale(route, RouteCoords.Frame.current());
                    }
                });
                return;
            }
        }
    }

    /**
     * Plans {@code src}'s pair now (async) and saves the warps on it. {@code done} runs on the client thread with null
     * on success or the reason it failed (also said in chat).
     */
    public static void plan(Route route, RouteCoords.Frame frame, RouteNode src, Consumer<String> done) {
        Minecraft client = Minecraft.getInstance();
        RouteNode dest = route == null ? null : route.pathDestination(src);
        if (dest == null || frame == null || client.level == null) {
            done.accept("no destination path node");
            return;
        }
        int a = route.indexOf(src) + 1;
        int b = route.indexOf(dest) + 1;
        Vec3 from = RouteCoords.toReal(frame, src.relativePos());
        Vec3 destReal = RouteCoords.toReal(frame, dest.relativePos());
        BlockPos below = BlockPos.containing(destReal.x, destReal.y - 0.5, destReal.z);
        BlockPos goal = TeleportUtils.etherwarpable(below) ? below : TeleportUtils.nearestEtherwarpable(below);
        if (goal == null) {
            String why = "no etherwarpable block at or near path #" + b;
            AutoRoutesFeature.chatBad("Path #" + a + " -> #" + b + ": " + why + ".");
            done.accept(why);
            return;
        }
        DungeonLayout layout = DungeonLayout.capture();
        EtherwarpPathfinder.PathConfig cfg = ClearExecutor.pathConfig();
        double range = ClearExecutor.hopRange();
        // The source/destination as they stand NOW: a stamp taken after the search could describe a node moved
        // while it ran.
        double fx = src.x, fy = src.y, fz = src.z;
        double tx = dest.x, ty = dest.y, tz = dest.z;
        planning = true;
        plansStarted++;
        LOGGER.info("[AutoRoutes] Path #{} -> #{}: planning with the Interactive Map's floor planner, {} to {}", a, b,
                fmt(from), goal.toShortString());
        PLANNER.submit(() -> {
            long t0 = System.currentTimeMillis();
            List<EtherwarpPathfinder.Node> path = null;
            try {
                path = EtherwarpPathfinder.findDungeonPath(from, goal, cfg, range, layout);
            } catch (Throwable e) {
                LOGGER.warn("[AutoRoutes] Path search failed: {}", e.toString());
            }
            long took = System.currentTimeMillis() - t0;
            List<EtherwarpPathfinder.Node> result = path;
            client.execute(() -> {
                planning = false;
                String why = save(route, frame, src, result, range, fx, fy, fz, tx, ty, tz);
                if (why == null) {
                    LOGGER.info("[AutoRoutes] Path #{} -> #{} planned by the floor planner: {} warp(s) in {} ms - saved",
                            a, b, src.pathHops.size(), took);
                    AutoRoutesFeature.chat(ModChat.text("Path "), ModChat.value("#" + a + " -> #" + b),
                            ModChat.text(": " + src.pathHops.size() + " warp(s) saved"), ModChat.dim(" (" + took + " ms)"));
                } else {
                    LOGGER.info("[AutoRoutes] Path #{} -> #{} not planned: {}", a, b, why);
                    AutoRoutesFeature.chatBad("Path #" + a + " -> #" + b + ": " + why + ".");
                }
                done.accept(why);
            });
        });
    }

    /** Turns the planner's hops into room-relative saved warps on {@code src}. @return null, or why not */
    private static String save(Route route, RouteCoords.Frame frame, RouteNode src, List<EtherwarpPathfinder.Node> path,
                               double range, double fx, double fy, double fz, double tx, double ty, double tz) {
        if (path == null) {
            return "the planner found no etherwarp path";
        }
        if (path.isEmpty()) {
            return "the two path nodes are on the same block";
        }
        if (path.size() > RouteStore.MAX_PATH_HOPS) {
            return "the path needs " + path.size() + " warps, more than " + RouteStore.MAX_PATH_HOPS;
        }
        List<RouteNode.PathHop> hops = new ArrayList<>();
        for (EtherwarpPathfinder.Node n : path) {
            Vec3 eye = new Vec3(n.x, n.y + TeleportUtils.eyeHeight(true), n.z);
            TeleportUtils.RaycastResult hit = TeleportUtils.getEtherPos(eye, n.yaw, n.pitch, range);
            if (hit == null || !hit.succeeded() || hit.pos() == null) {
                return "a planned warp does not reach a block";
            }
            BlockPos target = hit.pos();
            Vec3 o = RouteCoords.toRelative(frame, n.vec());
            Vec3 l = RouteCoords.toRelative(frame, new Vec3(target.getX() + 0.5, target.getY() + 1.05, target.getZ() + 0.5));
            BlockPos t = RouteCoords.toRelativeBlock(frame, target);
            hops.add(new RouteNode.PathHop(o.x, o.y, o.z, RouteCoords.toRelativeYaw(frame, n.yaw), n.pitch, t,
                    l.x, l.y, l.z));
        }
        if (route.indexOf(src) < 0) {
            return "the path node was deleted while it was planned";
        }
        src.pathHops.clear();
        src.pathHops.addAll(hops);
        src.planFromX = fx;
        src.planFromY = fy;
        src.planFromZ = fz;
        src.planToX = tx;
        src.planToY = ty;
        src.planToZ = tz;
        src.roundToSaved();
        RouteStore.getInstance().save();
        return null;
    }

    private static String fmt(Vec3 v) {
        return String.format(java.util.Locale.US, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }
}

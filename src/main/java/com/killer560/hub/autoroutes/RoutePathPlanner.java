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
            if (n.type == RouteNode.Type.PATH && route.pathDestination(n) != null && !valid(route, n)
                    && !isProving(n)) {
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
     * Throws away EVERY saved path plan in every room's route (killer560, 2026-10-09: "Later on I am going to have you
     * regenerate every one of my path nodes"), so each pair is planned again with the exact planner: this room's at
     * once ({@code /ar replan} calls {@link #refreshStale} next), every other room's the next time its route fires the
     * pair or the route is edited (RouteExecutor plans a pair with no saved warps before flying it). Nothing else of a
     * node changes. Saves the routes file.
     *
     * @return {pairs cleared, rooms they were in}
     */
    public static int[] invalidateAll() {
        int pairs = 0;
        int rooms = 0;
        for (Route route : RouteStore.getInstance().routes().values()) {
            int before = pairs;
            for (RouteNode n : route.nodes()) {
                if (n.type == RouteNode.Type.PATH && !n.pathHops.isEmpty()) {
                    n.pathHops.clear();
                    pairs++;
                }
            }
            rooms += pairs > before ? 1 : 0;
        }
        if (pairs > 0) {
            RouteStore.getInstance().save();
        }
        LOGGER.info("[AutoRoutes] replan: cleared {} saved path plan(s) in {} room(s)", pairs, rooms);
        return new int[]{pairs, rooms};
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
        // The block he placed the node on is the goal even when it is a bottom slab (his choice, killer560 2026-10-09);
        // anything picked for him instead (nearestEtherwarpable) is never one.
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
        // Breaker Aura's picks are planned as gone (killer560, 2026-10-11: "treat those as not existing when it plans
        // its route as they should be broken whenever it goes by"). Read here, on the client thread, through the aura's
        // own rule: only while the aura is on, and only blocks it would actually break. If one is still standing when
        // the warp goes, the warp lands short and is handled as any failed landing is.
        java.util.Set<Long> air = breakerAir(route, frame, src);
        planning = true;
        plansStarted++;
        LOGGER.info("[AutoRoutes] Path #{} -> #{}: planning with the Interactive Map's floor planner, {} to {}{}", a, b,
                fmt(from), goal.toShortString(), air.isEmpty() ? "" : " (" + air.size()
                        + " breaker node block(s) planned as broken)");
        // On the Interactive Map's planner thread, never one of our own: the floor graph is shared with the map's
        // warm-up and is not thread-safe. A search here racing the warm-up pushed the graph's node count past its
        // arrays, after which nothing planned for the rest of the floor (2026-10-06).
        ClearExecutor.onPlanner(() -> {
            long t0 = System.currentTimeMillis();
            List<EtherwarpPathfinder.Node> path = null;
            try {
                path = EtherwarpPathfinder.findOptimalPath(from, goal, cfg, range, layout,
                        EtherwarpPathfinder.PATH_EXACT_NANOS, air);
            } catch (Throwable e) {
                LOGGER.warn("[AutoRoutes] Path search failed: {}", e.toString());
            }
            long took = System.currentTimeMillis() - t0;
            List<EtherwarpPathfinder.Node> result = path;
            EtherwarpPathfinder.ExactInfo info = EtherwarpPathfinder.lastExact;
            EtherwarpPathfinder.Prover prover = EtherwarpPathfinder.lastProver;
            boolean unreachable = EtherwarpPathfinder.unreachable;
            client.execute(() -> {
                planning = false;
                String why = result == null && unreachable
                        ? "no etherwarp path ends on path #" + b + "'s block " + goal.toShortString()
                        + " - warps only land next to it; place the node on a block a warp can reach"
                        : save(route, frame, src, result, range, fx, fy, fz, tx, ty, tz, goal, !air.isEmpty());
                if (prover != null) {
                    // Not proved on the planner thread in its turn: what was found is saved (if anything) and the proof
                    // carries on, unbounded, on its own thread. The pair does not fly until it is proved.
                    prove(route, frame, src, prover, range, fx, fy, fz, tx, ty, tz, goal, !air.isEmpty(), a, b);
                    if (why != null && result == null) {
                        why = null;   // nothing yet, but the proof is still looking
                    }
                }
                if (prover != null) {
                    AutoRoutesFeature.chat(ModChat.text("Path "), ModChat.value("#" + a + " -> #" + b),
                            ModChat.text(": " + (src.pathHops.isEmpty() ? "nothing" : src.pathHops.size() + " warp(s)")
                                    + " so far"), ModChat.dim(" - proving it the fewest in the background..."));
                } else if (why == null) {
                    boolean proven = info != null && info.proven() && info.warps() == src.pathHops.size();
                    LOGGER.info("[AutoRoutes] Path #{} -> #{} planned by the floor planner: {} warp(s) in {} ms - saved, {}",
                            a, b, src.pathHops.size(), took, proven ? "the fewest there are (proved)"
                                    : "NOT proved the fewest" + (info == null ? "" : " (" + info.why()
                                    + ", none with fewer than " + info.fewerThan() + ")"));
                    AutoRoutesFeature.chat(ModChat.text("Path "), ModChat.value("#" + a + " -> #" + b),
                            ModChat.text(": " + src.pathHops.size() + " warp(s) saved"),
                            proven ? ModChat.dim(" (fewest possible, " + took + " ms)")
                                    : ModChat.bad(" (best found in " + took + " ms, not proved fewest)"));
                } else {
                    LOGGER.info("[AutoRoutes] Path #{} -> #{} not planned: {}", a, b, why);
                    AutoRoutesFeature.chatBad("Path #" + a + " -> #" + b + ": " + why + ".");
                }
                done.accept(why);
            });
        });
    }

    // ------------------------------------------------------------------------------------------- background proofs

    /** Pairs whose proof is still running (identity): RouteExecutor holds a pair until its proof is in. */
    private static final java.util.Set<RouteNode> PROVING =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static final java.util.Map<RouteNode, EtherwarpPathfinder.Prover> PROVERS = new java.util.IdentityHashMap<>();
    private static java.util.concurrent.ExecutorService proofThread;

    /** Whether {@code src}'s pair is still being proved (client thread). */
    public static boolean isProving(RouteNode src) {
        return PROVING.contains(src);
    }

    /**
     * Runs the rest of a proof on the path-proof thread (one at a time, never the map's planner thread) and, when it is
     * in, saves the shorter route if there is one - as long as both nodes still stand where they did. Client thread.
     */
    private static void prove(Route route, RouteCoords.Frame frame, RouteNode src, EtherwarpPathfinder.Prover prover,
                              double range, double fx, double fy, double fz, double tx, double ty, double tz,
                              BlockPos goal, boolean airy, int a, int b) {
        EtherwarpPathfinder.Prover old = PROVERS.put(src, prover);
        if (old != null) {
            old.cancel();
        }
        PROVING.add(src);
        if (proofThread == null) {
            proofThread = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "killer560smod-pathproof");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
        }
        Minecraft client = Minecraft.getInstance();
        long t0 = System.currentTimeMillis();
        proofThread.submit(() -> {
            List<EtherwarpPathfinder.Node> shorter = null;
            try {
                shorter = prover.run();
            } catch (Throwable e) {
                LOGGER.warn("[AutoRoutes] Path proof failed: {}", e.toString());
            }
            List<EtherwarpPathfinder.Node> found = shorter;
            long took = System.currentTimeMillis() - t0;
            client.execute(() -> {
                if (PROVERS.get(src) != prover) {
                    return;   // a newer plan of this pair took over
                }
                PROVERS.remove(src);
                PROVING.remove(src);
                if (!prover.proven()) {
                    return;   // cancelled: the pair moved or went
                }
                RouteNode dest = route.pathDestination(src);
                boolean same = dest != null && route.indexOf(src) >= 0 && Math.abs(src.x - fx) < 0.002
                        && Math.abs(src.y - fy) < 0.002 && Math.abs(src.z - fz) < 0.002 && Math.abs(dest.x - tx) < 0.002
                        && Math.abs(dest.y - ty) < 0.002 && Math.abs(dest.z - tz) < 0.002;
                if (!same) {
                    return;
                }
                if (found != null) {
                    String why = save(route, frame, src, found, range, fx, fy, fz, tx, ty, tz, goal, airy);
                    if (why != null) {
                        AutoRoutesFeature.chatBad("Path #" + a + " -> #" + b + ": the proved route could not be saved: "
                                + why + ".");
                        return;
                    }
                }
                LOGGER.info("[AutoRoutes] Path #{} -> #{} proved in the background after {} ms: {} warp(s), the fewest"
                        + " there are (proved)", a, b, took, src.pathHops.size());
                if (src.pathHops.isEmpty()) {
                    AutoRoutesFeature.chatBad("Path #" + a + " -> #" + b + ": no etherwarp path (proved).");
                } else {
                    AutoRoutesFeature.chat(ModChat.text("Path "), ModChat.value("#" + a + " -> #" + b),
                            ModChat.text(": " + src.pathHops.size() + " warp(s) saved"),
                            ModChat.dim(" (fewest possible, proved in " + took + " ms)"));
                }
            });
        });
    }

    /** Stops every running proof (a world change); their pairs plan again when they are next needed. */
    public static void cancelProofs() {
        for (EtherwarpPathfinder.Prover p : PROVERS.values()) {
            p.cancel();
        }
        PROVERS.clear();
        PROVING.clear();
    }

    /**
     * The blocks the route's own Dungeon Breaker nodes break before {@code src}'s pair flies, in world coordinates:
     * every block of every DUNGEON_BREAKER node numbered before {@code src}, or stacked on its tile (a breaker fires
     * before the etherwarp slot). killer560 (2026-10-12): "the path node isn't planning like there aren't any blocks
     * blocking it for dungeon breaker stuff" - and "During clear the only way to auto break blocks is through the
     * breaker node", so Breaker Aura's list (boss only) is not read here at all. Client thread.
     */
    public static List<BlockPos> breakerBlocksBefore(Route route, RouteCoords.Frame frame, RouteNode src) {
        List<BlockPos> out = new ArrayList<>();
        if (route == null || frame == null || src == null) {
            return out;
        }
        int at = route.indexOf(src);
        List<RouteNode> nodes = route.nodes();
        for (int i = 0; i < nodes.size(); i++) {
            RouteNode n = nodes.get(i);
            if (n.type != RouteNode.Type.DUNGEON_BREAKER) {
                continue;
            }
            boolean before = i < at;
            boolean stacked = Math.floor(n.x) == Math.floor(src.x) && Math.floor(n.z) == Math.floor(src.z)
                    && Math.abs(n.y - src.y) < 1.0;
            if (!before && !stacked) {
                continue;
            }
            for (BlockPos rel : n.breakerBlocks) {
                out.add(RouteCoords.toRealBlock(frame, rel));
            }
        }
        return out;
    }

    /** {@link #breakerBlocksBefore}, packed for the planner ({@code EtherSearch.pack}). */
    static java.util.Set<Long> breakerAir(Route route, RouteCoords.Frame frame, RouteNode src) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        for (BlockPos p : breakerBlocksBefore(route, frame, src)) {
            out.add(com.killer560.hub.livemap.autoclear.EtherSearch.pack(p.getX(), p.getY(), p.getZ()));
        }
        return out;
    }

    /** A stamp of the breaker blocks a pair's plan assumed broken (0 for none), saved with the plan. */
    public static int breakerStamp(Route route, RouteNode src) {
        int h = 0;
        if (route == null) {
            return h;
        }
        int at = route.indexOf(src);
        List<RouteNode> nodes = route.nodes();
        for (int i = 0; i < nodes.size(); i++) {
            RouteNode n = nodes.get(i);
            boolean stacked = Math.floor(n.x) == Math.floor(src.x) && Math.floor(n.z) == Math.floor(src.z)
                    && Math.abs(n.y - src.y) < 1.0;
            if (n.type == RouteNode.Type.DUNGEON_BREAKER && (i < at || stacked)) {
                for (BlockPos b : n.breakerBlocks) {
                    h = h * 31 + b.hashCode();
                }
            }
        }
        return h;
    }

    /**
     * Turns the planner's hops into room-relative saved warps on {@code src}. Each warp's landing is the block the plan
     * put it on (the next warp's block, the last one's {@code goal}); with no Breaker Aura blocks planned as broken
     * ({@code airy} false) each is also cast again here and must land exactly there. @return null, or why not
     */
    private static String save(Route route, RouteCoords.Frame frame, RouteNode src, List<EtherwarpPathfinder.Node> path,
                               double range, double fx, double fy, double fz, double tx, double ty, double tz,
                               BlockPos goal, boolean airy) {
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
        for (int i = 0; i < path.size(); i++) {
            EtherwarpPathfinder.Node n = path.get(i);
            BlockPos target = i + 1 < path.size() ? path.get(i + 1).pos : goal;
            if (!airy) {
                Vec3 eye = new Vec3(n.x, n.y + TeleportUtils.eyeHeight(true), n.z);
                TeleportUtils.RaycastResult hit = TeleportUtils.getEtherPos(eye, n.yaw, n.pitch, range);
                if (hit == null || !hit.succeeded() || hit.pos() == null) {
                    return "a planned warp does not reach a block";
                }
                target = hit.pos();
            }
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
        src.planBreakers = breakerStamp(route, src);
        src.roundToSaved();
        RouteStore.getInstance().save();
        return null;
    }

    private static String fmt(Vec3 v) {
        return String.format(java.util.Locale.US, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }
}

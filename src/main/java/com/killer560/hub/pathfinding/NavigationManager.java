package com.killer560.hub.pathfinding;

import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The live navigation: holds one target, keeps a graph path to it, recalculates when the player leaves that path, and
 * reports arrival. Same shape as SkyHanni's {@code data/IslandGraphs.kt}: find the node closest to the player, run
 * Dijkstra to the node closest to the goal, drop the first node when walking straight to the second one is shorter,
 * and re-path whenever the closest node changes while the player is no longer on the path.
 */
public final class NavigationManager {

    /** Called when the player reaches the target. */
    @FunctionalInterface
    public interface ArrivalListener {
        void arrived(Target target);
    }

    public static final class Target {
        public final String label;
        public final Vec3 pos;
        /** Graph node index this target came from, or -1 for a raw coordinate. */
        public final int nodeIndex;
        /** Keep navigating after arriving (fairy souls: you still have to click the soul). */
        public final boolean stayOnArrive;
        final ArrivalListener listener;
        boolean announcedArrival;

        public Target(String label, Vec3 pos, int nodeIndex, boolean stayOnArrive, ArrivalListener listener) {
            this.label = label;
            this.pos = pos;
            this.nodeIndex = nodeIndex;
            this.stayOnArrive = stayOnArrive;
            this.listener = listener;
        }
    }

    private static final long MIN_REPATH_MS = 400L;

    private static Target target;
    private static List<Vec3> path = List.of();
    private static String pathIsland;
    private static long lastPathMs;
    private static int lastStartNode = -1;
    private static Object lastLevel;
    private static Vec3 lastPlayerPos;
    private static boolean teleported;

    private NavigationManager() {
    }

    // ------------------------------------------------------------------ public API

    public static boolean isActive() {
        return target != null;
    }

    public static Target target() {
        return target;
    }

    /** Path points (block centers) from the first graph node to the target, or empty. */
    public static List<Vec3> path() {
        return path;
    }

    public static String pathIsland() {
        return pathIsland;
    }

    public static void navigate(String label, Vec3 pos, int nodeIndex, boolean stayOnArrive, ArrivalListener listener) {
        target = new Target(label, pos, nodeIndex, stayOnArrive, listener);
        path = List.of();
        lastPathMs = 0;
        lastStartNode = -1;
        pathIsland = IslandDetector.graphIsland();
        recalculate(true);
        PathfindingConfig cfg = PathfindingConfig.getInstance();
        if (cfg.isChatFeedback()) {
            FairySoulsFeature.say(PathfindingConfig.chatName(), ModChat.text("Navigating to "), ModChat.value(label),
                    ModChat.dim(String.format(Locale.US, " (%.0fm)", remainingDistance())));
        }
    }

    public static void stop(String reason, boolean announce) {
        if (target == null) {
            return;
        }
        String label = target.label;
        target = null;
        path = List.of();
        if (announce && PathfindingConfig.getInstance().isChatFeedback()) {
            FairySoulsFeature.say(PathfindingConfig.chatName(), ModChat.text("Stopped navigating to "), ModChat.value(label),
                    reason == null ? ModChat.text(".") : ModChat.dim(" (" + reason + ")"));
        }
    }

    /** Distance still to walk: player to the path, then along the path to the target. */
    public static double remainingDistance() {
        Minecraft client = Minecraft.getInstance();
        if (target == null || client.player == null) {
            return 0;
        }
        Vec3 pos = client.player.position();
        if (path.size() < 2) {
            return pos.distanceTo(target.pos);
        }
        int seg = closestSegment(pos);
        Vec3 proj = projectOnSegment(pos, path.get(seg), path.get(seg + 1));
        double total = pos.distanceTo(proj) + proj.distanceTo(path.get(seg + 1));
        for (int i = seg + 1; i < path.size() - 1; i++) {
            total += path.get(i).distanceTo(path.get(i + 1));
        }
        return total;
    }

    // ------------------------------------------------------------------ ticking

    public static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (client.level != lastLevel) {
            lastLevel = client.level;
            lastPlayerPos = null;
            if (target != null) {
                stop("world change", true);
            }
        }
        teleported = false;
        if (player == null) {
            return;
        }
        Vec3 pos = player.position();
        if (lastPlayerPos != null && lastPlayerPos.distanceToSqr(pos) > 64.0) {
            teleported = true;
        }
        lastPlayerPos = pos;
        if (target == null) {
            return;
        }
        PathfindingConfig cfg = PathfindingConfig.getInstance();
        if (!cfg.isEnabled() && !FairySoulsFeature.autoRunning()) {
            stop(null, false);
            return;
        }
        String island = IslandDetector.graphIsland();
        if (pathIsland != null && !pathIsland.equals(island)) {
            stop("left the island", true);
            return;
        }

        double distToTarget = pos.distanceTo(target.pos);
        if (distToTarget <= cfg.getArriveDistance()) {
            if (!target.announcedArrival) {
                target.announcedArrival = true;
                if (cfg.isChatFeedback()) {
                    FairySoulsFeature.say(PathfindingConfig.chatName(), ModChat.text("Arrived at "), ModChat.value(target.label));
                }
                Target reached = target;
                if (!reached.stayOnArrive) {
                    target = null;
                    path = List.of();
                }
                if (reached.listener != null) {
                    reached.listener.arrived(reached);
                }
                return;
            }
        } else if (target.announcedArrival && distToTarget > cfg.getArriveDistance() + 4.0) {
            // walked away from a "stay" target (e.g. a soul that was never clicked) - path back to it
            target.announcedArrival = false;
            recalculate(true);
        }

        if (target == null) {
            return;
        }
        boolean stale = path.isEmpty() || teleported;
        if (!stale) {
            double offPath = Math.sqrt(distanceSqToPath(pos));
            stale = offPath > cfg.getRecalcDistance();
            if (!stale && offPath > 3.0) {
                IslandGraph graph = GraphRepository.get(island);
                if (graph != null) {
                    IslandGraph.Node nearest = graph.nearest(pos.x, pos.y + 1.0, pos.z);
                    stale = nearest != null && nearest.index != lastStartNode;
                }
            }
        }
        if (stale) {
            recalculate(false);
        }
    }

    /** Rebuilds the path from wherever the player is now. */
    public static void recalculate(boolean force) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || target == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastPathMs < MIN_REPATH_MS) {
            return;
        }
        lastPathMs = now;
        String island = IslandDetector.graphIsland();
        IslandGraph graph = island == null ? null : GraphRepository.get(island);
        if (graph == null) {
            // no graph (yet): fall back to a straight line so the target is still shown
            path = List.of(player.position(), target.pos);
            return;
        }
        Vec3 pos = player.position();
        IslandGraph.Node start = graph.nearest(pos.x, pos.y + 1.0, pos.z);
        IslandGraph.Node goal = target.nodeIndex >= 0 && target.nodeIndex < graph.nodes.length
                ? graph.nodes[target.nodeIndex]
                : graph.nearest(target.pos.x, target.pos.y, target.pos.z);
        if (start == null || goal == null) {
            path = List.of(pos, target.pos);
            return;
        }
        lastStartNode = start.index;
        GraphPathfinder.Path result = GraphPathfinder.shortestPath(graph, start.index, goal.index);
        List<Vec3> points = new ArrayList<>();
        if (result.isEmpty()) {
            // unreachable on the graph (rare: disconnected clusters) - straight line, still better than nothing
            points.add(centre(start));
        } else {
            int[] nodes = result.nodes();
            int from = 0;
            // SkyHanni's shortcut: skip the first node when walking straight to the second one is shorter
            if (nodes.length >= 2) {
                double viaFirst = pos.distanceTo(centre(graph.nodes[nodes[0]]))
                        + centre(graph.nodes[nodes[0]]).distanceTo(centre(graph.nodes[nodes[1]]));
                double direct = pos.distanceTo(centre(graph.nodes[nodes[1]]));
                if (direct < viaFirst) {
                    from = 1;
                }
            }
            for (int i = from; i < nodes.length; i++) {
                points.add(centre(graph.nodes[nodes[i]]));
            }
        }
        Vec3 last = points.isEmpty() ? pos : points.get(points.size() - 1);
        if (last.distanceToSqr(target.pos) > 1.0) {
            points.add(target.pos);
        }
        path = List.copyOf(points);
    }

    // ------------------------------------------------------------------ geometry helpers

    /** Feet-level center of a graph node's block (positions in the graph are the block a player stands on). */
    public static Vec3 centre(IslandGraph.Node node) {
        return new Vec3(node.x + 0.5, node.y + 0.5, node.z + 0.5);
    }

    // closestSegment is asked many times a tick for one position (the walker's carrot tries, corner scan, remaining
    // distance, the HUD's width/height/render): the path list is only ever replaced, so identity plus the position is a key.
    private static List<Vec3> segPath;
    private static double segX = Double.NaN;
    private static double segY;
    private static double segZ;
    private static int segResult;

    public static int closestSegment(Vec3 pos) {
        if (path == segPath && pos.x == segX && pos.y == segY && pos.z == segZ) {
            return segResult;
        }
        int found = closestSegment0(pos);
        segPath = path;
        segX = pos.x;
        segY = pos.y;
        segZ = pos.z;
        segResult = found;
        return found;
    }

    private static int closestSegment0(Vec3 pos) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < path.size() - 1; i++) {
            double d = pos.distanceToSqr(projectOnSegment(pos, path.get(i), path.get(i + 1)));
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    public static double distanceSqToPath(Vec3 pos) {
        if (path.isEmpty()) {
            return Double.MAX_VALUE;
        }
        if (path.size() == 1) {
            return pos.distanceToSqr(path.get(0));
        }
        double best = Double.MAX_VALUE;
        for (int i = 0; i < path.size() - 1; i++) {
            best = Math.min(best, pos.distanceToSqr(projectOnSegment(pos, path.get(i), path.get(i + 1))));
        }
        return best;
    }

    public static Vec3 projectOnSegment(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double lenSq = ab.lengthSqr();
        if (lenSq < 1.0e-6) {
            return a;
        }
        double t = p.subtract(a).dot(ab) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        return a.add(ab.scale(t));
    }

    /** A point {@code ahead} blocks further along the path than the player's projection - the walker's "carrot". */
    public static Vec3 pointAhead(Vec3 pos, double ahead) {
        if (path.isEmpty()) {
            return null;
        }
        if (path.size() == 1) {
            return path.get(0);
        }
        int seg = closestSegment(pos);
        Vec3 current = projectOnSegment(pos, path.get(seg), path.get(seg + 1));
        double remaining = ahead;
        for (int i = seg; i < path.size() - 1; i++) {
            Vec3 next = path.get(i + 1);
            double d = current.distanceTo(next);
            if (d >= remaining) {
                return current.add(next.subtract(current).normalize().scale(remaining));
            }
            remaining -= d;
            current = next;
        }
        return path.get(path.size() - 1);
    }
}

package com.killer560.hub.miningqol;

import com.killer560.hub.livemap.autoclear.TeleportUtils;
import com.killer560.hub.pathfinding.GraphPathfinder;
import com.killer560.hub.pathfinding.IslandGraph;
import com.killer560.hub.pathfinding.NavigationManager;
import com.killer560.hub.util.BoxBatch;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Commission Route (legit, both builds; killer560 2026-11-03: "have an option to draw a pathfinding line to wherever i need
 * to go to do a comission then once i am there there is no pathfinding line", and "three options for the line one for
 * etherwarp one for aote one for walking").
 *
 * <p>The destination is the area of the first open commission that names one ({@link MiningState#COMMISSION_AREAS}:
 * "Lava Springs Mithril" goes to Lava Springs); a commission with no area ("Mithril Miner", "Goblin Slayer") is skipped,
 * and with none left there is no line. The path is the shortest one over SkyHanni's Dwarven Mines island graph (the one
 * {@link MinesAreaMap} names areas from), from the node nearest him to the nearest node of that area
 * ({@link GraphPathfinder}, one Dijkstra), drawn from where he stands through the nodes. In the target area (the sidebar
 * says so, or the area map's label of the block he stands on) the line goes. Recomputed when the commissions, the map,
 * the mode or his nearest node change, never per frame.
 *
 * <p>Route Mode: Walk is the graph path. AOTE cuts it into Aspect of the End teleports ({@link #AOTE_RANGE} blocks along the
 * look, landing predicted with {@link TeleportUtils#getTeleportPos}, the same cast Auto Clear's AOTV node uses) and boxes
 * each landing. Etherwarp picks, from each stand, the furthest block on the path ahead that an etherwarp reaches
 * ({@link TeleportUtils#etherwarpable}: solid with two free blocks above; {@link TeleportUtils#getEtherwarpDirection}:
 * a visible face whose cast lands on it, within {@link #ETHERWARP_RANGE}) and highlights it; where no warp reaches the
 * next stretch, it walks that stretch. Only draws: nothing is aimed, clicked or sent.
 */
public final class CommissionRoute {

    /** Hypixel's etherwarp range before Transmission Tuners (EtherwarpOverlayFeature, SimAbilities). */
    public static final double ETHERWARP_RANGE = 57.0;
    /** Aspect of the End's Instant Transmission, before Transmission Tuners (hypixelskyblock wiki: 8 blocks). */
    public static final double AOTE_RANGE = 8.0;
    private static final int MAX_HOPS = 64;
    private static final int MIN_TICKS_BETWEEN = 5;

    private static volatile Vec3[] points = new Vec3[0];
    private static volatile List<BlockPos> hopBlocks = List.of();
    private static volatile int[] pathNodes = new int[0];
    private static volatile String targetArea = null;
    private static String lastKey = "";
    private static int sinceCompute = 0;
    private static int frames = 0;
    private static int computes = 0;
    private static volatile Vec3 lastOrigin = null;
    private static volatile double[] lastPatch = null;
    private static int patches = 0;

    private static final BoxBatch FILLS = new BoxBatch();
    private static final BoxBatch LINES = new BoxBatch();

    private CommissionRoute() {
    }

    private static void clear() {
        points = new Vec3[0];
        hopBlocks = List.of();
        pathNodes = new int[0];
        targetArea = null;
        lastKey = "";
    }

    /** The area of the first open commission that names one, or null. */
    public static String targetArea(List<MiningState.Commission> coms) {
        for (MiningState.Commission c : coms) {
            if (c.done()) {
                continue;
            }
            for (String a : MiningState.COMMISSION_AREAS) {
                if (c.name().startsWith(a + " ")) {
                    return a;
                }
            }
        }
        return null;
    }

    static void tick(Minecraft client) {
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        if (!cfg.isCommissionRoute() || !MiningState.dwarvenMines()
                || !MiningState.commissionsKnown() || client.player == null || client.level == null) {
            clear();
            return;
        }
        IslandGraph g = MinesAreaMap.graph();
        boolean claim = CommissionClaim.routeToEmissary();
        String area = claim ? EMISSARY : targetArea(MiningState.commissions());
        if (g == null || g.nodes.length == 0 || area == null) {
            clear();
            return;
        }
        Vec3 pos = client.player.position();
        BlockPos feet = client.player.blockPosition();
        if (claim) {
            for (BlockPos e : CommissionClaim.emissaries()) {
                if (e.distSqr(feet) <= EMISSARY_ARRIVED * EMISSARY_ARRIVED) {
                    clear();
                    return;
                }
            }
        } else if (MiningState.areaIs(MiningState.area(), area)
                || area.equals(MinesAreaMap.areaAt(feet.getX(), feet.getY(), feet.getZ()))) {
            clear();
            return;
        }
        IslandGraph.Node start = g.nearest(pos.x, pos.y + 1.0, pos.z);
        if (start == null) {
            clear();
            return;
        }
        MiningQolConfig.CommissionRouteMode mode = cfg.getCommissionRouteMode();
        String key = MiningState.version() + "|" + MinesAreaMap.version() + "|" + start.index + "|" + area + "|" + mode;
        sinceCompute++;
        if (key.equals(lastKey) || (sinceCompute < MIN_TICKS_BETWEEN && !lastKey.isEmpty())) {
            return;
        }
        lastKey = key;
        sinceCompute = 0;
        computes++;
        int[] goals = claim ? emissaryNodes(g) : MinesAreaMap.nodesInArea(area);
        targetArea = area;
        if (goals.length == 0) {
            points = new Vec3[0];
            hopBlocks = List.of();
            pathNodes = new int[0];
            return;
        }
        Plan plan = plan(g, pos, start.index, goals, mode);
        if (plan == null) {
            points = new Vec3[0];
            hopBlocks = List.of();
            pathNodes = new int[0];
            return;
        }
        pathNodes = plan.nodes;
        hopBlocks = plan.hops;
        points = plan.line.toArray(new Vec3[0]);
    }

    private static final String EMISSARY = "Emissary";
    private static final double EMISSARY_ARRIVED = 6.0;
    /** What one etherwarp costs in blocks of walking: fewer warps win (killer560: "more of a minimal etherwarps style"). */
    static final double ETHER_COST = 25.0;
    /** What one AOTE teleport costs in blocks of walking. */
    static final double AOTE_COST = 5.0;
    private static final int MAX_RAYCAST_TESTS = 250;

    private record Plan(List<Vec3> line, List<BlockPos> hops, int[] nodes) {
    }

    /** The graph node nearest each Emissary. */
    private static int[] emissaryNodes(IslandGraph g) {
        List<BlockPos> es = CommissionClaim.emissaries();
        java.util.LinkedHashSet<Integer> out = new java.util.LinkedHashSet<>();
        for (BlockPos e : es) {
            IslandGraph.Node n = g.nearest(e.getX() + 0.5, e.getY(), e.getZ() + 0.5);
            if (n != null) {
                out.add(n.index);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static Vec3 feetOf(IslandGraph.Node n) {
        return new Vec3(n.x + 0.5, n.y, n.z + 0.5);
    }

    /** Per-search state, arrays indexed by node (the last index is the player's own spot). */
    private static final class Search {
        final double[] dist;
        final int[] prev;
        final boolean[] warpEdge;
        final BlockPos[] hopOf;
        final Vec3[] standOf;
        final boolean[] done;
        final java.util.PriorityQueue<double[]> pq = new java.util.PriorityQueue<>((x, y) -> Double.compare(x[0], y[0]));

        Search(int size) {
            dist = new double[size];
            prev = new int[size];
            warpEdge = new boolean[size];
            hopOf = new BlockPos[size];
            standOf = new Vec3[size];
            done = new boolean[size];
            java.util.Arrays.fill(dist, Double.MAX_VALUE);
            java.util.Arrays.fill(prev, -1);
        }

        void relax(int u, int v, double w, boolean warp, BlockPos hop, Vec3 stand) {
            if (v < 0 || v >= dist.length || done[v]) {
                return;
            }
            double nd = dist[u] + w;
            if (nd < dist[v]) {
                dist[v] = nd;
                prev[v] = u;
                warpEdge[v] = warp;
                hopOf[v] = hop;
                standOf[v] = stand;
                pq.add(new double[]{nd, v});
            }
        }
    }

    /**
     * One Dijkstra over the walking graph PLUS warp edges (killer560, 2026-11-03: "the pathfinding for aote up to stuff like
     * lava springs still doesnt work. instead of just teleporting up through the air it just has me run around to it"). The
     * player's own spot is a virtual node that walks to his nearest graph node and warps like any other. From a node,
     * Etherwarp mode adds an edge to each graph node an etherwarp reaches from there (a solid block under it, two free above,
     * within {@link #ETHERWARP_RANGE}, line of sight: {@link TeleportUtils#getEtherwarpDirection}) at {@link #ETHER_COST},
     * and AOTE mode one to each node within {@link #AOTE_RANGE} that a straight teleport through air lands on
     * ({@link TeleportUtils#getTeleportPos}) at {@link #AOTE_COST}. Only warps that get closer to the goal are tried, best
     * first, and at most {@link #MAX_RAYCAST_TESTS} line-of-sight tests are spent per route.
     */
    private static Plan plan(IslandGraph g, Vec3 pos, int startNode, int[] goals, MiningQolConfig.CommissionRouteMode mode) {
        int n = g.nodes.length;
        int me = n;
        double[] h = new double[n + 1];
        for (int i = 0; i < n; i++) {
            double best = Double.MAX_VALUE;
            Vec3 f = feetOf(g.nodes[i]);
            for (int gi : goals) {
                best = Math.min(best, f.distanceTo(feetOf(g.nodes[gi])));
            }
            h[i] = best;
        }
        double bestMe = Double.MAX_VALUE;
        for (int gi : goals) {
            bestMe = Math.min(bestMe, pos.distanceTo(feetOf(g.nodes[gi])));
        }
        h[me] = bestMe;
        boolean[] isGoal = new boolean[n + 1];
        for (int gi : goals) {
            isGoal[gi] = true;
        }
        Search s = new Search(n + 1);
        s.dist[me] = 0;
        s.standOf[me] = pos;
        s.pq.add(new double[]{0, me});
        int tests = 0;
        int reached = -1;
        boolean ether = mode == MiningQolConfig.CommissionRouteMode.ETHERWARP;
        while (!s.pq.isEmpty()) {
            double[] e = s.pq.poll();
            int u = (int) e[1];
            if (s.done[u]) {
                continue;
            }
            s.done[u] = true;
            if (u != me && isGoal[u]) {
                reached = u;
                break;
            }
            Vec3 stand = u == me ? pos : feetOf(g.nodes[u]);
            if (u == me) {
                s.relax(u, startNode, pos.distanceTo(feetOf(g.nodes[startNode])), false, null, null);
            } else {
                int[] out = g.nodes[u].neighbours();
                double[] w = g.nodes[u].weights();
                for (int j = 0; j < out.length; j++) {
                    s.relax(u, out[j], w[j], false, null, null);
                }
            }
            if (mode == MiningQolConfig.CommissionRouteMode.WALK) {
                continue;
            }
            // Warp edges: candidates that get closer to the goal, best first.
            double range = ether ? ETHERWARP_RANGE : AOTE_RANGE + 2.0;
            double minGain = ether ? 8.0 : 1.5;
            List<Integer> cand = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                if (j == u || s.done[j]) {
                    continue;
                }
                double d = feetOf(g.nodes[j]).distanceTo(stand);
                if (d > range || d < (ether ? 6.0 : 2.0) || h[j] > h[u] - minGain) {
                    continue;
                }
                cand.add(j);
            }
            cand.sort((x, y) -> Double.compare(h[x], h[y]));
            int limit = u == me ? 40 : (ether ? 10 : 40);
            int tried = 0;
            for (int j : cand) {
                if (tried >= limit || tests >= MAX_RAYCAST_TESTS) {
                    break;
                }
                tried++;
                tests++;
                Vec3 f = feetOf(g.nodes[j]);
                if (ether) {
                    BlockPos ground = groundUnder(new Vec3(f.x, f.y + 0.5, f.z));
                    if (ground == null) {
                        continue;
                    }
                    Vec3 eye = stand.add(0, TeleportUtils.SNEAK_EYE, 0);
                    if (Vec3.atCenterOf(ground).distanceTo(eye) > ETHERWARP_RANGE
                            || TeleportUtils.getEtherwarpDirection(eye, ground, ETHERWARP_RANGE, false) == null) {
                        continue;
                    }
                    s.relax(u, j, ETHER_COST, true, ground,
                            new Vec3(ground.getX() + 0.5, ground.getY() + 1.0, ground.getZ() + 0.5));
                } else {
                    // AOTE always travels its full range along the look unless a block stops it, so the edge goes to
                    // the graph node nearest where aiming at node j LANDS, not to j itself.
                    Vec3 eye = stand.add(0, 1.62, 0);
                    TeleportUtils.Rotation r = TeleportUtils.getDirection(eye, f.add(0, 1.62, 0));
                    TeleportUtils.RaycastResult res = TeleportUtils.getTeleportPos(eye, r.yaw(), r.pitch(), AOTE_RANGE);
                    Vec3 land = res.succeeded() && res.pos() != null ? settle(res.pos()) : null;
                    if (land == null) {
                        continue;
                    }
                    int k = -1;
                    double kd = 2.0;
                    for (int m = 0; m < n; m++) {
                        double d = feetOf(g.nodes[m]).distanceTo(land);
                        if (d <= kd && m != u && !s.done[m] && h[m] <= h[u] - minGain) {
                            kd = d;
                            k = m;
                        }
                    }
                    if (k >= 0) {
                        s.relax(u, k, AOTE_COST, true, BlockPos.containing(land.x, land.y - 0.5, land.z), land);
                    }
                }
            }
        }
        if (reached < 0) {
            return null;
        }
        List<Integer> rev = new ArrayList<>();
        for (int c = reached; c != -1; c = s.prev[c]) {
            rev.add(c);
        }
        java.util.Collections.reverse(rev);
        List<Vec3> line = new ArrayList<>();
        List<BlockPos> hops = new ArrayList<>();
        List<Integer> nodes = new ArrayList<>();
        for (int idx = 1; idx < rev.size(); idx++) {
            int c = rev.get(idx);
            nodes.add(c);
            if (s.warpEdge[c]) {
                line.add(s.standOf[c]);
                hops.add(s.hopOf[c]);
            } else {
                line.add(NavigationManager.centre(g.nodes[c]));
            }
        }
        return new Plan(line, List.copyOf(hops), nodes.stream().mapToInt(Integer::intValue).toArray());
    }

    /** The block a stand at node point p is on: the first etherwarpable block at or just below its feet. */
    private static BlockPos groundUnder(Vec3 p) {
        int x = (int) Math.floor(p.x), z = (int) Math.floor(p.z);
        int fy = (int) Math.floor(p.y);
        for (int dy = 1; dy <= 3; dy++) {
            BlockPos b = new BlockPos(x, fy - dy, z);
            if (TeleportUtils.etherwarpable(b)) {
                return b;
            }
        }
        return null;
    }

    /** Where a teleport landing in block {@code p} comes to rest: down onto the first solid block within 4. */
    private static Vec3 settle(BlockPos p) {
        Minecraft mc = Minecraft.getInstance();
        BlockPos q = p;
        for (int i = 0; i < 4 && mc.level != null; i++) {
            BlockPos below = q.below();
            if (!mc.level.getBlockState(below).getCollisionShape(mc.level, below).isEmpty()) {
                break;
            }
            q = below;
        }
        return new Vec3(q.getX() + 0.5, q.getY(), q.getZ() + 0.5);
    }

    static void render(LevelRenderContext context) {
        Vec3[] pts = points;
        Minecraft client = Minecraft.getInstance();
        if (pts.length == 0 || client.player == null || !MiningState.dwarvenMines()) {
            return;
        }
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        float[] k = WorldRenderUtils.argbToFloats(cfg.getCommissionRouteColor());
        Vec3 me = WorldRenderUtils.tracerOrigin(context);
        lastOrigin = me;
        Vec3[] line = new Vec3[pts.length + 1];
        line[0] = me;
        System.arraycopy(pts, 0, line, 1, pts.length);
        WorldRenderUtils.renderLineStrip(context, line, k[0], k[1], k[2], 0.95f, 3f);
        List<BlockPos> hops = hopBlocks;
        if (!hops.isEmpty()) {
            FILLS.clear();
            LINES.clear();
            // Each landing is a 3x3 patch on the block tops around it (killer560, 2026-11-03: "highlight a 3x3 area to
            // etherwarp onto"), with the exact block's top outlined brighter inside it.
            double e = 0.004;
            for (BlockPos p : hops) {
                double top = p.getY() + 1.0 + e;
                FILLS.add(p.getX() - 1, top, p.getZ() - 1, p.getX() + 2, top + 0.03, p.getZ() + 2, k[0], k[1], k[2], 0.3f);
                LINES.add(p.getX() - 1, top, p.getZ() - 1, p.getX() + 2, top + 0.03, p.getZ() + 2, k[0], k[1], k[2], 0.8f);
                LINES.add(p.getX(), top, p.getZ(), p.getX() + 1, top + 0.03, p.getZ() + 1, k[0], k[1], k[2], 1f);
                lastPatch = new double[]{p.getX() - 1, top, p.getZ() - 1, p.getX() + 2, p.getZ() + 2};
                patches++;
            }
            FILLS.drawFilled(context, RenderTypes.debugFilledBox());
            LINES.drawOutlines(context, RenderTypes.LINES_TRANSLUCENT, 2f);
        }
        frames++;
    }

    // ---- testkit hooks

    /** Graph node ids (SkyHanni's keys) along the current path, in order. */
    public static List<Integer> pathNodeIds() {
        IslandGraph g = MinesAreaMap.graph();
        int[] n = pathNodes;
        List<Integer> out = new ArrayList<>();
        if (g == null) {
            return out;
        }
        for (int i : n) {
            if (i >= 0 && i < g.nodes.length) {
                out.add(g.nodes[i].id);
            }
        }
        return out;
    }

    /** The line's points after the player, as "x,y,z" (one decimal). */
    public static List<String> linePoints() {
        List<String> out = new ArrayList<>();
        for (Vec3 v : points) {
            out.add(String.format(Locale.ROOT, "%.1f,%.1f,%.1f", v.x, v.y, v.z));
        }
        return out;
    }

    public static int pointCount() {
        return points.length;
    }

    /** Hop blocks (etherwarp targets, or AOTE landing feet blocks) as "x,y,z". */
    public static List<String> hops() {
        List<String> out = new ArrayList<>();
        for (BlockPos p : hopBlocks) {
            out.add(p.getX() + "," + p.getY() + "," + p.getZ());
        }
        return out;
    }

    public static String currentTargetArea() {
        return targetArea;
    }

    /** Where the last drawn line started: the crosshair end of the camera's look (testkit). */
    public static double[] lastOrigin() {
        Vec3 o = lastOrigin;
        return o == null ? null : new double[]{o.x, o.y, o.z};
    }

    /** The last 3x3 landing patch drawn: minX, topY, minZ, maxX, maxZ (testkit). */
    public static double[] lastPatch() {
        return lastPatch;
    }

    public static int patches() {
        return patches;
    }

    public static int frames() {
        return frames;
    }

    public static int computes() {
        return computes;
    }
}

package com.killer560.hub.miningqol;

import com.killer560.hub.livemap.autoclear.TeleportUtils;
import com.killer560.hub.pathfinding.GraphPathfinder;
import com.killer560.hub.pathfinding.IslandGraph;
import com.killer560.hub.pathfinding.NavigationManager;
import com.killer560.hub.util.BoxBatch;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
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
 * ({@link GraphPathfinder}, one Dijkstra), drawn from where he stands through the nodes. For a mithril or titanium area
 * commission the goal is the area's nodes that have ore beside them in the loaded world ({@link OreNodes}: at least
 * {@link OreNodes#MIN_ORE} exposed ore blocks within {@link OreNodes#RADIUS} blocks; killer560 2026-11-03: "takes me to an
 * area but there are no mithril things there"), and the line goes only when he is at that node, not for entering the
 * area; with no ore node known (chunks not loaded, or another kind of commission) the goal is the area's nodes and the
 * line goes in the area (the sidebar says so, or the area map's label of the block he stands on). Recomputed when the commissions, the map,
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

    private static volatile Vec3[] points = new Vec3[0];
    private static volatile List<BlockPos> hopBlocks = List.of();
    private static volatile List<BlockPos> patchBlocks = List.of();
    private static volatile int[] pathNodes = new int[0];
    private static volatile String targetArea = null;
    private static int frames = 0;
    private static int computes = 0;
    private static volatile Vec3 lastOrigin = null;
    private static int patches = 0;
    /** The plan being followed; it is trimmed as he reaches its points and rebuilt only when it has to be. */
    private static Cur cur = null;
    private static String lastTried = "";
    private static int sincePlan = 0;
    /** Ticks before a plan that found nothing is tried again. */
    private static final int RETRY_TICKS = 20;
    /** Ticks before a plan he has walked off is rebuilt. */
    private static final int OFF_TICKS = 10;
    /** A point counts as reached within this many blocks sideways (and {@link #REACH_Y} up or down). */
    private static final double REACH = 2.5;
    private static final double REACH_Y = 3.0;
    /** Further than this from the whole remaining line, he has left it. */
    private static final double OFF_PATH = 8.0;

    private static final BoxBatch FILLS = new BoxBatch();
    private static final BoxBatch LINES = new BoxBatch();

    /** The plan and what is left of it: parallel lists, one entry per point of the line. */
    private static final class Cur {
        final String key;
        final List<Vec3> pts;
        final List<BlockPos> hopAt;
        final List<List<BlockPos>> patch;
        final int[] nodes;
        Vec3 anchor;

        Cur(String key, Plan plan, List<List<BlockPos>> patch, Vec3 anchor) {
            this.key = key;
            this.pts = new ArrayList<>(plan.line);
            this.hopAt = new ArrayList<>(plan.hopAt);
            this.patch = new ArrayList<>(patch);
            this.nodes = plan.nodes;
            this.anchor = anchor;
        }
    }

    private CommissionRoute() {
    }

    private static void clear() {
        cur = null;
        lastTried = "";
        targetArea = null;
        publish();
    }

    private static void publish() {
        Cur c = cur;
        if (c == null) {
            points = new Vec3[0];
            hopBlocks = List.of();
            patchBlocks = List.of();
            pathNodes = new int[0];
            return;
        }
        List<BlockPos> hops = new ArrayList<>();
        List<BlockPos> boxes = new ArrayList<>();
        for (int i = 0; i < c.pts.size(); i++) {
            if (c.hopAt.get(i) != null) {
                hops.add(c.hopAt.get(i));
                boxes.addAll(c.patch.get(i));
            }
        }
        points = c.pts.toArray(new Vec3[0]);
        hopBlocks = List.copyOf(hops);
        patchBlocks = List.copyOf(boxes);
        pathNodes = c.nodes;
    }

    /**
     * The 3x3 around a landing (killer560, 2026-11-03: "3x3 in that area and full block not just top layer. so if the 3x3 has
     * 1 block down a block highlight that instead"): in each of the nine columns, the highest block you could etherwarp onto
     * (solid, two free above) from three below the landing to two above it; a column with none is left out.
     */
    static List<BlockPos> patchAround(BlockPos ground) {
        List<BlockPos> out = new ArrayList<>(9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 2; dy >= -3; dy--) {
                    BlockPos b = ground.offset(dx, dy, dz);
                    if (TeleportUtils.etherwarpable(b)) {
                        out.add(b);
                        break;
                    }
                }
            }
        }
        return out;
    }

    private static double segDist(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double l2 = ab.lengthSqr();
        double t = l2 <= 1e-9 ? 0 : Math.max(0, Math.min(1, p.subtract(a).dot(ab) / l2));
        return a.add(ab.scale(t)).distanceTo(p);
    }

    /** Drops every point up to the furthest one he is standing at: a reached point or hop is done, no replan. */
    private static void trim(Cur c, Vec3 pos) {
        int last = -1;
        for (int i = 0; i < c.pts.size(); i++) {
            Vec3 q = c.pts.get(i);
            if (Math.hypot(q.x - pos.x, q.z - pos.z) <= REACH && Math.abs(q.y - pos.y) <= REACH_Y) {
                last = i;
            }
        }
        for (int i = 0; i <= last; i++) {
            c.anchor = c.pts.remove(0);
            c.hopAt.remove(0);
            c.patch.remove(0);
        }
    }

    private static boolean offPath(Cur c, Vec3 pos) {
        if (c.pts.isEmpty()) {
            return false;
        }
        Vec3 a = c.anchor;
        double best = Double.MAX_VALUE;
        for (Vec3 q : c.pts) {
            best = Math.min(best, segDist(pos, a, q));
            a = q;
        }
        return best > OFF_PATH;
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
        if (!cfg.isCommissionRoute() || cfg.commissionsHiddenNow() || !MiningState.dwarvenMines()
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
        String ore = claim ? null : OreNodes.oreFor(MiningState.commissions(), area);
        int[] oreGoals = new int[0];
        if (ore != null) {
            OreNodes.tick(client.level, g, area, ore, MinesAreaMap.nodesInArea(area), pos.x, pos.y, pos.z);
            oreGoals = OreNodes.goals();
        }
        if (claim) {
            for (BlockPos e : CommissionClaim.emissaries()) {
                if (e.distSqr(feet) <= EMISSARY_ARRIVED * EMISSARY_ARRIVED) {
                    clear();
                    return;
                }
            }
        } else if (oreGoals.length > 0) {
            // An ore node is known: being in the area is not enough, he has to be at the ore.
            for (int n : oreGoals) {
                IslandGraph.Node nd = g.nodes[n];
                if (Math.hypot(nd.x - pos.x, nd.z - pos.z) <= ORE_ARRIVED && Math.abs(nd.y - pos.y) <= ORE_ARRIVED_Y) {
                    clear();
                    return;
                }
            }
        } else if (MiningState.areaIs(MiningState.area(), area)
                || area.equals(MinesAreaMap.areaAt(feet.getX(), feet.getY(), feet.getZ()))) {
            clear();
            return;
        }
        MiningQolConfig.CommissionRouteMode mode = cfg.getCommissionRouteMode();
        // What a plan depends on: where it goes, how, and the map. Not the commission percentages (they change every few
        // seconds) and not which node he is nearest: those used to rebuild it, and its warps, again and again.
        String key = area + "|" + mode + "|" + MinesAreaMap.version() + "|" + java.util.Arrays.hashCode(oreGoals);
        sincePlan++;
        targetArea = area;
        boolean replan = false;
        if (cur == null || !cur.key.equals(key)) {
            replan = !key.equals(lastTried) || sincePlan >= RETRY_TICKS;
        } else {
            trim(cur, pos);
            replan = offPath(cur, pos) && sincePlan >= OFF_TICKS;
        }
        if (replan) {
            lastTried = key;
            sincePlan = 0;
            computes++;
            cur = null;
            IslandGraph.Node start = g.nearest(pos.x, pos.y + 1.0, pos.z);
            int[] goals = claim ? emissaryNodes(g) : oreGoals.length > 0 ? oreGoals : MinesAreaMap.nodesInArea(area);
            if (start != null && goals.length > 0) {
                Plan plan = plan(g, pos, start.index, goals, mode);
                if (plan != null) {
                    List<List<BlockPos>> patch = new ArrayList<>();
                    for (BlockPos h : plan.hopAt) {
                        patch.add(h == null ? List.of() : patchAround(h));
                    }
                    cur = new Cur(key, plan, patch, pos);
                    trim(cur, pos);
                }
            }
        }
        publish();
    }

    /** He is at an ore node within this many blocks sideways (and {@link #ORE_ARRIVED_Y} up or down): the line goes. */
    private static final double ORE_ARRIVED = 4.0;
    private static final double ORE_ARRIVED_Y = 5.0;
    private static final String EMISSARY = "Emissary";
    private static final double EMISSARY_ARRIVED = 6.0;
    /** What one etherwarp costs in blocks of walking: fewer warps win (killer560: "more of a minimal etherwarps style"). */
    static final double ETHER_COST = 25.0;
    /** What one AOTE teleport costs in blocks of walking. */
    static final double AOTE_COST = 5.0;
    private static final int MAX_RAYCAST_TESTS = 250;

    private record Plan(List<Vec3> line, List<BlockPos> hopAt, int[] nodes) {
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
                hops.add(null);
            }
        }
        return new Plan(line, hops, nodes.stream().mapToInt(Integer::intValue).toArray());
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
        List<BlockPos> boxes = patchBlocks;
        if (!boxes.isEmpty()) {
            // Each landing is a 3x3 of WHOLE blocks, each column at the height you can stand on it, with the landing block
            // itself outlined brighter. Depth-writing types (EspTypes), drawn from COLLECT_SUBMITS.
            FILLS.clear();
            LINES.clear();
            double e = 0.003;
            for (BlockPos p : boxes) {
                FILLS.add(p.getX() - e, p.getY() - e, p.getZ() - e, p.getX() + 1 + e, p.getY() + 1 + e, p.getZ() + 1 + e,
                        k[0], k[1], k[2], 0.25f);
                LINES.add(p.getX() - e, p.getY() - e, p.getZ() - e, p.getX() + 1 + e, p.getY() + 1 + e, p.getZ() + 1 + e,
                        k[0], k[1], k[2], 0.8f);
            }
            for (BlockPos p : hopBlocks) {
                LINES.add(p.getX() - 2 * e, p.getY() - 2 * e, p.getZ() - 2 * e, p.getX() + 1 + 2 * e, p.getY() + 1 + 2 * e,
                        p.getZ() + 1 + 2 * e, k[0], k[1], k[2], 1f);
            }
            patches = boxes.size();
            FILLS.drawFilled(context, com.killer560.hub.util.EspTypes.FILLED);
            LINES.drawOutlines(context, com.killer560.hub.util.EspTypes.LINES, 2f);
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

    /** How many patch boxes the last frame drew (testkit). */
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

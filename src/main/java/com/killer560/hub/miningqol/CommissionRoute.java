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
        if (!cfg.isCommissionRoute() || !cfg.isCommissionHud() || !MiningState.dwarvenMines()
                || !MiningState.commissionsKnown() || client.player == null || client.level == null) {
            clear();
            return;
        }
        IslandGraph g = MinesAreaMap.graph();
        String area = targetArea(MiningState.commissions());
        if (g == null || g.nodes.length == 0 || area == null) {
            clear();
            return;
        }
        Vec3 pos = client.player.position();
        BlockPos feet = client.player.blockPosition();
        if (MiningState.areaIs(MiningState.area(), area)
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
        int[] goals = MinesAreaMap.nodesInArea(area);
        if (goals.length == 0) {
            points = new Vec3[0];
            hopBlocks = List.of();
            pathNodes = new int[0];
            targetArea = area;
            return;
        }
        GraphPathfinder.Tree tree = GraphPathfinder.dijkstra(g, start.index, -1);
        int best = -1;
        for (int i : goals) {
            if (tree.reachable(i) && (best < 0 || tree.dist()[i] < tree.dist()[best])) {
                best = i;
            }
        }
        targetArea = area;
        if (best < 0) {
            points = new Vec3[0];
            hopBlocks = List.of();
            pathNodes = new int[0];
            return;
        }
        int[] nodes = tree.pathTo(best).nodes();
        pathNodes = nodes;
        List<Vec3> walk = new ArrayList<>();
        for (int n : nodes) {
            walk.add(NavigationManager.centre(g.nodes[n]));
        }
        switch (mode) {
            case ETHERWARP -> etherwarpPlan(pos, walk);
            case AOTE -> aotePlan(pos, walk);
            default -> {
                hopBlocks = List.of();
                points = walk.toArray(new Vec3[0]);
            }
        }
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

    private static void etherwarpPlan(Vec3 pos, List<Vec3> walk) {
        List<Vec3> line = new ArrayList<>();
        List<BlockPos> hops = new ArrayList<>();
        Vec3 stand = pos;
        int at = -1; // index into walk of the last point reached
        for (int guard = 0; guard < MAX_HOPS && at < walk.size() - 1; guard++) {
            Vec3 eye = stand.add(0, TeleportUtils.SNEAK_EYE, 0);
            int reached = -1;
            BlockPos block = null;
            for (int j = walk.size() - 1; j > at; j--) {
                Vec3 p = walk.get(j);
                if (p.distanceToSqr(eye) > (ETHERWARP_RANGE + 1) * (ETHERWARP_RANGE + 1)) {
                    continue;
                }
                BlockPos b = groundUnder(p);
                if (b == null || Vec3.atCenterOf(b).distanceTo(eye) > ETHERWARP_RANGE) {
                    continue;
                }
                if (TeleportUtils.getEtherwarpDirection(eye, b, ETHERWARP_RANGE, false) != null) {
                    reached = j;
                    block = b;
                    break;
                }
            }
            if (block == null) {
                // No warp reaches further: walk to the next point.
                at++;
                stand = walk.get(at);
                line.add(stand);
                continue;
            }
            hops.add(block);
            stand = new Vec3(block.getX() + 0.5, block.getY() + 1.0, block.getZ() + 0.5);
            line.add(stand);
            at = reached;
        }
        hopBlocks = List.copyOf(hops);
        points = line.toArray(new Vec3[0]);
    }

    private static void aotePlan(Vec3 pos, List<Vec3> walk) {
        List<Vec3> line = new ArrayList<>();
        List<BlockPos> hops = new ArrayList<>();
        Vec3 stand = pos;
        int next = 0;
        for (int guard = 0; guard < MAX_HOPS * 4 && next < walk.size(); guard++) {
            Vec3 aim = walk.get(next);
            if (stand.distanceTo(aim) < 1.5) {
                next++;
                continue;
            }
            Vec3 eye = stand.add(0, 1.62, 0);
            TeleportUtils.Rotation r = TeleportUtils.getDirection(eye, aim.add(0, 1.62, 0));
            TeleportUtils.RaycastResult res = TeleportUtils.getTeleportPos(eye, r.yaw(), r.pitch(), AOTE_RANGE);
            Vec3 land = res.succeeded() && res.pos() != null
                    ? new Vec3(res.pos().getX() + 0.5, res.pos().getY(), res.pos().getZ() + 0.5) : null;
            if (land == null || land.distanceTo(stand) < 2.0) {
                // A teleport gets nowhere here (wall, ceiling): walk to the point.
                stand = aim;
                line.add(stand);
                next++;
                continue;
            }
            hops.add(BlockPos.containing(land.x, land.y, land.z));
            stand = land;
            line.add(stand);
        }
        hopBlocks = List.copyOf(hops);
        points = line.toArray(new Vec3[0]);
    }

    static void render(LevelRenderContext context) {
        Vec3[] pts = points;
        Minecraft client = Minecraft.getInstance();
        if (pts.length == 0 || client.player == null || !MiningState.dwarvenMines()) {
            return;
        }
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        float[] k = WorldRenderUtils.argbToFloats(cfg.getCommissionRouteColor());
        Vec3 me = client.player.position().add(0, 0.1, 0);
        Vec3[] line = new Vec3[pts.length + 1];
        line[0] = me;
        System.arraycopy(pts, 0, line, 1, pts.length);
        WorldRenderUtils.renderLineStrip(context, line, k[0], k[1], k[2], 0.95f, 3f);
        List<BlockPos> hops = hopBlocks;
        if (!hops.isEmpty()) {
            FILLS.clear();
            LINES.clear();
            boolean ether = cfg.getCommissionRouteMode() == MiningQolConfig.CommissionRouteMode.ETHERWARP;
            for (BlockPos p : hops) {
                double e = 0.002;
                if (ether) {
                    FILLS.add(p.getX() - e, p.getY() - e, p.getZ() - e, p.getX() + 1 + e, p.getY() + 1 + e,
                            p.getZ() + 1 + e, k[0], k[1], k[2], 0.3f);
                    LINES.add(p.getX() - e, p.getY() - e, p.getZ() - e, p.getX() + 1 + e, p.getY() + 1 + e,
                            p.getZ() + 1 + e, k[0], k[1], k[2], 1f);
                } else {
                    LINES.add(p.getX() + 0.3, p.getY(), p.getZ() + 0.3, p.getX() + 0.7, p.getY() + 0.4, p.getZ() + 0.7,
                            k[0], k[1], k[2], 1f);
                }
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

    public static int frames() {
        return frames;
    }

    public static int computes() {
        return computes;
    }
}

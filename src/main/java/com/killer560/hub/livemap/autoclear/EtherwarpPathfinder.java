package com.killer560.hub.livemap.autoclear;

import com.killer560.hub.livemap.DungeonLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Etherwarp paths for the Interactive Map and the Etherwarp Hopper.
 *
 * <p><b>Dungeon clicks</b> ({@link #findDungeonPath}, {@link #findDungeonPathToTile}) search the whole floor as one
 * graph by the number of warps ({@link WarpGraph}), kept between clicks, warmed in the background while he is in a
 * dungeon ({@link #tickWarm}) and told about block changes by {@link LevelEtherGrid}. killer560 (2026-10-04): "it is
 * taking a lot of warps and taking like 40ms [...] prioritize using as few warps as physically possible." His log
 * had "11 leg(s) ... total 94.02 ms, 40 warp(s)" from the room-by-room planner this replaced; on
 * {@code tools/bench/FloorBench} (whole sim-style floors of the shipped captures) the graph takes 40% fewer warps
 * than that planner and a warm click a median of under a millisecond - see docs/SIM.md for the numbers and the
 * method. The room-by-room planner ({@link #legacyDungeonPath}: QUOI's {@code findDungeonPath}, one weighted-A*
 * leg per room along {@link DungeonMapPathfinder}'s route) is kept as the fallback for when the graph finds nothing,
 * so no click that used to find a path stops finding one.
 *
 * <p><b>Single searches</b> ({@link #findPath}, the Etherwarp Hopper) are QUOI's {@code EtherwarpPathfinder} in
 * behaviour - A* whose moves are etherwarp rays from a fixed yaw/pitch fan, then {@code smoothPath} - over
 * {@link EtherSearch} and a byte-per-block {@link LevelEtherGrid}.
 */
public final class EtherwarpPathfinder {

    private static final org.slf4j.Logger LOGGER =
            com.killer560.hub.util.ModLog.get("killer560smod-livemap");

    private EtherwarpPathfinder() {
    }

    /** QUOI {@code PathConfig}. {@code timeout} is milliseconds for the whole search. */
    public record PathConfig(float yawStep, float pitchStep, double hWeight, long timeout) {
    }

    /** QUOI {@code TeleportPathNode}: where he stands, the block under him, and the aim used FROM here. */
    public static final class Node {
        public final double x;
        public final double y;
        public final double z;
        public final BlockPos pos;
        public final float yaw;
        public final float pitch;

        Node(double x, double y, double z, BlockPos pos, float yaw, float pitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.pos = pos;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        public Vec3 vec() {
            return new Vec3(x, y, z);
        }
    }

    // ------------------------------------------------------------------------------------------- public API

    /** QUOI {@code findPath}: a single search, no room chaining. {@code layout} is unused and may be null. */
    public static List<Node> findPath(Vec3 from, BlockPos to, PathConfig cfg, double dist, boolean withLast,
                                      DungeonLayout layout) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        LevelEtherGrid grid = new LevelEtherGrid(level);
        EtherSearch search = new EtherSearch(grid);
        if (!search.etherwarpable(to.getX(), to.getY(), to.getZ())) {
            return null;
        }
        long deadline = System.nanoTime() + cfg.timeout() * 1_000_000L;
        EtherSearch.Leg leg = leg(cfg, dist, deadline);
        leg.goalX = to.getX();
        leg.goalY = to.getY();
        leg.goalZ = to.getZ();
        leg.directRadiusSq = 0;
        leg.isGoal = (x, y, z) -> x == to.getX() && y == to.getY() && z == to.getZ();
        leg.landingOk = coverTest(grid, level);
        List<EtherSearch.Hop> path = search.searchLeg(startHop(from), leg);
        if (path == null) {
            return null;
        }
        return toNodes(search.smooth(path, dist, withLast));
    }

    // ------------------------------------------------------------------------------------------- the floor graph

    /**
     * Fewest warps to one block, over the whole floor at once ({@link WarpGraph}). When the graph finds nothing
     * (or the floor is not warm yet and the search runs out of its short budget) the old room-by-room legs run
     * instead, so nothing that used to find a path stops finding one.
     */
    public static List<Node> findDungeonPath(Vec3 from, BlockPos to, PathConfig cfg, double dist,
                                             DungeonLayout layout) {
        return planFloor(from, to, -1, cfg, dist, layout);
    }

    /**
     * Fewest warps into one map tile: any landing in the clicked tile at its floor height, as
     * {@link TeleportUtils#etherwarpableInTile} defines it - killer560 (2026-10-01): "it just needs to go to that
     * room [...] It can choose anywhere in that room whatever is fastest." {@code to} is the block
     * etherwarpableInTile picked, used if no landing of the tile can be reached.
     *
     * @param tileIdx the 11x11 map index of the clicked tile (as {@link DungeonLayout#cellCenter(int)} takes it)
     */
    public static List<Node> findDungeonPathToTile(Vec3 from, BlockPos to, int tileIdx, PathConfig cfg, double dist,
                                                   DungeonLayout layout) {
        return planFloor(from, to, tileIdx, cfg, dist, layout);
    }

    /** No path is longer than this. */
    private static final int MAX_WARPS = 48;
    /**
     * Bucket width: one node per 2x2 columns and height (doorways and tile centre lines: every landing). 2026-10-05:
     * 3 -> 2 with PARTIAL_FROM 0.6 -> 0.3 took tools/bench/FloorBench whole floors from 8.26 to 8.04 warps a click
     * (the every-landing reference: 7.81 on the same 180 clicks, where bucket 3 took 8.19 and bucket 2 takes 8.01),
     * for 1.9x the edges and warm-up rays - affordable once EtherSearch.aimPast cut a warm-up's rays by 44-71%.
     * Bucket 1 reaches the reference (7.89 vs 7.88) but clicks take 11-14 ms at p95 and the graph holds 12M edges.
     */
    private static final int BUCKET = 2;
    /** A line blocked past this fraction of the way to a landing gets the full 18-point aim (WarpGraph). */
    private static final double PARTIAL_FROM = 0.3;

    private static List<Node> planFloor(Vec3 from, BlockPos to, int tileIdx, PathConfig cfg, double dist,
                                        DungeonLayout layout) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        long t0 = System.nanoTime();
        LevelEtherGrid grid = new LevelEtherGrid(level);
        if (tileIdx < 0 && !new EtherSearch(grid).etherwarpable(to.getX(), to.getY(), to.getZ())) {
            LOGGER.info("[Path] {} is not etherwarpable - it is not solid, or there is no standing room over"
                    + " it. Nothing searched.", to);
            return null;
        }
        FloorGraphs graphs = graphFor(level, dist);
        EtherSearch.Hop start = startHop(from);
        WarpGraph.Goal goal = new WarpGraph.Goal();
        goal.x = to.getX();
        goal.y = to.getY();
        goal.z = to.getZ();
        goal.deadEnd = deadEnds(layout);
        int tile6 = tileIdx < 0 ? -1 : ((tileIdx / DungeonLayout.GRID) / 2) * 6 + (tileIdx % DungeonLayout.GRID) / 2;
        String kind;
        WarpGraph.Goal exactFallback = null;
        if (tile6 >= 0) {
            FloorTiles tiles = graphTiles;
            int t = tile6;
            goal.region = (x, y, z) -> tiles.tileOf(x, y, z) == t;
            goal.tile = t;
            // Land a decent way into the room, towards the middle of the clicked tile (WarpGraph.DEEP), not just
            // past its doorway.
            goal.preferX = tiles.x0 + 32 * (t % 6) + 0.5;
            goal.preferZ = tiles.z0 + 32 * (t / 6) + 0.5;
            kind = "tile";
            if (new EtherSearch(grid).etherwarpable(to.getX(), to.getY(), to.getZ())) {
                // No landing of the tile's floor band reachable: the block etherwarpableInTile picked, exactly.
                exactFallback = new WarpGraph.Goal();
                exactFallback.x = to.getX();
                exactFallback.y = to.getY();
                exactFallback.z = to.getZ();
                exactFallback.deadEnd = goal.deadEnd;
            }
        } else {
            int room = layout.roomAtWorld(to.getX(), to.getZ());
            int tx = to.getX();
            int ty = to.getY();
            int tz = to.getZ();
            goal.near = (x, y, z) -> Math.abs(x - tx) <= NEAR_RADIUS && Math.abs(z - tz) <= NEAR_RADIUS
                    && Math.abs(y - ty) <= NEAR_RADIUS && layout.roomAtWorld(x, z) == room;
            kind = "exact";
        }
        // FloorGraphs.plan: the full graph once it is warm (finishing a re-warm after a block change first); before
        // that, the quick graph, which warms first and in about half a second.
        List<EtherSearch.Hop> path = graphs.plan(grid, () -> new LevelEtherGrid(level), workers(), workerCount, start,
                goal, exactFallback, t0, cfg.timeout() * 1_000_000L, MAX_WARPS);
        if (graphs.usedExact) {
            kind = "tile, then its block";
        }
        WarpGraph graph = graphs.used;
        boolean warm = graph.warmDone() || graph.warmedOnce();
        long end = System.nanoTime();
        if (path != null && path.isEmpty()) {
            LOGGER.info("[Path] already there ({}), {} ms", kind, ms(end - t0));
            return new ArrayList<>();
        }
        if (path == null && graphs.provedNoWay) {
            // The warm graph proves nothing reaches it from here (a closed door, a sealed room); the room-by-room
            // planner's landings are a subset of the graph's, so it would only spend its 670 ms failing too.
            LOGGER.info("[Path] no way to {} {} from here on the floor graph ({} ms) - not trying room by room", kind,
                    to, ms(end - t0));
            return null;
        }
        if (path == null) {
            LOGGER.info("[Path] fewest-warps graph found nothing for {} {} in {} ms ({}; {} graph; {} node(s) worked"
                            + " out now, graph {} node(s), warm-up {}) - room by room instead", kind, to, ms(end - t0),
                    graphs.timedOut ? "out of time" : "no way", graphs.usedName, graph.expandedCold, graph.nodeCount(),
                    graph.warmDone() ? "done" : "still running");
            LOGGER.info("[Path] not a proof of no way because: {}", graph.noWayWhy);
            return legacyDungeonPath(from, to, cfg, dist, layout);
        }
        String landing = graph.firmFixed > 0 || graph.firmDropped > 0 || graph.fragileLeft
                ? "; " + graph.firmFixed + " aim(s) moved to one that holds, " + graph.firmDropped
                + " fragile hop(s) dropped" + (graph.fragileLeft ? ", ONE STILL FRAGILE" : "") : "";
        if (tile6 >= 0 && !graphs.usedExact && graph.lastDepth >= 0) {
            landing += "; landing " + graph.lastDepth + " block(s) from the tile centre"
                    + (graph.lastDepth <= WarpGraph.DEEP ? "" : " (shallow: none within " + WarpGraph.DEEP
                    + " in the fewest warps or one more)")
                    + (graph.lastExtraWarp ? ", one warp more than the fewest to get that far in" : "");
        }
        // One line a click, so the cost can be read off his log rather than guessed at.
        LOGGER.info("[Path] {} warp(s) ({}{}), total {} ms on the {} graph: start {} ms, aim set {} ms ({} node(s)),"
                        + " backward labels {} ms ({} node(s)); {} node(s) worked out now, {} known, {} edge(s), {}"
                        + " ray(s); exact heuristic {}; graph {} node(s), warm-up {}; {} section(s) filled{}",
                path.size(), kind, graph.endedNear ? ", near: the block itself cannot be reached" : "",
                ms(end - t0), graphs.usedName, ms(graph.nanosStart), ms(graph.nanosAimSet), graph.goalSetSize,
                ms(graph.nanosLabels), graph.labelled, graph.expandedCold, graph.expandedWarm, graph.edgesScanned,
                graph.rays, graph.usedFields ? "yes" : "no", graph.nodeCount(), warm ? "done" : "still running",
                grid.filled, landing);
        return toNodes(path);
    }

    /**
     * Rooms an etherwarp cannot be used FROM: the rooms {@link AutoClearUtils#canPath} refuses to start in (QUOI's
     * rule: a maze, Boulder, a trap), which the sim enforces too (SimAbilities: traps, Teleport Maze, Boulder). A
     * path may land in one - the goal can be there - but never warp on from it.
     */
    private static EtherSearch.CellTest deadEnds(DungeonLayout layout) {
        boolean[] dead = new boolean[layout.roomCount()];
        boolean any = false;
        for (int r = 0; r < dead.length; r++) {
            String name = layout.name(r);
            var entry = layout.entry(r);
            boolean trap = entry != null && entry.type != null ? entry.type.equalsIgnoreCase("trap")
                    : name != null && name.contains("Trap");
            dead[r] = trap || (name != null && (name.contains("Maze") || name.contains("Boulder")));
            any |= dead[r];
        }
        if (!any) {
            return null;
        }
        return (x, y, z) -> {
            int r = layout.roomAtWorld(x, z);
            return r >= 0 && r < dead.length && dead[r];
        };
    }

    // The graph lives as long as its floor: same level, same hop range, same sim state and altitude. Only the
    // planner thread ever touches it.
    private static volatile FloorGraphs graph;
    private static FloorTiles graphTiles;
    private static volatile Level graphLevel;
    private static volatile double graphRange;
    private static boolean graphSim;
    private static int graphYOffset;

    /** The 6x6 room tiles, each one's click region being etherwarpableInTile's first band. */
    private static final class FloorTiles implements WarpGraph.Tiles {
        final int x0;
        final int z0;
        final int floorY;

        FloorTiles(int x0, int z0, int floorY) {
            this.x0 = x0;
            this.z0 = z0;
            this.floorY = floorY;
        }

        @Override
        public int tileOf(int x, int y, int z) {
            int i = Math.floorDiv(x - x0 + 16, 32);
            int j = Math.floorDiv(z - z0 + 16, 32);
            if (i < 0 || j < 0 || i > 5 || j > 5) {
                return -1;
            }
            // TeleportUtils.etherwarpableInTile: 14 either side of the centre, the centre's y (70) +- 2.
            if (Math.abs(x - (x0 + 32 * i)) > 14 || Math.abs(z - (z0 + 32 * j)) > 14
                    || y < floorY + 1 - 2 || y > floorY + 1 + 2) {
                return -1;
            }
            return j * 6 + i;
        }

        @Override
        public int cellOf(int x, int z) {
            int i = Math.floorDiv(x - x0 + 16, 32);
            int j = Math.floorDiv(z - z0 + 16, 32);
            return i < 0 || j < 0 || i > 5 || j > 5 ? -1 : j * 6 + i;
        }

        @Override
        public int count() {
            return 36;
        }
    }

    /** Planner thread. The floor's graphs, made fresh when the floor changes, with any block changes applied. */
    private static FloorGraphs graphFor(Level level, double range) {
        boolean sim = com.killer560.hub.roomsim.SimState.isActive();
        int off = DungeonLayout.simYOffset();
        if (graph == null || graphLevel != level || graphRange != range || graphSim != sim || graphYOffset != off) {
            BlockPos first = DungeonLayout.cellCenter(0);
            BlockPos last = DungeonLayout.cellCenter(DungeonLayout.GRID * DungeonLayout.GRID - 1);
            int minX = first.getX() - 16;
            int minZ = first.getZ() - 16;
            int maxX = last.getX() + 16;
            int maxZ = last.getZ() + 16;
            int floor = 69 + off;
            int topY = level.getMaxY();
            WarpGraph.LandingRule rule = (g, x, y, z) -> x >= minX && x <= maxX && z >= minZ && z <= maxZ
                    && (!sim || covered(g, x, y, z, floor, topY));
            WarpGraph made = new WarpGraph(range, STAND_OFFSET, BUCKET, rule, floor - 20, floor + 45);
            FloorTiles tiles = new FloorTiles(first.getX(), first.getZ(), floor);
            made.setTiles(tiles);
            made.setFine(EtherwarpPathfinder::doorwayColumn);
            made.partialFrom = PARTIAL_FROM;
            // The quick graph a click uses until the full one is warm (FloorGraphs): same landing rule and tiles,
            // coarser, only the doorways' own centre lines kept whole, cheap aims.
            WarpGraph quick = new WarpGraph(range, STAND_OFFSET, FloorGraphs.QUICK_BUCKET, rule, floor - 20, floor + 45);
            quick.setTiles(tiles);
            quick.setFine(EtherwarpPathfinder::doorLineColumn);
            quick.partialFrom = FloorGraphs.QUICK_PARTIAL_FROM;
            graph = new FloorGraphs(made, quick);
            graphTiles = tiles;
            graphLevel = level;
            graphRange = range;
            graphSim = sim;
            graphYOffset = off;
            LevelEtherGrid.listener = made;   // which tells the quick graph too (WarpGraph.follower)
        }
        LevelEtherGrid.processChanges(level);
        return graph;
    }

    /**
     * Where the graph keeps every landing as its own node rather than one per 3x3: the doorway boxes between
     * neighbouring tiles (3 wide along the seam, 7 deep across it - SimDoors' carve and the real doorways) and
     * the tile centre lines every doorway's axis runs along. Every Catacombs door sits on a seam at a tile's centre
     * line, so the long sights from one room into the next pass through a doorway along that line, and whether
     * one passes is decided by a block either way. On tools/bench/FloorBench (small floors, against every
     * landing with the full aim) this took the extra warps over the reference from 12% to under 5%.
     */
    private static boolean doorwayColumn(int x, int z) {
        BlockPos first = DungeonLayout.cellCenter(0);
        int wx = x - first.getX();
        int wz = z - first.getZ();
        int offX = Math.floorMod(wx + 16, 32) - 16;   // from the nearest tile centre line
        int offZ = Math.floorMod(wz + 16, 32) - 16;
        int seamX = Math.floorMod(wx, 32) - 16;       // from the nearest seam
        int seamZ = Math.floorMod(wz, 32) - 16;
        return offX == 0 || offZ == 0 || (Math.abs(seamX) <= 3 && Math.abs(offZ) <= 1)
                || (Math.abs(seamZ) <= 3 && Math.abs(offX) <= 1);
    }

    /**
     * The quick graph's whole columns ({@link FloorGraphs}): only the line through the middle of each doorway, seven
     * columns deep across the seam, which every door's long sights pass along.
     */
    private static boolean doorLineColumn(int x, int z) {
        BlockPos first = DungeonLayout.cellCenter(0);
        int wx = x - first.getX();
        int wz = z - first.getZ();
        int offX = Math.floorMod(wx + 16, 32) - 16;
        int offZ = Math.floorMod(wz + 16, 32) - 16;
        int seamX = Math.floorMod(wx, 32) - 16;
        int seamZ = Math.floorMod(wz, 32) - 16;
        return (Math.abs(seamX) <= 3 && offZ == 0) || (Math.abs(seamZ) <= 3 && offX == 0);
    }

    /** {@link #coverTest} through any grid (warm-up workers read through their own). */
    private static boolean covered(EtherSearch.Grid grid, int x, int y, int z, int floor, int topY) {
        if (y <= floor + COVER_FLOOR_SLACK) {
            return true;
        }
        int top = Math.min(topY, y + COVER_SCAN);
        for (int yy = y + 3; yy <= top; yy++) {
            if ((grid.flags(x, yy, z) & EtherSearch.AIR) == 0) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------- warming

    private static volatile boolean warmInFlight;
    private static java.util.concurrent.ExecutorService workers;
    private static int workerCount;

    /** Worker threads for warming: a quarter of the cores, one to six. One means the planner thread alone. */
    private static java.util.concurrent.ExecutorService workers() {
        if (workers == null) {
            workerCount = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() / 4));
            if (workerCount > 1) {
                workers = java.util.concurrent.Executors.newFixedThreadPool(workerCount, r -> {
                    Thread t = new Thread(r, "killer560smod-etherwarm");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                });
            }
        }
        return workers;
    }

    /** One warm-up slice: a waiting click gets the planner thread after at most one of these (plus one batch). */
    private static final long WARM_SLICE_NANOS = 10_000_000L;
    /** A warm-up task gives the planner thread back after this long even with no click waiting. */
    private static final long WARM_TASK_NANOS = 250_000_000L;

    /**
     * Client thread, every tick: while he is in a dungeon with the Interactive Map on, keeps one warm-up slice
     * queued on the planner thread until every landing he can reach has its edges - so the click itself only has
     * to search. Stops on its own when the graph is warm; a block change that drops nodes starts it again.
     *
     * @param planner      the Interactive Map's planner thread
     * @param clickWaiting true while a click is waiting for or using the planner thread
     */
    public static void tickWarm(java.util.concurrent.ExecutorService planner,
                                java.util.function.BooleanSupplier clickWaiting, double range) {
        if (warmInFlight || clickWaiting.getAsBoolean()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        FloorGraphs g = graph;
        if (g != null && graphLevel == level && graphRange == range && g.warmDone()
                && !LevelEtherGrid.hasPendingChanges()) {
            return;
        }
        Vec3 p = mc.player.position();
        warmInFlight = true;
        planner.submit(() -> {
            try {
                FloorGraphs gr = graphFor(level, range);
                long t0 = System.nanoTime();
                boolean wasDone = gr.full.warmDone();
                boolean quickWasDone = gr.quick.warmDone();
                boolean more = true;
                while (more && !clickWaiting.getAsBoolean() && System.nanoTime() - t0 < WARM_TASK_NANOS) {
                    more = gr.warm(new LevelEtherGrid(level), () -> new LevelEtherGrid(level), workers(),
                            workerCount, p.x, p.y, p.z, WARM_SLICE_NANOS);
                }
                warmNanos += System.nanoTime() - t0;
                if (!quickWasDone && gr.quick.warmDone() && !gr.full.warmDone()) {
                    LOGGER.info("[Path] quick floor graph warm: {} node(s), {} ms of planner time on {} thread(s)",
                            gr.quick.nodeCount(), ms(warmNanos), workerCount);
                }
                if (gr.full.warmDone() && !wasDone) {
                    LOGGER.info("[Path] floor graph warm: {} node(s), {} ms of planner time on {} thread(s)",
                            gr.full.nodeCount(), ms(warmNanos), workerCount);
                    warmNanos = 0;
                }
            } catch (RuntimeException e) {
                LOGGER.warn("[Path] warm-up failed: {}", e.toString());
            } finally {
                warmInFlight = false;
            }
        });
    }

    private static long warmNanos;

    /**
     * The planner before the floor graph, kept as its fallback: QUOI {@code findDungeonPath}, room-by-room legs
     * via {@link DungeonMapPathfinder}.
     *
     * <p><b>Every refusal says which one it is.</b> killer560 (2026-10-01): "interactive map fails anytime I
     * try to use it on sim", and all the feature printed was "Failed after 671ms" - which is the same message
     * for an unetherwarpable target, a room graph with no route, and a leg search that ran out of time. One
     * line per refusal settles it in a single run instead of a round of guesses.
     */
    private static List<Node> legacyDungeonPath(Vec3 from, BlockPos to, PathConfig cfg, double dist,
                                                DungeonLayout layout) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        long searchStart = System.nanoTime();
        LevelEtherGrid grid = new LevelEtherGrid(level);
        EtherSearch search = new EtherSearch(grid);
        if (!search.etherwarpable(to.getX(), to.getY(), to.getZ())) {
            LOGGER.info("[Path] {} is not etherwarpable - it is not solid, or there is no standing room over"
                    + " it. Nothing searched.", to);
            return null;
        }
        int startRoom = layout.roomAtWorld(from.x, from.z);
        int goalRoom = layout.roomAtWorld(to.getX(), to.getZ());
        if (startRoom >= 0 && startRoom == goalRoom) {
            List<Node> same = sameRoomPath(grid, search, level, from, to, cfg, dist, layout, startRoom,
                    searchStart);
            if (same != null) {
                return same;
            }
        }
        if (startRoom < 0 || goalRoom < 0 || startRoom == goalRoom) {
            List<Node> direct = findPath(from, to, cfg, dist, false, layout);
            if (direct == null) {
                LOGGER.info("[Path] No single-room path from {} to {} (startRoom {}, goalRoom {}) - the"
                        + " warp search found nothing within {} blocks a hop.", BlockPos.containing(from), to,
                        startRoom, goalRoom, dist);
            }
            return direct;
        }
        List<DungeonMapPathfinder.RoomStep> roomPath = DungeonMapPathfinder.findPath(layout, startRoom, goalRoom, false);
        if (roomPath == null) {
            // The usual cause is every door between here and there reading as LOCKED. DungeonLayout decides
            // that by testing whether the block at doorBlock(idx) is air, so a sim whose doorways are carved
            // at a different height than the one it checks locks the whole floor at once.
            LOGGER.info("[Path] No ROOM route from room {} to room {}: every door between them reads as"
                    + " locked or missing. {} door(s) on the grid are currently locked.",
                    startRoom, goalRoom, lockedDoorCount(layout));
            return null;
        }
        long deadline = searchStart + cfg.timeout() * 1_000_000L;
        EtherSearch.CellTest cover = coverTest(grid, level);
        List<EtherSearch.Hop> path = new ArrayList<>();
        EtherSearch.Hop cur = startHop(from);
        path.add(cur);
        int directLegs = 0;
        int searchedLegs = 0;
        int widenedLegs = 0;
        long expanded = 0;
        for (int i = 0; i < roomPath.size(); i++) {
            DungeonMapPathfinder.RoomStep step = roomPath.get(i);
            EtherSearch.Leg leg = leg(cfg, dist, deadline);
            int room = step.room();
            int nextRoom = -1;
            if (step.door() >= 0) {
                BlockPos door = DungeonLayout.doorBlock(step.door());
                // One below the door block, not a literal 68 - the sim shifts the whole floor.
                int gx = door.getX();
                int gy = door.getY() - 1;
                int gz = door.getZ();
                nextRoom = roomPath.get(i + 1).room();
                int next = nextRoom;
                leg.goalX = gx;
                leg.goalY = gy;
                leg.goalZ = gz;
                leg.directRadiusSq = 9;
                leg.isGoal = (x, y, z) -> {
                    int dx = x - gx;
                    int dy = y - gy;
                    int dz = z - gz;
                    return dx * dx + dy * dy + dz * dz <= 9
                            || (layout.roomAtWorld(x, z) == next && Math.abs(y - gy) <= 3);
                };
            } else {
                leg.goalX = to.getX();
                leg.goalY = to.getY();
                leg.goalZ = to.getZ();
                leg.directRadiusSq = 0;
                leg.isGoal = (x, y, z) -> x == to.getX() && y == to.getY() && z == to.getZ();
            }
            // BOUNDED TO THE ROOMS THIS LEG JOINS. A leg from one room into the next has no business landing in
            // a third, and without the bound a leg whose door is hard to see wanders the whole floor before it
            // gives up. If the bounded leg fails it is run once more unbounded, so the bound can only ever make
            // a search faster, never make one fail that used to work.
            int boundA = room;
            int boundB = nextRoom;
            leg.landingOk = (x, y, z) -> {
                int r = layout.roomAtWorld(x, z);
                return (r == boundA || r == boundB) && cover.test(x, y, z);
            };
            List<EtherSearch.Hop> seg = search.searchLeg(cur, leg);
            expanded += search.expanded;
            if (seg == null && System.nanoTime() < deadline) {
                leg.landingOk = cover;
                seg = search.searchLeg(cur, leg);
                expanded += search.expanded;
                widenedLegs++;
            }
            if (seg == null && step.door() < 0 && System.nanoTime() < deadline) {
                // The last leg aims at one block. If that block cannot be reached at all (a chest in a sealed
                // alcove - see sameRoomPath), land as near it as the room allows rather than fail the click.
                int tx = to.getX();
                int ty = to.getY();
                int tz = to.getZ();
                int goalRoomFinal = room;
                leg.directRadiusSq = NEAR_RADIUS * NEAR_RADIUS;
                leg.isGoal = (x, y, z) -> Math.abs(x - tx) <= NEAR_RADIUS && Math.abs(z - tz) <= NEAR_RADIUS
                        && Math.abs(y - ty) <= NEAR_RADIUS && layout.roomAtWorld(x, z) == goalRoomFinal;
                seg = search.searchLeg(cur, leg);
                expanded += search.expanded;
                if (seg != null) {
                    LOGGER.info("[Path] {} itself cannot be reached; the last hop lands within {} block(s) of it",
                            to, NEAR_RADIUS);
                }
            }
            if (seg == null) {
                LOGGER.info("[Path] Room hop {} of {} failed: no warp chain from {} to {},{},{} (room {}, door {})."
                        + " The room route exists; this leg of it does not.", i + 1, roomPath.size(),
                        new BlockPos(cur.bx, cur.by, cur.bz), leg.goalX, leg.goalY, leg.goalZ, room, step.door());
                return null;
            }
            if (search.expanded == 0 && seg.size() > 1) {
                directLegs++;
            } else if (seg.size() > 1) {
                searchedLegs++;
            }
            for (int j = 1; j < seg.size(); j++) {
                path.add(seg.get(j));
            }
            cur = path.get(path.size() - 1);
        }
        if (path.size() < 2) {
            LOGGER.info("[Path] Room route from {} to {} is {} hop(s) long but produced no warps at all.",
                    startRoom, goalRoom, roomPath.size());
            return null;
        }
        long smoothStart = System.nanoTime();
        List<EtherSearch.Hop> smoothed = search.smooth(path, dist, false);
        long end = System.nanoTime();
        // One line a search, so the cost can be read off his log rather than guessed at.
        LOGGER.info("[Path] room by room: {} leg(s): {} direct, {} searched ({} widened, {} node(s) expanded, {} ray(s));"
                        + " {} section(s) filled; legs {} ms, smoothing {} ms, total {} ms, {} warp(s)",
                roomPath.size(), directLegs, searchedLegs, widenedLegs, expanded, search.rays, grid.filled,
                ms(smoothStart - searchStart), ms(end - smoothStart), ms(end - searchStart), smoothed.size());
        return toNodes(smoothed);
    }

    /** How close a same-room fallback landing must be to an unreachable target: blocks across, and up or down. */
    private static final int NEAR_RADIUS = 5;

    /**
     * A target in the room he is standing in: first exactly, kept inside the room; then, if the exact block
     * cannot be reached, the nearest landing within {@link #NEAR_RADIUS} of it, still inside the room.
     *
     * <p>killer560's log (2026-10-04): ten "No single-room path ... the warp search found nothing within 57.0
     * blocks a hop" lines at 670 ms each, for a target 14 blocks away. The target was Tic Tac Toe's chest, which
     * in that capture sits in a walled-off alcove: it is "etherwarpable" (solid, two air above) and no ray from
     * anywhere reaches it, so the unbounded search expanded the whole floor until the deadline - rebuilt from
     * the capture in {@code tools/bench} ({@code -Dttt=}), where the room alone is exhausted after 295 nodes.
     * Bounded to the room it fails in a few milliseconds, and the near search then puts him at the alcove wall.
     *
     * <p>Returns null when neither finds anything, and the caller then runs the old unbounded exact search, so a
     * target that could only be reached by leaving the room and coming back still works as it did.
     */
    private static List<Node> sameRoomPath(LevelEtherGrid grid, EtherSearch search, Level level, Vec3 from,
                                           BlockPos to, PathConfig cfg, double dist,
                                           DungeonLayout layout, int room, long searchStart) {
        long deadline = searchStart + cfg.timeout() * 1_000_000L;
        EtherSearch.CellTest cover = coverTest(grid, level);
        EtherSearch.CellTest inRoom = (x, y, z) -> layout.roomAtWorld(x, z) == room && cover.test(x, y, z);
        int tx = to.getX();
        int ty = to.getY();
        int tz = to.getZ();
        for (int pass = 0; pass < 2; pass++) {
            EtherSearch.Leg leg = leg(cfg, dist, deadline);
            leg.goalX = tx;
            leg.goalY = ty;
            leg.goalZ = tz;
            leg.landingOk = inRoom;
            if (pass == 0) {
                leg.directRadiusSq = 0;
                leg.isGoal = (x, y, z) -> x == tx && y == ty && z == tz;
            } else {
                leg.directRadiusSq = NEAR_RADIUS * NEAR_RADIUS;
                leg.isGoal = (x, y, z) -> Math.abs(x - tx) <= NEAR_RADIUS && Math.abs(z - tz) <= NEAR_RADIUS
                        && Math.abs(y - ty) <= NEAR_RADIUS && layout.roomAtWorld(x, z) == room;
            }
            List<EtherSearch.Hop> path = search.searchLeg(startHop(from), leg);
            if (path != null && path.size() >= 2) {
                List<EtherSearch.Hop> smoothed = search.smooth(path, dist, false);
                EtherSearch.Hop last = path.get(path.size() - 1);
                LOGGER.info("[Path] same room: {} in {} ms, {} warp(s){}", pass == 0 ? "exact" : "near",
                        ms(System.nanoTime() - searchStart), smoothed.size(),
                        pass == 0 ? "" : " - " + to + " itself cannot be reached, landing on " + last.bx + ","
                                + last.by + "," + last.bz);
                return toNodes(smoothed);
            }
            if (System.nanoTime() > deadline) {
                break;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------- helpers

    private static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.2f", nanos / 1e6);
    }

    /** Feet height above the top of the block an etherwarp lands on. */
    public static final double STAND_OFFSET = 1.05;

    private static EtherSearch.Leg leg(PathConfig cfg, double dist, long deadline) {
        EtherSearch.Leg leg = new EtherSearch.Leg();
        leg.fan = EtherSearch.fan(dist, cfg.yawStep(), cfg.pitchStep());
        leg.hWeight = cfg.hWeight();
        // Block top + 1.05: QUOI's figure for where Hypixel's etherwarp lands the feet, and the dungeon sim's
        // server lands there too (SimAbilities.ETHERWARP_LANDING_OFFSET). The sim used to land at + 1.0 and
        // planned with that; one value now.
        leg.standOffset = STAND_OFFSET;
        leg.deadlineNanos = deadline;
        return leg;
    }

    private static EtherSearch.Hop startHop(Vec3 from) {
        BlockPos p = BlockPos.containing(from);
        return new EtherSearch.Hop(from.x, from.y, from.z, p.getX(), p.getY(), p.getZ(), 0f, 0f);
    }

    private static List<Node> toNodes(List<EtherSearch.Hop> hops) {
        List<Node> out = new ArrayList<>(hops.size());
        for (EtherSearch.Hop h : hops) {
            out.add(new Node(h.x, h.y, h.z, new BlockPos(h.bx, h.by, h.bz), h.yaw, h.pitch));
        }
        return out;
    }

    /** How far above a landing the sim's "is this under the dungeon's rock" test looks - see underCover. */
    private static final int COVER_SCAN = 64;
    private static final int COVER_FLOOR_SLACK = 6;

    /**
     * {@link TeleportUtils#underCover} read through the grid: outside the sim always true; in it, a landing well
     * above floor height must have something other than air above it, or it is the roof of a pasted room.
     */
    private static EtherSearch.CellTest coverTest(EtherSearch.Grid grid, Level level) {
        if (!com.killer560.hub.roomsim.SimState.isActive()) {
            return (x, y, z) -> true;
        }
        int floor = 69 + DungeonLayout.simYOffset();
        int maxY = level.getMaxY();
        return (x, y, z) -> {
            if (y <= floor + COVER_FLOOR_SLACK) {
                return true;
            }
            int top = Math.min(maxY, y + COVER_SCAN);
            for (int yy = y + 3; yy <= top; yy++) {
                if ((grid.flags(x, yy, z) & EtherSearch.AIR) == 0) {
                    return true;
                }
            }
            return false;
        };
    }

    /** How many of the grid's doors currently read as locked - the number that makes a "no room route"
     *  line mean something. */
    private static int lockedDoorCount(DungeonLayout layout) {
        int locked = 0;
        for (int idx = 0; idx < DungeonLayout.GRID * DungeonLayout.GRID; idx++) {
            if (layout.doorType(idx) != DungeonLayout.DOOR_NONE && layout.isLocked(idx)) {
                locked++;
            }
        }
        return locked;
    }
}

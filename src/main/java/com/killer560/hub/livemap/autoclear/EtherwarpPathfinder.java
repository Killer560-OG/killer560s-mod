package com.killer560.hub.livemap.autoclear;

import com.killer560.hub.livemap.DungeonLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Etherwarp paths for the Interactive Map and the Etherwarp Hopper: QUOI's {@code EtherwarpPathfinder} in
 * behaviour - A* whose moves are etherwarp rays from a fixed yaw/pitch fan, then {@code smoothPath} merging hops
 * that one warp can skip - and, for a dungeon, one search per room along {@link DungeonMapPathfinder}'s room
 * path, each targeting the door with a 3-block radius (or any landing inside the next room within 3 blocks of
 * door height).
 *
 * <p>killer560 (2026-10-04): "the found path needs to be faster, like 1-2 ms every time it has to calculate."
 * The search itself now lives in {@link EtherSearch} over a byte-per-block {@link LevelEtherGrid}; this class
 * only turns a room route into legs, bounds each leg to the two rooms it joins, and adapts the result to the
 * {@link Node} list everything downstream already reads. His own log before the change (Map Logger,
 * 2026-10-01, 19 successful clicks): median 6 ms, worst 153 ms, and eight "Failed after ~675ms" timeouts. On
 * {@code tools/bench/EtherSearchBench} - a 6x6 floor of the shipped 1x1 captures, 2,000 clicks of 1 to 7 rooms -
 * the new search, through the same section-table grid the game uses, takes a mean of 0.47 ms, median 0.32,
 * p90 0.91, p99 3.5 and worst 10 ms with sections already cached, and a mean of 0.6 ms with every section filled
 * fresh. See docs/SIM.md for the method and what it does not cover.
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
    public static List<Node> findPath(Vec3 from, BlockPos to, PathConfig cfg, double dist, boolean offset, boolean withLast,
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
        EtherSearch.Leg leg = leg(cfg, dist, offset, deadline);
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

    /**
     * QUOI {@code findDungeonPath}: room-by-room legs via {@link DungeonMapPathfinder}.
     *
     * <p><b>Every refusal says which one it is.</b> killer560 (2026-10-01): "interactive map fails anytime I
     * try to use it on sim", and all the feature printed was "Failed after 671ms" - which is the same message
     * for an unetherwarpable target, a room graph with no route, and a leg search that ran out of time. One
     * line per refusal settles it in a single run instead of a round of guesses.
     */
    public static List<Node> findDungeonPath(Vec3 from, BlockPos to, PathConfig cfg, double dist, boolean offset,
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
        if (startRoom < 0 || goalRoom < 0 || startRoom == goalRoom) {
            List<Node> direct = findPath(from, to, cfg, dist, offset, false, layout);
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
            EtherSearch.Leg leg = leg(cfg, dist, offset, deadline);
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
        LOGGER.info("[Path] {} leg(s): {} direct, {} searched ({} widened, {} node(s) expanded, {} ray(s));"
                        + " {} section(s) filled; legs {} ms, smoothing {} ms, total {} ms, {} warp(s)",
                roomPath.size(), directLegs, searchedLegs, widenedLegs, expanded, search.rays, grid.filled,
                ms(smoothStart - searchStart), ms(end - smoothStart), ms(end - searchStart), smoothed.size());
        return toNodes(smoothed);
    }

    // ------------------------------------------------------------------------------------------- helpers

    private static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.2f", nanos / 1e6);
    }

    private static EtherSearch.Leg leg(PathConfig cfg, double dist, boolean offset, long deadline) {
        EtherSearch.Leg leg = new EtherSearch.Leg();
        leg.fan = EtherSearch.fan(dist, cfg.yawStep(), cfg.pitchStep());
        leg.hWeight = cfg.hWeight();
        leg.standOffset = offset ? 1.05 : 1.0;
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

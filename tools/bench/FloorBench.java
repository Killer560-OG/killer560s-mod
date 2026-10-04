import com.killer560.hub.livemap.autoclear.EtherSearch;
import com.killer560.hub.livemap.autoclear.WarpGraph;
import com.killer560.hub.roomsim.RoomDoors;
import com.killer560.hub.roomsim.RoomLibrary;
import com.killer560.hub.roomsim.SimFloorLayout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * The Interactive Map's etherwarp planner on WHOLE FLOORS laid out the way the sim lays them out, without a game.
 *
 * <p>See tools/bench/floor.sh. Each floor is {@code SimFloorLayout.generate} (the sim's real layout code, as in
 * tools/layoutsim) over the shipped captures, pasted with {@code RoomPlacer}'s transform at the real grid
 * coordinates (Hypixel heights), every link carved as {@code SimDoors.carveDoorway} does and every unlinked
 * measured doorway sealed as {@code SimDoors.sealDoorway} does. Block flags come from palette NAMES
 * ({@code EtherSearchBench.flagsForName}). Landings obey the sim's roof rule ("under cover") and the dungeon's
 * bounds, as {@code EtherwarpPathfinder} gives them in the sim.
 *
 * <p>A click is a start on a spot he can walk to and either an EXACT block (a door, an override spot, a puzzle)
 * or a TILE (a plain map click: any landing in the clicked tile at floor height). Three planners:
 * <ul>
 *   <li><b>old</b>: the room-by-room legs of EtherwarpPathfinder before 2026-10-04 (room route, one bounded
 *       weighted-A* leg per door, widened retry, near fallback, smoothing). A tile click is planned to the block
 *       {@code TeleportUtils.etherwarpableInTile} picked (nearest standable to him), as the game did.</li>
 *   <li><b>new</b>: {@link WarpGraph}, bucket 3 (the game's setting), timed cold (a fresh graph per click),
 *       lazily warm (one graph, clicks in sequence) and warm (the whole floor expanded first).</li>
 *   <li><b>ref</b>: {@link WarpGraph} with bucket 1 - breadth first over EVERY landing block, every fan hit an
 *       edge, every node in range tried against an exact goal. The fewest warps the fan allows; "new" is
 *       compared against it click by click.</li>
 * </ul>
 * Every returned path is replayed ray by ray from the start; a hop that does not land where the next one starts,
 * or a path that does not end on its goal, is counted as INVALID.
 *
 * <p>-Dfloors=N (5) -Dclicks=N per floor (300) -Drefclicks=N (100) -Dseed=N (560) -Dbucket=N (3)
 * -Dyaw=6 -Dpitch=7 -Drange=60 -Dold=false (skip the old planner) -Dcheck=FILE (regression: fail on worse).
 */
public final class FloorBench {

    static final int START = -185;
    static final int OFF = 201;            // bench x/z = world x/z + OFF
    static final int SIZE = 198;
    static final int MAX_Y = 200;
    static final int FLOOR_Y = 69;
    static final int AIRF = EtherSearch.PASSABLE | EtherSearch.AIR;
    static final int SOLID = 1 << EtherSearch.TOP_SHIFT;

    // ------------------------------------------------------------------------------------------- the floor

    static final class Floor {
        final byte[] g = new byte[SIZE * SIZE * MAX_Y];
        final Set<Long> chests = new HashSet<>();
        final int[] owner = new int[36];
        final List<SimFloorLayout.Placement> rooms;
        final List<int[]> doorSeams = new ArrayList<>();   // {wx, wz, alongX, roomA, roomB}
        final List<List<Integer>> adj = new ArrayList<>();
        final List<List<int[]>> spots = new ArrayList<>();  // per room, walkable standable blocks (bench coords)
        int carved;
        int sealed;

        Floor(SimFloorLayout.Floor f) {
            Arrays.fill(g, (byte) AIRF);
            Arrays.fill(owner, -1);
            rooms = f.rooms();
            for (int i = 0; i < rooms.size(); i++) {
                SimFloorLayout.Placement p = rooms.get(i);
                adj.add(new ArrayList<>());
                spots.add(new ArrayList<>());
                for (int a = 0; a < p.cellsX(); a++) {
                    for (int b = 0; b < p.cellsZ(); b++) {
                        owner[(p.originZ() + b) * 6 + p.originX() + a] = i;
                    }
                }
                paste(p);
            }
            Set<Long> linked = new HashSet<>();
            for (SimFloorLayout.Link l : f.links()) {
                int ra = owner[l.aZ() * 6 + l.aX()];
                int rb = owner[l.bZ() * 6 + l.bX()];
                if (ra < 0 || rb < 0 || ra == rb) {
                    continue;
                }
                int dgx = l.aX() + l.bX();
                int dgz = l.aZ() + l.bZ();
                linked.add((long) dgx * 100 + dgz);
                int wx = START + dgx * 16;
                int wz = START + dgz * 16;
                boolean alongX = l.aX() != l.bX();
                carve(wx, wz, alongX);
                carved++;
                doorSeams.add(new int[]{wx, wz, alongX ? 1 : 0, ra, rb});
                if (!adj.get(ra).contains(rb)) {
                    adj.get(ra).add(rb);
                    adj.get(rb).add(ra);
                }
            }
            for (SimFloorLayout.Placement p : rooms) {
                RoomDoors.Mask mask = RoomDoors.of(p.name());
                if (mask == null) {
                    continue;
                }
                mask = RoomDoors.rotate(mask, p.rotation());
                for (int[] dc : RoomDoors.doorCells(mask, p.originX(), p.originZ())) {
                    int dgx = dc[0] * 2 + RoomDoors.DX[dc[2]];
                    int dgz = dc[1] * 2 + RoomDoors.DZ[dc[2]];
                    if (linked.contains((long) dgx * 100 + dgz)) {
                        continue;
                    }
                    seal(START + dgx * 16, START + dgz * 16, RoomDoors.DX[dc[2]] != 0);
                    sealed++;
                }
            }
            findSpots();
        }

        /** Rooms the door graph does not join to {@code from} - clicks into them fail for every planner. */
        int unreachableFrom(int from) {
            if (rooms.isEmpty()) {
                return 0;
            }
            boolean[] seen = new boolean[rooms.size()];
            ArrayDeque<Integer> q = new ArrayDeque<>();
            seen[from] = true;
            q.add(from);
            int n = 1;
            while (!q.isEmpty()) {
                for (int nb : adj.get(q.poll())) {
                    if (!seen[nb]) {
                        seen[nb] = true;
                        n++;
                        q.add(nb);
                    }
                }
            }
            return rooms.size() - n;
        }

        int idx(int x, int y, int z) {
            return (y * SIZE + z) * SIZE + x;
        }

        int flags(int x, int y, int z) {
            if (x < 0 || z < 0 || x >= SIZE || z >= SIZE || y < 0 || y >= MAX_Y) {
                return AIRF;
            }
            return g[idx(x, y, z)] & 0xFF;
        }

        void set(int wx, int y, int wz, int f) {
            int x = wx + OFF;
            int z = wz + OFF;
            if (x < 0 || z < 0 || x >= SIZE || z >= SIZE || y < 0 || y >= MAX_Y) {
                return;
            }
            g[idx(x, y, z)] = (byte) f;
        }

        int wflags(int wx, int y, int wz) {
            return flags(wx + OFF, y, wz + OFF);
        }

        void paste(SimFloorLayout.Placement p) {
            RoomLibrary.Room room = RoomLibrary.ROOMS.get(p.name());
            int[] pal = new int[room.palette.size()];
            boolean[] chest = new boolean[pal.length];
            for (int i = 0; i < pal.length; i++) {
                pal[i] = EtherSearchBench.flagsForName(room.palette.get(i));
                chest[i] = room.palette.get(i).contains("chest");
            }
            int cx = START + p.originX() * 32;
            int cz = START + p.originZ() * 32;
            int x0 = cx - RoomLibrary.TILE / 2 - room.margin;
            int z0 = cz - RoomLibrary.TILE / 2 - room.margin;
            for (int y = room.minY; y <= room.maxY; y++) {
                for (int x = 0; x < room.sizeX; x++) {
                    for (int z = 0; z < room.sizeZ; z++) {
                        short pi = room.at(x, y, z);
                        if (pi < 0 || pi >= pal.length) {
                            continue;
                        }
                        int[] l = rotateLocal(x, z, room.sizeX, room.sizeZ, p.rotation());
                        int wx = x0 + l[0];
                        int wz = z0 + l[1];
                        set(wx, y, wz, pal[pi]);
                        long key = EtherSearch.pack(wx, y, wz);
                        if (chest[pi]) {
                            chests.add(key);
                        } else {
                            chests.remove(key);
                        }
                    }
                }
            }
        }

        static int[] rotateLocal(int x, int z, int sizeX, int sizeZ, int degrees) {
            return switch (degrees) {
                case 0 -> new int[]{x, z};
                case 90 -> new int[]{sizeZ - 1 - z, x};
                case 180 -> new int[]{sizeX - 1 - x, sizeZ - 1 - z};
                case 270 -> new int[]{z, sizeX - 1 - x};
                default -> throw new IllegalArgumentException("rotation " + degrees);
            };
        }

        /** SimDoors.findFloor: down from y72 to y58, the first non-air, non-chest block with two air above. */
        int findFloor(int wx, int wz) {
            for (int y = 72; y >= 58; y--) {
                int f = wflags(wx, y, wz);
                if ((f & EtherSearch.AIR) != 0 || chests.contains(EtherSearch.pack(wx, y, wz))) {
                    continue;
                }
                if ((wflags(wx, y + 1, wz) & EtherSearch.AIR) != 0 && (wflags(wx, y + 2, wz) & EtherSearch.AIR) != 0) {
                    return y + 1;
                }
            }
            return 69;
        }

        /** SimDoors.carveDoorway for a normal door: 3 wide, 4 high, 7 deep of air, floored where it is open. */
        void carve(int wx, int wz, boolean alongX) {
            int floorY = findFloor(wx, wz);
            for (int d = -3; d <= 3; d++) {
                for (int w = -1; w <= 1; w++) {
                    int x = alongX ? wx + d : wx + w;
                    int z = alongX ? wz + w : wz + d;
                    for (int y = floorY; y < floorY + 4; y++) {
                        set(x, y, z, AIRF);
                        chests.remove(EtherSearch.pack(x, y, z));
                    }
                }
            }
            for (int d = -3; d <= 3; d++) {
                for (int w = -1; w <= 1; w++) {
                    int x = alongX ? wx + d : wx + w;
                    int z = alongX ? wz + w : wz + d;
                    if ((wflags(x, floorY - 1, z) & EtherSearch.PASSABLE) != 0) {
                        set(x, floorY - 1, z, SOLID);
                    }
                }
            }
        }

        /** SimDoors.sealDoorway: a 3x3 plug, floor - 1 to floor + 4, only where it is air. */
        void seal(int wx, int wz, boolean alongX) {
            int floorY = findFloor(wx, wz);
            for (int d = -1; d <= 1; d++) {
                for (int w = -1; w <= 1; w++) {
                    for (int y = floorY - 1; y < floorY + 5; y++) {
                        int x = alongX ? wx + d : wx + w;
                        int z = alongX ? wz + w : wz + d;
                        if ((wflags(x, y, z) & EtherSearch.AIR) != 0) {
                            set(x, y, z, SOLID);
                        }
                    }
                }
            }
        }

        /** DungeonLayout.roomAtWorld, in bench coordinates. */
        int roomAt(int bx, int bz) {
            int wx = bx - OFF;
            int wz = bz - OFF;
            int gx = Math.max(0, Math.min(5, (int) Math.round((wx - START) / 32.0)));
            int gz = Math.max(0, Math.min(5, (int) Math.round((wz - START) / 32.0)));
            return owner[gz * 6 + gx];
        }

        int cellAt(int bx, int bz) {
            int gx = Math.max(0, Math.min(5, (int) Math.round((bx - OFF - START) / 32.0)));
            int gz = Math.max(0, Math.min(5, (int) Math.round((bz - OFF - START) / 32.0)));
            return gz * 6 + gx;
        }

        boolean etherwarpable(int x, int y, int z) {
            int f = flags(x, y, z);
            if ((f & EtherSearch.PASSABLE) != 0) {
                return false;
            }
            int base = y + Math.max(1, (f >> EtherSearch.TOP_SHIFT) & 3);
            int feet = flags(x, base, z);
            int head = flags(x, base + 1, z);
            return (feet & EtherSearch.PASSABLE) != 0 && (feet & EtherSearch.BLOCKS_FEET) == 0
                    && (head & EtherSearch.PASSABLE) != 0 && (head & EtherSearch.BLOCKS_FEET) == 0;
        }

        /** EtherwarpPathfinder.coverTest + the dungeon's bounds: what the game passes as "may land here". */
        boolean landingOk(int x, int y, int z) {
            if (x < 1 || z < 1 || x >= SIZE - 1 || z >= SIZE - 1) {
                return false;
            }
            if (y <= FLOOR_Y + 6) {
                return true;
            }
            int top = Math.min(MAX_Y - 1, y + 64);
            for (int yy = y + 3; yy <= top; yy++) {
                if ((flags(x, yy, z) & EtherSearch.AIR) == 0) {
                    return true;
                }
            }
            return false;
        }

        /** Every standable block he can WALK to from a carved doorway (one block of step up or down). */
        void findSpots() {
            Set<Long> seen = new HashSet<>();
            ArrayDeque<int[]> q = new ArrayDeque<>();
            for (int[] d : doorSeams) {
                int x = d[0] + OFF;
                int z = d[1] + OFF;
                for (int y = 72; y >= 60; y--) {
                    if (etherwarpable(x, y, z) && landingOk(x, y, z) && seen.add(EtherSearch.pack(x, y, z))) {
                        q.add(new int[]{x, y, z});
                        break;
                    }
                }
            }
            while (!q.isEmpty()) {
                int[] c = q.poll();
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int x = c[0] + d[0];
                    int z = c[2] + d[1];
                    for (int y = c[1] + 1; y >= c[1] - 1; y--) {
                        if (etherwarpable(x, y, z)) {
                            if (landingOk(x, y, z) && seen.add(EtherSearch.pack(x, y, z))) {
                                q.add(new int[]{x, y, z});
                            }
                            break;
                        }
                    }
                }
            }
            for (long k : seen) {
                int x = WarpGraph.unpackX(k);
                int y = WarpGraph.unpackY(k);
                int z = WarpGraph.unpackZ(k);
                int r = roomAt(x, z);
                if (r >= 0 && (flags(x, y, z) & EtherSearch.BLACKLIST) == 0) {
                    spots.get(r).add(new int[]{x, y, z});
                }
            }
            for (List<int[]> l : spots) {
                l.sort((a, b) -> a[0] != b[0] ? a[0] - b[0] : a[1] != b[1] ? a[1] - b[1] : a[2] - b[2]);
            }
        }
    }

    // ------------------------------------------------------------------------------------------- clicks

    static final class Click {
        int[] start;
        int goalRoom;
        int[] exact;        // exact goal (bench coords), or for a tile click the block the OLD planner was given
        int tileCell = -1;  // tile click: the 6x6 cell
        boolean tile;
    }

    static EtherSearch.CellTest tileRegion(Floor f, int cell) {
        int cx = START + (cell % 6) * 32 + OFF;
        int cz = START + (cell / 6) * 32 + OFF;
        return (x, y, z) -> Math.abs(x - cx) <= 14 && Math.abs(z - cz) <= 14 && y >= 68 && y <= 72;
    }

    /** TeleportUtils.etherwarpableInTile: band 2 then 8 around y70, nearest to him. */
    static int[] inTile(Floor f, int cell, int[] from) {
        int cx = START + (cell % 6) * 32 + OFF;
        int cz = START + (cell / 6) * 32 + OFF;
        for (int band : new int[]{2, 8}) {
            int[] best = null;
            double bd = Double.MAX_VALUE;
            for (int dy = band; dy >= -band; dy--) {
                for (int dx = -14; dx <= 14; dx++) {
                    for (int dz = -14; dz <= 14; dz++) {
                        int x = cx + dx;
                        int y = 70 + dy;
                        int z = cz + dz;
                        if (!f.etherwarpable(x, y, z) || !f.landingOk(x, y, z)) {
                            continue;
                        }
                        double ddx = from[0] + 0.5 - (x + 0.5);
                        double ddy = from[1] + 1.0 - (y + 1.0);
                        double ddz = from[2] + 0.5 - (z + 0.5);
                        double d = ddx * ddx + ddy * ddy + ddz * ddz;
                        if (d < bd) {
                            bd = d;
                            best = new int[]{x, y, z};
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    static final int SMALL_W = Integer.getInteger("smallw", 0);
    static final int SMALL_H = Integer.getInteger("smallh", 0);

    /** Only the rooms wholly inside a random SMALL_W x SMALL_H window of cells, and the links between them. */
    static SimFloorLayout.Floor window(SimFloorLayout.Floor f, Random rng) {
        SimFloorLayout.Floor best = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            SimFloorLayout.Floor w = window(f, rng.nextInt(6 - SMALL_W + 1), rng.nextInt(6 - SMALL_H + 1));
            if (best == null || w.links().size() > best.links().size()) {
                best = w;
            }
        }
        return best;
    }

    static SimFloorLayout.Floor window(SimFloorLayout.Floor f, int wx, int wz) {
        List<SimFloorLayout.Placement> keep = new ArrayList<>();
        for (SimFloorLayout.Placement p : f.rooms()) {
            if (p.originX() >= wx && p.originZ() >= wz && p.originX() + p.cellsX() <= wx + SMALL_W
                    && p.originZ() + p.cellsZ() <= wz + SMALL_H) {
                keep.add(p);
            }
        }
        List<SimFloorLayout.Link> links = new ArrayList<>();
        for (SimFloorLayout.Link l : f.links()) {
            boolean a = l.aX() >= wx && l.aX() < wx + SMALL_W && l.aZ() >= wz && l.aZ() < wz + SMALL_H;
            boolean b = l.bX() >= wx && l.bX() < wx + SMALL_W && l.bZ() >= wz && l.bZ() < wz + SMALL_H;
            if (a && b && inKept(keep, l.aX(), l.aZ()) && inKept(keep, l.bX(), l.bZ())) {
                links.add(l);
            }
        }
        // No path to blood in a cut-out window: the bench only needs the rooms and their links.
        return new SimFloorLayout.Floor(keep, links, List.of(), 0, List.of());
    }

    static boolean inKept(List<SimFloorLayout.Placement> keep, int cx, int cz) {
        for (SimFloorLayout.Placement p : keep) {
            if (cx >= p.originX() && cx < p.originX() + p.cellsX() && cz >= p.originZ() && cz < p.originZ() + p.cellsZ()) {
                return true;
            }
        }
        return false;
    }

    /**
     * How often the cheap aim the graph's edges use ("refuse when the line to the top centre is blocked more
     * than two blocks short") says no where the full eighteen-point aim says yes: random standable pairs within
     * reach.
     */
    static void aimCheck(Floor f, EtherSearch.Grid grid, Random rng, int n) {
        List<int[]> all = new ArrayList<>();
        for (List<int[]> l : f.spots) {
            all.addAll(l);
        }
        EtherSearch full = new EtherSearch(grid);
        WarpGraph probeGraph = graph(f, 1);
        int pairs = 0;
        int fullYes = 0;
        int fastYes = 0;
        int missed = 0;
        while (pairs < n) {
            int[] a = all.get(rng.nextInt(all.size()));
            int[] b = all.get(rng.nextInt(all.size()));
            double ex = a[0] + 0.5;
            double ey = a[1] + 1.05 + EtherSearch.SNEAK_EYE;
            double ez = a[2] + 0.5;
            double dx = b[0] + 0.5 - ex;
            double dy = b[1] + 0.5 - ey;
            double dz = b[2] + 0.5 - ez;
            if (dx * dx + dy * dy + dz * dz > 61 * 61 || (a[0] == b[0] && a[2] == b[2])) {
                continue;
            }
            pairs++;
            boolean yes = full.aim(ex, ey, ez, b[0], b[1], b[2], RANGE);
            boolean fast = probeGraph.fastAimForBench(grid, ex, ey, ez, b[0], b[1], b[2]);
            fullYes += yes ? 1 : 0;
            fastYes += fast ? 1 : 0;
            if (yes && !fast) {
                missed++;
            }
        }
        System.out.printf(Locale.ROOT, "  aim check: %d pairs within reach, full aim lands on %d, fast aim on %d,"
                + " full-yes/fast-no %d (%.2f%% of full-yes)%n", pairs, fullYes, fastYes, missed,
                100.0 * missed / Math.max(1, fullYes));
    }

    static List<Click> clicks(Floor f, Random rng, int n) {
        List<Integer> withSpots = new ArrayList<>();
        for (int r = 0; r < f.rooms.size(); r++) {
            if (!f.spots.get(r).isEmpty()) {
                withSpots.add(r);
            }
        }
        List<Click> out = new ArrayList<>();
        while (out.size() < n && !withSpots.isEmpty()) {
            int sr = withSpots.get(rng.nextInt(withSpots.size()));
            int gr = withSpots.get(rng.nextInt(withSpots.size()));
            Click c = new Click();
            c.start = f.spots.get(sr).get(rng.nextInt(f.spots.get(sr).size()));
            c.goalRoom = gr;
            c.tile = rng.nextBoolean();
            if (c.tile) {
                List<Integer> cells = new ArrayList<>();
                for (int i = 0; i < 36; i++) {
                    if (f.owner[i] == gr) {
                        cells.add(i);
                    }
                }
                c.tileCell = cells.get(rng.nextInt(cells.size()));
                c.exact = inTile(f, c.tileCell, c.start);
                if (c.exact == null) {
                    continue;
                }
            } else {
                c.exact = f.spots.get(gr).get(rng.nextInt(f.spots.get(gr).size()));
            }
            if (c.exact[0] == c.start[0] && c.exact[2] == c.start[2]) {
                continue;
            }
            out.add(c);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------- planners

    static double RANGE = 60.0;
    static final double PARTIAL = Double.parseDouble(System.getProperty("partial", "0.6"));
    static final int THREADS = Integer.getInteger("threads", 3);
    static final java.util.concurrent.ExecutorService WORKERS = java.util.concurrent.Executors.newFixedThreadPool(
            Math.max(1, THREADS), r -> {
                Thread t = new Thread(r, "warm");
                t.setDaemon(true);
                return t;
            });
    static EtherSearch.Fan FAN;

    static EtherSearch.Hop startHop(int[] s) {
        return new EtherSearch.Hop(s[0] + 0.5, s[1] + 1.0, s[2] + 0.5, s[0], s[1] + 1, s[2], 0, 0);
    }

    static EtherSearch.Leg leg(long deadline) {
        EtherSearch.Leg leg = new EtherSearch.Leg();
        leg.fan = FAN;
        leg.hWeight = 6.7;
        leg.standOffset = 1.05;
        leg.deadlineNanos = deadline;
        return leg;
    }

    /** The planner as it was: EtherwarpPathfinder.findDungeonPath before 2026-10-04's rewrite. */
    static List<EtherSearch.Hop> oldPlan(Floor f, EtherSearch search, int[] s, int[] to) {
        long deadline = System.nanoTime() + 670_000_000L;
        EtherSearch.CellTest cover = f::landingOk;
        int startRoom = f.roomAt(s[0], s[2]);
        int goalRoom = f.roomAt(to[0], to[2]);
        int tx = to[0];
        int ty = to[1];
        int tz = to[2];
        if (startRoom >= 0 && startRoom == goalRoom) {
            int room = startRoom;
            for (int pass = 0; pass < 2; pass++) {
                EtherSearch.Leg leg = leg(deadline);
                leg.goalX = tx;
                leg.goalY = ty;
                leg.goalZ = tz;
                leg.landingOk = (x, y, z) -> f.roomAt(x, z) == room && cover.test(x, y, z);
                if (pass == 0) {
                    leg.directRadiusSq = 0;
                    leg.isGoal = (x, y, z) -> x == tx && y == ty && z == tz;
                } else {
                    leg.directRadiusSq = 25;
                    leg.isGoal = (x, y, z) -> Math.abs(x - tx) <= 5 && Math.abs(z - tz) <= 5 && Math.abs(y - ty) <= 5
                            && f.roomAt(x, z) == room;
                }
                List<EtherSearch.Hop> path = search.searchLeg(startHop(s), leg);
                if (path != null && path.size() >= 2) {
                    return search.smooth(path, RANGE, false);
                }
            }
            EtherSearch.Leg leg = leg(deadline);
            leg.goalX = tx;
            leg.goalY = ty;
            leg.goalZ = tz;
            leg.isGoal = (x, y, z) -> x == tx && y == ty && z == tz;
            leg.landingOk = cover;
            List<EtherSearch.Hop> path = search.searchLeg(startHop(s), leg);
            return path == null || path.size() < 2 ? null : search.smooth(path, RANGE, false);
        }
        // Room route: fewest rooms (DungeonMapPathfinder is A* with unit steps).
        int[] prev = new int[f.rooms.size()];
        Arrays.fill(prev, -2);
        prev[startRoom] = -1;
        ArrayDeque<Integer> q = new ArrayDeque<>(List.of(startRoom));
        while (!q.isEmpty()) {
            int r = q.poll();
            for (int nb : f.adj.get(r)) {
                if (prev[nb] == -2) {
                    prev[nb] = r;
                    q.add(nb);
                }
            }
        }
        if (prev[goalRoom] == -2) {
            return null;
        }
        List<Integer> route = new ArrayList<>();
        for (int r = goalRoom; r >= 0; r = prev[r]) {
            route.add(0, r);
        }
        List<EtherSearch.Hop> path = new ArrayList<>();
        EtherSearch.Hop cur = startHop(s);
        path.add(cur);
        for (int i = 0; i < route.size(); i++) {
            int room = route.get(i);
            int nextRoom = i + 1 < route.size() ? route.get(i + 1) : -1;
            EtherSearch.Leg leg = leg(deadline);
            if (nextRoom >= 0) {
                int[] seam = null;
                for (int[] d : f.doorSeams) {
                    if ((d[3] == room && d[4] == nextRoom) || (d[4] == room && d[3] == nextRoom)) {
                        seam = d;
                        break;
                    }
                }
                int gx = seam[0] + OFF;
                int gy = 68;
                int gz = seam[1] + OFF;
                int next = nextRoom;
                leg.goalX = gx;
                leg.goalY = gy;
                leg.goalZ = gz;
                leg.directRadiusSq = 9;
                leg.isGoal = (x, y, z) -> {
                    int dx = x - gx;
                    int dy = y - gy;
                    int dz = z - gz;
                    return dx * dx + dy * dy + dz * dz <= 9 || (f.roomAt(x, z) == next && Math.abs(y - gy) <= 3);
                };
            } else {
                leg.goalX = tx;
                leg.goalY = ty;
                leg.goalZ = tz;
                leg.directRadiusSq = 0;
                leg.isGoal = (x, y, z) -> x == tx && y == ty && z == tz;
            }
            int a = room;
            int b = nextRoom;
            leg.landingOk = (x, y, z) -> {
                int r = f.roomAt(x, z);
                return (r == a || r == b) && cover.test(x, y, z);
            };
            List<EtherSearch.Hop> seg = search.searchLeg(cur, leg);
            if (seg == null && System.nanoTime() < deadline) {
                leg.landingOk = cover;
                seg = search.searchLeg(cur, leg);
            }
            if (seg == null && nextRoom < 0 && System.nanoTime() < deadline) {
                int gr = room;
                leg.directRadiusSq = 25;
                leg.isGoal = (x, y, z) -> Math.abs(x - tx) <= 5 && Math.abs(z - tz) <= 5 && Math.abs(y - ty) <= 5
                        && f.roomAt(x, z) == gr;
                seg = search.searchLeg(cur, leg);
            }
            if (seg == null) {
                return null;
            }
            for (int j = 1; j < seg.size(); j++) {
                path.add(seg.get(j));
            }
            cur = path.get(path.size() - 1);
        }
        return path.size() < 2 ? null : search.smooth(path, RANGE, false);
    }

    static WarpGraph graph(Floor f, int bucket) {
        WarpGraph g = new WarpGraph(RANGE, 1.05, bucket, (grid, x, y, z) -> f.landingOk(x, y, z), FLOOR_Y - 20,
                FLOOR_Y + 45);
        g.partialFrom = PARTIAL;
        if (!"false".equals(System.getProperty("fine"))) {
            // EtherwarpPathfinder.doorwayColumn, in bench coordinates.
            g.setFine((x, z) -> {
                int wx = x - OFF - START;
                int wz = z - OFF - START;
                int offX = Math.floorMod(wx + 16, 32) - 16;
                int offZ = Math.floorMod(wz + 16, 32) - 16;
                int seamX = Math.floorMod(wx, 32) - 16;
                int seamZ = Math.floorMod(wz, 32) - 16;
                return offX == 0 || offZ == 0 || (Math.abs(seamX) <= 3 && Math.abs(offZ) <= 1)
                        || (Math.abs(seamZ) <= 3 && Math.abs(offX) <= 1);
            });
        }
        g.setTiles(new WarpGraph.Tiles() {
            @Override
            public int tileOf(int x, int y, int z) {
                int cell = f.cellAt(x, z);
                return tileRegion(f, cell).test(x, y, z) ? cell : -1;
            }

            @Override
            public int count() {
                return 36;
            }
        });
        return g;
    }

    static WarpGraph.Goal goal(Floor f, Click c) {
        WarpGraph.Goal g = new WarpGraph.Goal();
        g.x = c.exact[0];
        g.y = c.exact[1];
        g.z = c.exact[2];
        if (c.tile) {
            g.region = tileRegion(f, c.tileCell);
            g.tile = c.tileCell;
        } else {
            g.tile = -1;
            int room = c.goalRoom;
            int tx = g.x;
            int ty = g.y;
            int tz = g.z;
            g.near = (x, y, z) -> Math.abs(x - tx) <= 5 && Math.abs(z - tz) <= 5 && Math.abs(y - ty) <= 5
                    && f.roomAt(x, z) == room;
        }
        return g;
    }

    /** EtherwarpPathfinder.planFloor: a tile click that reaches no landing of the tile's band tries its block. */
    static List<EtherSearch.Hop> planGame(WarpGraph g, EtherSearch.Grid grid, Floor f, Click c, long deadline) {
        List<EtherSearch.Hop> p = g.plan(grid, startHop(c.start), goal(f, c), deadline, 64);
        if (p == null && c.tile && !g.timedOut) {
            WarpGraph.Goal exact = new WarpGraph.Goal();
            exact.x = c.exact[0];
            exact.y = c.exact[1];
            exact.z = c.exact[2];
            p = g.plan(grid, startHop(c.start), exact, deadline, 64);
        }
        return p;
    }

    // ------------------------------------------------------------------------------------------- checking

    /** 0 = valid and on the goal, 1 = valid and on a near landing, -1 = invalid. */
    static int replay(Floor f, EtherSearch probe, int[] s, Click c, List<EtherSearch.Hop> path) {
        double x = s[0] + 0.5;
        double y = s[1] + 1.0;
        double z = s[2] + 0.5;
        double[] look = new double[3];
        int lx = 0;
        int ly = 0;
        int lz = 0;
        for (EtherSearch.Hop h : path) {
            if (Math.abs(h.x - x) > 1e-6 || Math.abs(h.y - y) > 1e-6 || Math.abs(h.z - z) > 1e-6) {
                return -1;
            }
            EtherSearch.look(h.yaw, h.pitch, look);
            double ey = y + EtherSearch.SNEAK_EYE;
            if (probe.cast(x, ey, z, x + look[0] * RANGE, ey + look[1] * RANGE, z + look[2] * RANGE) != EtherSearch.LANDS) {
                return -1;
            }
            lx = probe.hitX;
            ly = probe.hitY;
            lz = probe.hitZ;
            if (!f.landingOk(lx, ly, lz)) {
                return -1;
            }
            x = lx + 0.5;
            y = ly + 1.05;
            z = lz + 0.5;
        }
        if (c.tile) {
            if (tileRegion(f, c.tileCell).test(lx, ly, lz)) {
                return 0;
            }
            // The old planner was handed one block in the tile; landing on it is landing in the tile.
            return lx == c.exact[0] && ly == c.exact[1] && lz == c.exact[2] ? 0 : -1;
        }
        if (lx == c.exact[0] && ly == c.exact[1] && lz == c.exact[2]) {
            return 0;
        }
        boolean near = Math.abs(lx - c.exact[0]) <= 5 && Math.abs(ly - c.exact[1]) <= 5
                && Math.abs(lz - c.exact[2]) <= 5 && f.roomAt(lx, lz) == c.goalRoom;
        return near ? 1 : -1;
    }

    static void printPath(Floor f, EtherSearch probe, String what, List<EtherSearch.Hop> path) {
        StringBuilder sb = new StringBuilder(what + ":");
        double[] lk = new double[3];
        for (EtherSearch.Hop h : path) {
            EtherSearch.look(h.yaw, h.pitch, lk);
            double ey = h.y + EtherSearch.SNEAK_EYE;
            probe.cast(h.x, ey, h.z, h.x + lk[0] * RANGE, ey + lk[1] * RANGE, h.z + lk[2] * RANGE);
            double d = Math.sqrt(Math.pow(probe.hitX + 0.5 - h.x, 2) + Math.pow(probe.hitZ + 0.5 - h.z, 2));
            sb.append(String.format(Locale.ROOT, " -> %d,%d,%d (r%d, %.0fb)", probe.hitX, probe.hitY, probe.hitZ,
                    f.roomAt(probe.hitX, probe.hitZ), d));
        }
        System.out.println(sb);
    }

    static final class Stats {
        final String name;
        final List<Long> nanos = new ArrayList<>();
        final List<Integer> warps = new ArrayList<>();
        int ok;
        int near;
        int failed;
        int invalid;
        int n;

        Stats(String name) {
            this.name = name;
        }

        void add(long t, int verdict, List<EtherSearch.Hop> path) {
            n++;
            nanos.add(t);
            if (path == null) {
                failed++;
                warps.add(-1);
                return;
            }
            if (verdict < 0) {
                invalid++;
                warps.add(-1);
                return;
            }
            ok++;
            if (verdict == 1) {
                near++;
            }
            warps.add(path.size());
        }

        double meanWarps() {
            return warps.stream().filter(w -> w >= 0).mapToInt(Integer::intValue).average().orElse(0);
        }

        String timing() {
            long[] s = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
            if (s.length == 0) {
                return "-";
            }
            double mean = Arrays.stream(s).average().orElse(0) / 1e6;
            return String.format(Locale.ROOT, "mean %.2f, median %.2f, p90 %.2f, p99 %.2f, max %.2f ms", mean,
                    s[s.length / 2] / 1e6, s[s.length * 9 / 10] / 1e6, s[Math.min(s.length - 1, s.length * 99 / 100)] / 1e6,
                    s[s.length - 1] / 1e6);
        }

        double p99() {
            long[] s = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
            return s.length == 0 ? 0 : s[Math.min(s.length - 1, s.length * 99 / 100)] / 1e6;
        }

        double median() {
            long[] s = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
            return s.length == 0 ? 0 : s[s.length / 2] / 1e6;
        }

        void print() {
            int[] w = warps.stream().filter(v -> v >= 0).mapToInt(Integer::intValue).sorted().toArray();
            System.out.printf(Locale.ROOT, "  %-26s %5d clicks: found %6.2f%% (%d near), failed %d, INVALID %d;"
                            + " warps mean %.2f median %d max %d; %s%n", name, n, 100.0 * ok / Math.max(1, n), near,
                    failed, invalid, meanWarps(), w.length == 0 ? 0 : w[w.length / 2], w.length == 0 ? 0 : w[w.length - 1],
                    timing());
        }
    }

    // ------------------------------------------------------------------------------------------- main

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "src/main/resources/assets/killer560smod/rooms");
        int floors = Integer.getInteger("floors", 5);
        int perFloor = Integer.getInteger("clicks", 300);
        int refPerFloor = Integer.getInteger("refclicks", 100);
        int bucket = Integer.getInteger("bucket", 3);
        int coldClicks = Integer.getInteger("coldclicks", 20);
        int lazyClicks = Integer.getInteger("lazyclicks", 40);
        int selfPerFloor = Integer.getInteger("selfclicks", 40);
        long seed = Long.getLong("seed", 560L);
        boolean runOld = !"false".equals(System.getProperty("old"));
        RANGE = Double.parseDouble(System.getProperty("range", "60"));
        FAN = EtherSearch.fan(RANGE, Float.parseFloat(System.getProperty("yaw", "6")),
                Float.parseFloat(System.getProperty("pitch", "7")));

        Map<String, RoomLibrary.Room> rooms = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try (var files = Files.list(dir)) {
            for (Path p : files.filter(q -> q.toString().endsWith(".json")).sorted().toList()) {
                RoomLibrary.Room r = LayoutSim.read(p);
                if (r != null && r.usable()) {
                    rooms.put(r.name, r);
                }
            }
        }
        RoomLibrary.ROOMS.putAll(rooms);
        System.out.printf(Locale.ROOT, "%d usable captures; %d floor(s) x %d clicks (ref on %d), bucket %d, fan %d rays,"
                + " range %.0f, seed %d%n", rooms.size(), floors, perFloor, refPerFloor, bucket, FAN.size(), RANGE, seed);

        Stats old = new Stats("old (room by room)");
        Stats cold = new Stats("new, cold graph");
        Stats lazy = new Stats("new, lazily warm");
        Stats warm = new Stats("new, warm");
        Stats newOnRef = new Stats("new on ref clicks");
        Stats ref = new Stats("ref (bucket 1, all)");
        Stats oldOnRef = new Stats("old on ref clicks");
        int[] diffHist = new int[11];   // new - ref, offset 5
        int[] oldDiffHist = new int[41];   // old - ref, offset 5
        long warmNanos = 0;
        int warmNodes = 0;
        int expandedNodes = 0;
        long warmEdges = 0;
        List<String> worse = new ArrayList<>();
        int selfChecked = 0;
        int oldOnly = 0;
        int failPrinted = 0;
        int selfMismatch = 0;

        Random rng = new Random(seed);
        for (int fi = 0; fi < floors; fi++) {
            SimFloorLayout.Floor lf = SimFloorLayout.generate(rooms, 21, 36, 3, 9, rng);
            if (SMALL_W > 0) {
                lf = window(lf, rng);
            }
            Floor f = new Floor(lf);
            EtherSearch.Grid grid = f::flags;
            EtherSearch probe = new EtherSearch(grid);
            List<Click> cs = clicks(f, rng, perFloor);
            int spots = f.spots.stream().mapToInt(List::size).sum();
            System.out.printf(Locale.ROOT, "floor %d: %d rooms, %d doors carved, %d sealed, %d walkable spots,"
                            + " %d room(s) not reachable from room 0 through the carved doors%n", fi,
                    f.rooms.size(), f.carved, f.sealed, spots, f.unreachableFrom(0));

            // Warm-up for the JIT on this floor's first few clicks, untimed.
            if (fi == 0) {
                WarpGraph jit = graph(f, bucket);
                for (int i = 0; i < Math.min(60, cs.size()); i++) {
                    Click c = cs.get(i);
                    planGame(jit, grid, f, c, Long.MAX_VALUE);
                    if (runOld) {
                        oldPlan(f, new EtherSearch(grid), c.start, c.exact);
                    }
                }
            }

            WarpGraph lazyG = graph(f, bucket);
            WarpGraph warmG = graph(f, bucket);
            warmG.fullAim = Boolean.getBoolean("newfull");
            long w0 = System.nanoTime();
            int[] s0 = cs.get(0).start;
            while (warmG.warm(grid, () -> grid, WORKERS, THREADS, s0[0] + 0.5, s0[1] + 1.0, s0[2] + 0.5,
                    50_000_000L)) {
                // keep going
            }
            warmNanos += System.nanoTime() - w0;
            System.out.printf(Locale.ROOT, "  warm: %d rays for %d nodes = %.0f rays a node, %.0f ms on %d thread(s)%n",
                    warmG.totalRays, warmG.nodeCount(), (double) warmG.totalRays / warmG.nodeCount(),
                    (System.nanoTime() - w0) / 1e6, THREADS);
            warmNodes += warmG.nodeCount();
            expandedNodes += warmG.expandedCount();
            WarpGraph refG = graph(f, Integer.getInteger("refbucket", 1));
            refG.fullAim = Boolean.getBoolean("reffull");
            if (refPerFloor > 0) {
                long r0 = System.nanoTime();
                while (refG.warm(grid, () -> grid, WORKERS, THREADS, s0[0] + 0.5, s0[1] + 1.0, s0[2] + 0.5,
                        50_000_000L)) {
                    // keep going
                }
                System.out.printf(Locale.ROOT, "  reference graph (bucket 1): %d nodes, warmed in %.0f ms%n",
                        refG.nodeCount(), (System.nanoTime() - r0) / 1e6);
            }
            if (Integer.getInteger("aimcheck", 0) > 0) {
                aimCheck(f, grid, rng, Integer.getInteger("aimcheck", 0));
            }
            for (int i = 0; i < cs.size(); i++) {
                Click c = cs.get(i);
                if (runOld) {
                    EtherSearch os = new EtherSearch(grid);
                    long t0 = System.nanoTime();
                    List<EtherSearch.Hop> p = oldPlan(f, os, c.start, c.exact);
                    long t = System.nanoTime() - t0;
                    old.add(t, p == null ? 0 : replay(f, probe, c.start, c, p), p);
                }
                if (i < coldClicks) {
                    WarpGraph g = graph(f, bucket);
                    long t0 = System.nanoTime();
                    List<EtherSearch.Hop> p = planGame(g, grid, f, c, System.nanoTime() + 670_000_000L);
                    cold.add(System.nanoTime() - t0, p == null ? 0 : replay(f, probe, c.start, c, p), p);
                }
                if (i < lazyClicks) {
                    long t0 = System.nanoTime();
                    List<EtherSearch.Hop> p = planGame(lazyG, grid, f, c, System.nanoTime() + 670_000_000L);
                    lazy.add(System.nanoTime() - t0, p == null ? 0 : replay(f, probe, c.start, c, p), p);
                }
                long t0 = System.nanoTime();
                List<EtherSearch.Hop> pw = planGame(warmG, grid, f, c, System.nanoTime() + 670_000_000L);
                long tw = System.nanoTime() - t0;
                int vw = pw == null ? 0 : replay(f, probe, c.start, c, pw);
                warm.add(tw, vw, pw);
                if (fi == Integer.getInteger("debugfloor", -1) && i == Integer.getInteger("debugclick", -1)) {
                    warmG.exactHeuristics = false;
                    List<EtherSearch.Hop> pg = planGame(warmG, grid, f, c, Long.MAX_VALUE);
                    warmG.exactHeuristics = true;
                    List<EtherSearch.Hop> po = oldPlan(f, new EtherSearch(grid), c.start, c.exact);
                    WarpGraph fresh = graph(f, bucket);
                    List<EtherSearch.Hop> pf = planGame(fresh, grid, f, c, Long.MAX_VALUE);
                    System.out.println("DEBUG warm " + (pw == null ? "null" : pw.size()) + ", geometric only "
                            + (pg == null ? "null" : pg.size()) + ", old " + (po == null ? "null" : po.size() + " v"
                            + replay(f, probe, c.start, c, po)) + ", fresh lazy graph " + (pf == null ? "null" : pf.size())
                            + "; start etherwarpable " + f.etherwarpable(c.start[0], c.start[1], c.start[2])
                            + ", goal block etherwarpable " + f.etherwarpable(c.exact[0], c.exact[1], c.exact[2]));
                    if (po != null) {
                        printPath(f, probe, "  old", po);
                    }
                }
                if (runOld && old.warps.get(old.warps.size() - 1) >= 0 && (pw == null || vw < 0)) {
                    oldOnly++;
                }
                if ((pw == null || vw < 0) && failPrinted++ < 5) {
                    System.out.printf("WARM %s floor %d click %d %s: start %s room %d, goal %s room %d (cell %d)%n",
                            pw == null ? "FAILED" : "INVALID", fi, i, c.tile ? "tile" : "exact", Arrays.toString(c.start),
                            f.roomAt(c.start[0], c.start[2]), Arrays.toString(c.exact), c.goalRoom, c.tileCell);
                }
                if (Integer.getInteger("debug", -1) == i && pw != null) {
                    System.out.printf("click %d: start %s room %d -> %s room %d (%s)%n", i, Arrays.toString(c.start),
                            f.roomAt(c.start[0], c.start[2]), Arrays.toString(c.exact), c.goalRoom, c.tile ? "tile" : "exact");
                    for (int k = 0; k < pw.size(); k++) {
                        EtherSearch.Hop h = pw.get(k);
                        double[] lk = new double[3];
                        EtherSearch.look(h.yaw, h.pitch, lk);
                        double ey = h.y + EtherSearch.SNEAK_EYE;
                        probe.cast(h.x, ey, h.z, h.x + lk[0] * RANGE, ey + lk[1] * RANGE, h.z + lk[2] * RANGE);
                        double d = Math.sqrt(Math.pow(probe.hitX + 0.5 - h.x, 2) + Math.pow(probe.hitY + 1.05 - h.y, 2)
                                + Math.pow(probe.hitZ + 0.5 - h.z, 2));
                        System.out.printf(Locale.ROOT, "   hop %d from %.1f,%.2f,%.1f (room %d) -> %d,%d,%d (room %d) %.1f blocks%n", k,
                                h.x, h.y, h.z, f.roomAt((int) Math.floor(h.x), (int) Math.floor(h.z)), probe.hitX, probe.hitY,
                                probe.hitZ, f.roomAt(probe.hitX, probe.hitZ), d);
                    }
                }
                warmEdges += warmG.edgesScanned;
                if (Boolean.getBoolean("verbose")) {
                    System.out.printf(Locale.ROOT, "  click %d %s: %d warps, %.2f ms (start %.2f, aim set %.2f [%d], labels %.2f [%d]), %d expanded, %d edges, fields %b%n", i,
                            c.tile ? "tile " : "exact", pw == null ? -1 : pw.size(), tw / 1e6, warmG.nanosStart / 1e6,
                            warmG.nanosAimSet / 1e6, warmG.goalSetSize, warmG.nanosLabels / 1e6, warmG.labelled,
                            warmG.expandedWarm, warmG.edgesScanned, warmG.usedFields);
                }
                if (i < selfPerFloor && pw != null && vw >= 0) {
                    warmG.exactHeuristics = false;
                    List<EtherSearch.Hop> pg = planGame(warmG, grid, f, c, Long.MAX_VALUE);
                    warmG.exactHeuristics = true;
                    selfChecked++;
                    if (pg == null || pg.size() != pw.size()) {
                        selfMismatch++;
                        System.out.println("SELF-CHECK MISMATCH floor " + fi + " click " + i + ": exact heuristics "
                                + pw.size() + ", geometric only " + (pg == null ? "none" : pg.size()));
                    }
                }
                if (i < refPerFloor) {
                    long r0 = System.nanoTime();
                    List<EtherSearch.Hop> pr = planGame(refG, grid, f, c, Long.MAX_VALUE);
                    long tr = System.nanoTime() - r0;
                    int vr = pr == null ? 0 : replay(f, probe, c.start, c, pr);
                    ref.add(tr, vr, pr);
                    newOnRef.add(tw, vw, pw);
                    if (runOld) {
                        int ow = old.warps.get(old.warps.size() - 1);
                        oldOnRef.warps.add(ow);
                        oldOnRef.n++;
                        if (ow >= 0 && pr != null && vr == 0) {
                            oldDiffHist[Math.max(0, Math.min(40, ow - pr.size() + 5))]++;
                        }
                    }
                    if (pw != null && pr != null && vw == 0 && vr == 0) {
                        int d = pw.size() - pr.size();
                        diffHist[Math.max(0, Math.min(10, d + 5))]++;
                        if (d > 0 && Integer.getInteger("debugworse", 0) > worse.size()) {
                            System.out.println("WORSE click " + i + (c.tile ? " tile" : " exact") + " start "
                                    + Arrays.toString(c.start) + " goal " + Arrays.toString(c.exact));
                            printPath(f, probe, "  new", pw);
                            printPath(f, probe, "  ref", pr);
                        }
                        if (d > 0 && worse.size() < 8) {
                            worse.add(String.format(Locale.ROOT, "floor %d click %d %s: new %d, ref %d", fi, i,
                                    c.tile ? "tile" : "exact", pw.size(), pr.size()));
                        }
                    } else if ((pw == null || vw != 0) && pr != null && vr == 0 && worse.size() < 8) {
                        worse.add(String.format(Locale.ROOT, "floor %d click %d: new FAILED (%s), ref %d", fi, i,
                                pw == null ? "no path" : vw < 0 ? "invalid" : "near", pr.size()));
                    }
                }
            }
            System.out.printf(Locale.ROOT, "  warm-up of the whole floor: %d nodes, %d expanded, %.0f ms; ref graph %d nodes%n",
                    warmG.nodeCount(), warmG.expandedCount(), (System.nanoTime() - w0) / 1e6 - 0, refG.nodeCount());
        }
        System.out.println();
        System.out.println("RESULTS");
        if (runOld) {
            old.print();
        }
        cold.print();
        lazy.print();
        warm.print();
        System.out.printf(Locale.ROOT, "  warm-up per floor: %.0f ms for %d nodes (%d expanded); warm click scans %.0f edges%n",
                warmNanos / 1e6 / floors, warmNodes / floors, expandedNodes / floors, (double) warmEdges / Math.max(1, warm.n));
        System.out.println();
        if (runOld) {
            System.out.println("clicks the old planner found and the new one did not: " + oldOnly);
        }
        System.out.printf("SELF-CHECK: %d click(s) planned again on the same warm graph with only the geometric bound:"
                + " %d gave a different number of warps%n", selfChecked, selfMismatch);
        System.out.println("MINIMALITY on the first " + refPerFloor + " clicks of each floor");
        ref.print();
        newOnRef.print();
        StringBuilder sb = new StringBuilder("  new - ref warps:");
        for (int d = 0; d < diffHist.length; d++) {
            if (diffHist[d] > 0) {
                sb.append(String.format(Locale.ROOT, "  %+d: %d", d - 5, diffHist[d]));
            }
        }
        System.out.println(sb);
        if (runOld) {
            StringBuilder ob = new StringBuilder("  old - ref warps:");
            for (int d = 0; d < oldDiffHist.length; d++) {
                if (oldDiffHist[d] > 0) {
                    ob.append(String.format(Locale.ROOT, "  %+d: %d", d - 5, oldDiffHist[d]));
                }
            }
            System.out.println(ob);
        }
        for (String w : worse) {
            System.out.println("  " + w);
        }

        String check = System.getProperty("check");
        if (check != null) {
            // Keys are "full.x" or "small.x" (-Dsmallw), so one file holds both runs' floors.
            String prefix = SMALL_W > 0 ? "small." : "full.";
            Map<String, Double> want = new HashMap<>();
            for (String line : Files.readAllLines(Path.of(check))) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || !line.startsWith(prefix)) {
                    continue;
                }
                String[] kv = line.substring(prefix.length()).split("=");
                want.put(kv[0].trim(), Double.parseDouble(kv[1].trim()));
            }
            List<String> fails = new ArrayList<>();
            double found = 100.0 * warm.ok / Math.max(1, warm.n);
            int compared = 0;
            int worseCount = 0;
            for (int d = 0; d < diffHist.length; d++) {
                compared += diffHist[d];
                if (d > 5) {
                    worseCount += diffHist[d];
                }
            }
            double worseShare = 100.0 * worseCount / Math.max(1, compared);
            int invalid = warm.invalid + cold.invalid + lazy.invalid + ref.invalid;
            if (oldOnly > 0) {
                fails.add(oldOnly + " click(s) the old planner found and the new one did not");
            }
            if (selfMismatch > 0) {
                fails.add("exact heuristics changed the warp count on " + selfMismatch + " click(s)");
            }
            if (invalid > 0) {
                fails.add("invalid paths: " + invalid);
            }
            if (want.containsKey("minFoundPercent") && found < want.get("minFoundPercent")) {
                fails.add(String.format(Locale.ROOT, "found %.2f%% < %.2f%%", found, want.get("minFoundPercent")));
            }
            if (want.containsKey("maxMeanWarps") && warm.meanWarps() > want.get("maxMeanWarps")) {
                fails.add(String.format(Locale.ROOT, "mean warps %.3f > %.3f", warm.meanWarps(), want.get("maxMeanWarps")));
            }
            if (want.containsKey("maxMeanWarpsOverOld") && runOld
                    && warm.meanWarps() > old.meanWarps() * want.get("maxMeanWarpsOverOld")) {
                fails.add(String.format(Locale.ROOT, "mean warps %.3f > old %.3f x %.2f", warm.meanWarps(),
                        old.meanWarps(), want.get("maxMeanWarpsOverOld")));
            }
            if (want.containsKey("maxWorseThanRefPercent") && worseShare > want.get("maxWorseThanRefPercent")) {
                fails.add(String.format(Locale.ROOT, "%.2f%% of clicks worse than ref > %.2f%%", worseShare,
                        want.get("maxWorseThanRefPercent")));
            }
            if (want.containsKey("maxMeanOverRef") && ref.n > 0
                    && newOnRef.meanWarps() > ref.meanWarps() * want.get("maxMeanOverRef")) {
                fails.add(String.format(Locale.ROOT, "mean warps %.3f > ref %.3f x %.3f", newOnRef.meanWarps(),
                        ref.meanWarps(), want.get("maxMeanOverRef")));
            }
            if (want.containsKey("maxWarmMedianMs") && warm.median() > want.get("maxWarmMedianMs")) {
                fails.add(String.format(Locale.ROOT, "warm median %.2f ms > %.2f ms", warm.median(),
                        want.get("maxWarmMedianMs")));
            }
            if (want.containsKey("maxWarmP99Ms") && warm.p99() > want.get("maxWarmP99Ms")) {
                fails.add(String.format(Locale.ROOT, "warm p99 %.2f ms > %.2f ms", warm.p99(), want.get("maxWarmP99Ms")));
            }
            if (fails.isEmpty()) {
                System.out.println("REGRESSION CHECK PASSED (" + prefix + "* in " + check + ")");
            } else {
                System.out.println("REGRESSION CHECK FAILED (" + prefix + "* in " + check + "): " + String.join("; ", fails));
                System.exit(1);
            }
        }
    }
}

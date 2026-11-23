package com.killer560.hub.autoclear;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.livemap.autoclear.DungeonMapPathfinder;
import com.killer560.hub.roomdatabase.RoomEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Which rooms Auto Clear works through, read off the live map ({@link DungeonLayout}) - the same room graph the
 * Interactive Map and Auto Blood Rush path over. Client thread.
 */
public final class AutoClearTargets {

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-autoclear");

    private AutoClearTargets() {
    }

    /**
     * A room whose clear is its starred mobs: the room database's NORMAL, RARE and CHAMPION types (minibosses live in
     * the last two, and a starred miniboss counts). Puzzle, trap, fairy, blood and entrance rooms are never targets.
     */
    static boolean isMobRoom(DungeonLayout layout, int room) {
        RoomEntry entry = layout.entry(room);
        if (entry == null || entry.type == null) {
            return false;
        }
        String type = entry.type.toUpperCase(java.util.Locale.ROOT);
        return type.equals("NORMAL") || type.equals("RARE") || type.equals("CHAMPION");
    }

    /**
     * Cleared as Auto Clear reads it: on the map, or by killer560's chest rule ({@link ChestClearRule}: its secret chest
     * grabbed a few seconds ago, under Auto Clear or Auto Secret, and not yet 30 s without the map agreeing).
     */
    static boolean isCleared(DungeonLayout layout, int room) {
        return isClearedOnMap(layout, room) || ChestClearRule.clearedInItsEyes(layout, room);
    }

    /** Cleared on the map (the checkmark, green or white) on any of its tiles. */
    public static boolean isClearedOnMap(DungeonLayout layout, int room) {
        int[] tiles = layout.tiles(room);
        if (tiles == null) {
            return false;
        }
        for (int t : tiles) {
            if (LiveMapFeature.isRoomCleared(t)) {
                return true;
            }
        }
        return false;
    }

    static int roomByName(DungeonLayout layout, String name) {
        if (name == null) {
            return -1;
        }
        for (int r = 0; r < layout.roomCount(); r++) {
            if (name.equalsIgnoreCase(layout.name(r))) {
                return r;
            }
        }
        return -1;
    }

    public static int entranceRoom(DungeonLayout layout) {
        for (int r = 0; r < layout.roomCount(); r++) {
            RoomEntry e = layout.entry(r);
            if ((e != null && "ENTRANCE".equalsIgnoreCase(e.type)) || "Entrance".equalsIgnoreCase(layout.name(r))) {
                return r;
            }
        }
        return -1;
    }

    /**
     * The blood rush (Blood Rush Split mode): every room on the shortest room-graph path from the Entrance (or where he
     * stands, when the Entrance is not on the map) to the room in front of the Blood door, locked doors counted as
     * passable because a blood rush opens them. Null when the Blood door is not on the map yet. Same resolution of
     * "the room in front of the Blood door" as Auto Blood Rush's {@code nextDoor}.
     */
    static List<Integer> bloodRushRooms(DungeonLayout layout) {
        int blood = layout.bloodDoor();
        if (blood < 0) {
            return null;
        }
        int start = entranceRoom(layout);
        if (start < 0) {
            start = layout.currentRoom();
        }
        if (start < 0) {
            return null;
        }
        int[] resolved = DungeonMapPathfinder.resolve(layout, start, blood, true);
        if (resolved == null) {
            return null;
        }
        int bloodSide = layout.roomOfCell(resolved[1]);
        List<Integer> out = new ArrayList<>();
        if (bloodSide == start) {
            out.add(start);
            return out;
        }
        List<DungeonMapPathfinder.RoomStep> path = DungeonMapPathfinder.findPath(layout, start, bloodSide, true);
        if (path == null) {
            return null;
        }
        for (DungeonMapPathfinder.RoomStep step : path) {
            out.add(step.room());
        }
        return out;
    }

    /** Uncleared mob rooms the mode allows, minus the ones given up on this run (by name). */
    static List<Integer> candidates(DungeonLayout layout, AutoClearConfig.Mode mode, Set<String> skipped) {
        List<Integer> pool = new ArrayList<>();
        // killer560, 2026-10-19: "it will complain it cannot see blood sometimes, yet it can still see wither doors ... clear
        // the room prior to wither doors with mobs and it will load blood as time goes on." Until the Blood door is on the
        // map, the split clears the mob rooms like Full Clear instead of stopping; once it is, the rush order takes over.
        List<Integer> rush = mode == AutoClearConfig.Mode.BLOOD_RUSH_SPLIT ? bloodRushRooms(layout) : null;
        if (rush != null) {
            pool.addAll(rush);
        } else {
            for (int r = 0; r < layout.roomCount(); r++) {
                pool.add(r);
            }
        }
        List<Integer> out = new ArrayList<>();
        for (int r : pool) {
            if (isMobRoom(layout, r) && !isCleared(layout, r) && !skipped.contains(layout.name(r))) {
                out.add(r);
            }
        }
        return out;
    }

    /** Whether {@link #candidates} would take {@code room} if it were uncleared: a mob room the mode allows, not given up. */
    static boolean allowedByMode(DungeonLayout layout, AutoClearConfig.Mode mode, Set<String> skipped, int room) {
        if (room < 0 || !isMobRoom(layout, room) || skipped.contains(layout.name(room))) {
            return false;
        }
        List<Integer> rush = mode == AutoClearConfig.Mode.BLOOD_RUSH_SPLIT ? bloodRushRooms(layout) : null;
        return rush == null || rush.contains(room);
    }

    /** What to do next: clear {@code room}, or first open the wither {@code door} that is all that stands in the way. */
    record Choice(int room, int door) {
    }

    /**
     * killer560's order (2026-10-06): "prioritize moving away from blood rush and taking whatever the longest split is for
     * the normal rooms; then, once it eventually needs to go down the blood split, ... open wither doors to continue."
     * <ol>
     *   <li>Rooms OFF the blood-rush path first, grouped by the branch ("split") they hang off the path by - the branch
     *       with the most rooms first; inside a branch, fewest rooms away from him first.</li>
     *   <li>Then the rooms ON the path, from the Entrance toward the Blood door.</li>
     * </ol>
     * Branches come from a breadth-first tree over the map from the Entrance (locked doors passable); a room's branch is
     * the first room off the path on its way back to the Entrance. With no Blood door on the map yet every room is one
     * branch (nearest first). Blood Rush Split mode passes only the path rooms in.
     */
    static List<Integer> ordered(DungeonLayout layout, List<Integer> candidates) {
        List<Integer> rush = bloodRushRooms(layout);
        java.util.Set<Integer> onPath = rush == null ? java.util.Set.of() : new java.util.HashSet<>(rush);
        int root = rush != null && !rush.isEmpty() ? rush.get(0) : entranceRoom(layout);
        if (root < 0) {
            root = layout.currentRoom();
        }
        int[] parent = bfsParents(layout, root);
        int[] fromHere = bfsDistances(layout, layout.currentRoom());
        int n = layout.roomCount();
        int[] branch = new int[n];
        java.util.Arrays.fill(branch, -1);
        java.util.Map<Integer, Integer> size = new java.util.HashMap<>();
        for (int r = 0; r < n; r++) {
            if (onPath.contains(r) || parent[r] == -2) {
                continue;
            }
            int at = r;
            int guard = 0;
            while (parent[at] >= 0 && !onPath.contains(parent[at]) && guard++ < n) {
                at = parent[at];
            }
            branch[r] = at;
            size.merge(at, 1, Integer::sum);
        }
        List<Integer> off = new ArrayList<>();
        List<Integer> path = new ArrayList<>();
        for (int r : candidates) {
            (onPath.contains(r) ? path : off).add(r);
        }
        off.sort((x, y) -> {
            int sx = branch[x] < 0 ? 0 : size.getOrDefault(branch[x], 0);
            int sy = branch[y] < 0 ? 0 : size.getOrDefault(branch[y], 0);
            if (sx != sy) {
                return Integer.compare(sy, sx);
            }
            return branch[x] != branch[y] ? Integer.compare(branch[x], branch[y]) : Integer.compare(fromHere[x], fromHere[y]);
        });
        if (rush != null) {
            path.sort(java.util.Comparator.comparingInt(rush::indexOf));
        }
        List<Integer> out = new ArrayList<>(off);
        out.addAll(path);
        // Blood Rush Split keeps its rush order (its rooms are all on the path); Full Clear tours every room he can reach
        // in the fewest tiles, with no room forced first (killer560 2026-11-22: "it should be able to clear whatever is fastest").
        boolean split = AutoClearConfig.getInstance().getMode() == AutoClearConfig.Mode.BLOOD_RUSH_SPLIT;
        return toured(layout, candidates, out, split ? off : out, split ? off : List.of());
    }

    private static final double FIRST_PENALTY = 1000;
    private static DungeonLayout memoLayout;
    private static List<Integer> memoCandidates = List.of();
    private static int memoTile = -1;
    private static List<Integer> memoOrder;

    /**
     * {@code order} with the rooms of {@code group} he can reach through open doors taken first, in the order with the
     * fewest map tiles walked, every room of {@code first} before any other (acroomorder-1122, killer560 2026-11-22:
     * "also which rooms it goes to and what order it does them in"); every other room keeps its place behind them, so
     * the wither-door fallback of {@link #choose} reads the same. Rooms-away counted a 1x4 room as one step and sent him
     * across the floor past nearer rooms (f1 Lots Of Floors, 22 ticks after Crypt). Worked out once per pick (the same
     * layout, rooms and tile answer the next callers).
     */
    private static List<Integer> toured(DungeonLayout layout, List<Integer> candidates, List<Integer> order,
                                        List<Integer> group, List<Integer> first) {
        int start = playerTile(layout);
        if (layout == memoLayout && start == memoTile && candidates.equals(memoCandidates)) {
            return memoOrder;
        }
        int[] dist = start < 0 ? null : tileDistances(layout);
        List<Integer> reach = new ArrayList<>();
        for (int r : group) {
            if (dist != null && nearest(layout, dist, start * TILES, r) < UNREACHED) {
                reach.add(r);
            }
        }
        List<Integer> out = order;
        if (reach.size() > 1) {
            int n = reach.size();
            double[] from = new double[n];
            double[][] between = new double[n][n];
            boolean anyFirst = false;
            for (int r : reach) {
                anyFirst |= first.contains(r);
            }
            for (int i = 0; i < n; i++) {
                // A room not in {@code first} taken while one of them is left costs a penalty no saving outweighs.
                boolean later = anyFirst && !first.contains(reach.get(i));
                from[i] = nearest(layout, dist, start * TILES, reach.get(i)) + (later ? FIRST_PENALTY : 0);
                int[] tiles = layout.tiles(reach.get(i));
                for (int j = 0; j < n; j++) {
                    double sum = 0;
                    for (int c : tiles) {
                        sum += nearest(layout, dist, cellTile(c) * TILES, reach.get(j));
                    }
                    between[i][j] = i == j ? 0 : sum / tiles.length
                            + (later && first.contains(reach.get(j)) ? FIRST_PENALTY : 0);
                }
            }
            long until = System.nanoTime() + 3_000_000L;
            int[] best = (n <= 12 ? com.killer560.hub.autosecret.LeastWarpSearch.solve(from, between, null)
                    : com.killer560.hub.autosecret.LeastWarpSearch.branchAndBound(from, between,
                    () -> System.nanoTime() > until)).order();
            out = new ArrayList<>(order.size());
            for (int i : best) {
                out.add(reach.get(i));
            }
            for (int r : order) {
                if (!out.contains(r)) {
                    out.add(r);
                }
            }
            int[] old = new int[n];
            for (int i = 0; i < n; i++) {
                old[i] = i;
            }
            LOGGER.info(String.format(java.util.Locale.US, "[AutoClear] room order: %s est %.1f tiles vs old rule %.1f",
                    out.subList(0, n).stream().map(layout::name).toList(),
                    com.killer560.hub.autosecret.TourPlanner.travel(best, from, between),
                    com.killer560.hub.autosecret.TourPlanner.travel(old, from, between)));
        }
        memoLayout = layout;
        memoTile = start;
        memoCandidates = List.copyOf(candidates);
        memoOrder = out;
        return out;
    }

    /**
     * The first room in {@link #ordered} order he can reach through open doors; or, when none can be, the first locked
     * WITHER door on the way to the first of them (killer560: open a wither door only when it is the only thing left
     * before it can progress). Null when only rooms no wither door opens the way to are left.
     */
    static Choice choose(DungeonLayout layout, List<Integer> candidates) {
        List<Integer> order = ordered(layout, candidates);
        int from = layout.currentRoom();
        for (int r : order) {
            if (r == from || (from >= 0 && DungeonMapPathfinder.findPath(layout, from, r, false) != null)) {
                return new Choice(r, -1);
            }
        }
        if (from < 0) {
            return order.isEmpty() ? null : new Choice(order.get(0), -1);
        }
        for (int r : order) {
            List<DungeonMapPathfinder.RoomStep> path = DungeonMapPathfinder.findPath(layout, from, r, true);
            if (path == null) {
                continue;
            }
            for (DungeonMapPathfinder.RoomStep step : path) {
                int d = step.door();
                if (d >= 0 && layout.isLocked(d)) {
                    if (layout.doorType(d) == DungeonLayout.DOOR_WITHER) {
                        return new Choice(r, d);
                    }
                    break;   // a blood door: never opened here
                }
            }
        }
        return null;
    }

    /** Parent of each room in a breadth-first tree from {@code root} (locked doors passable); -1 root, -2 unreached. */
    private static int[] bfsParents(DungeonLayout layout, int root) {
        int n = layout.roomCount();
        int[] parent = new int[n];
        java.util.Arrays.fill(parent, -2);
        if (root < 0 || root >= n) {
            return parent;
        }
        parent[root] = -1;
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            int r = q.poll();
            for (int next : adjacent(layout, r)) {
                if (parent[next] == -2) {
                    parent[next] = r;
                    q.add(next);
                }
            }
        }
        return parent;
    }

    /** Rooms between {@code from} and every room over any door, locked or not (1000 unreached). */
    static int[] bfsDistances(DungeonLayout layout, int from) {
        int n = layout.roomCount();
        int[] dist = new int[n];
        java.util.Arrays.fill(dist, 1000);
        if (from < 0 || from >= n) {
            return dist;
        }
        dist[from] = 0;
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        q.add(from);
        while (!q.isEmpty()) {
            int r = q.poll();
            for (int next : adjacent(layout, r)) {
                if (dist[next] > dist[r] + 1) {
                    dist[next] = dist[r] + 1;
                    q.add(next);
                }
            }
        }
        return dist;
    }

    private static final int TILES = 36;
    private static final int UNREACHED = 999;

    /** The 6x6 tile of a map cell (rooms' tiles sit on the even cells of the 11x11 grid). */
    private static int cellTile(int cell) {
        return (cell / DungeonLayout.GRID / 2) * 6 + (cell % DungeonLayout.GRID) / 2;
    }

    /** The tile he stands on, as {@link DungeonLayout#roomAtWorld} reads it; a tile of the room he is in when that one
     *  is no room (a doorway's gap); -1 outside every room. */
    private static int playerTile(DungeonLayout layout) {
        int room = layout.currentRoom();
        net.minecraft.world.entity.player.Player p = net.minecraft.client.Minecraft.getInstance().player;
        if (room < 0 || p == null) {
            return -1;
        }
        int ix = Math.max(0, Math.min(5, (int) Math.round((p.getX() - 0.5 - LiveMapFeature.START_X) / 32.0)));
        int iz = Math.max(0, Math.min(5, (int) Math.round((p.getZ() - 0.5 - LiveMapFeature.START_Z) / 32.0)));
        int t = iz * 6 + ix;
        return layout.roomOfCell(iz * 2 * DungeonLayout.GRID + ix * 2) == room ? t : cellTile(layout.tiles(room)[0]);
    }

    /** Tiles walked between every two tiles ({@code [from * 36 + to]}): a step joins two tiles of one room, or two
     *  rooms over an open door; {@link #UNREACHED} past locked doors. */
    private static int[] tileDistances(DungeonLayout layout) {
        int[] dist = new int[TILES * TILES];
        java.util.Arrays.fill(dist, UNREACHED);
        int[] queue = new int[TILES];
        for (int s = 0; s < TILES; s++) {
            if (layout.roomOfCell((s / 6) * 2 * DungeonLayout.GRID + (s % 6) * 2) < 0) {
                continue;
            }
            int head = 0;
            int tail = 0;
            dist[s * TILES + s] = 0;
            queue[tail++] = s;
            while (head < tail) {
                int t = queue[head++];
                int x = t % 6;
                int z = t / 6;
                int room = layout.roomOfCell(z * 2 * DungeonLayout.GRID + x * 2);
                for (int k = 0; k < 4; k++) {
                    int dx = k == 0 ? 1 : k == 1 ? -1 : 0;
                    int dz = k == 2 ? 1 : k == 3 ? -1 : 0;
                    int nx = x + dx;
                    int nz = z + dz;
                    int u = nz * 6 + nx;
                    if (nx < 0 || nx > 5 || nz < 0 || nz > 5 || dist[s * TILES + u] != UNREACHED) {
                        continue;
                    }
                    int next = layout.roomOfCell(nz * 2 * DungeonLayout.GRID + nx * 2);
                    int door = (z * 2 + dz) * DungeonLayout.GRID + x * 2 + dx;
                    if (next >= 0 && (next == room || (layout.isDoor(door) && !layout.isLocked(door)))) {
                        dist[s * TILES + u] = dist[s * TILES + t] + 1;
                        queue[tail++] = u;
                    }
                }
            }
        }
        return dist;
    }

    /** Fewest tiles from the tile whose row starts at {@code row} in {@code dist} to any tile of {@code room}. */
    private static int nearest(DungeonLayout layout, int[] dist, int row, int room) {
        int best = UNREACHED;
        for (int c : layout.tiles(room)) {
            best = Math.min(best, dist[row + cellTile(c)]);
        }
        return best;
    }

    /** Rooms joined to {@code room} by any door, locked or not - DungeonMapPathfinder's neighbor rule. */
    private static List<Integer> adjacent(DungeonLayout layout, int room) {
        List<Integer> out = new ArrayList<>();
        int[][] dirs = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}};
        for (int tile : layout.tiles(room)) {
            int x = tile % DungeonLayout.GRID;
            int z = tile / DungeonLayout.GRID;
            for (int[] d : dirs) {
                int nx = x + d[0] * 2;
                int nz = z + d[1] * 2;
                if (nx < 0 || nx > 10 || nz < 0 || nz > 10) {
                    continue;
                }
                int door = (z + d[1]) * DungeonLayout.GRID + (x + d[0]);
                int next = layout.roomOfCell(nz * DungeonLayout.GRID + nx);
                if (layout.isDoor(door) && next >= 0 && next != room && !out.contains(next)) {
                    out.add(next);
                }
            }
        }
        return out;
    }
}

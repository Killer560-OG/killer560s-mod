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
final class AutoClearTargets {

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

    /** Cleared on the map (the checkmark, green or white) on any of its tiles. */
    static boolean isCleared(DungeonLayout layout, int room) {
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

    static int entranceRoom(DungeonLayout layout) {
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
        if (mode == AutoClearConfig.Mode.BLOOD_RUSH_SPLIT) {
            List<Integer> rush = bloodRushRooms(layout);
            if (rush == null) {
                return null;
            }
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
            if (branch[x] != branch[y]) {
                return Integer.compare(branch[x], branch[y]);
            }
            return Integer.compare(fromHere[x], fromHere[y]);
        });
        if (rush != null) {
            path.sort(java.util.Comparator.comparingInt(rush::indexOf));
        }
        List<Integer> out = new ArrayList<>(off);
        out.addAll(path);
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

    private static int[] bfsDistances(DungeonLayout layout, int from) {
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

    /** Rooms joined to {@code room} by any door, locked or not - DungeonMapPathfinder's neighbour rule. */
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

    /** The candidate fewest rooms away through unlocked doors, or -1 when none can be reached from here. */
    static int nearest(DungeonLayout layout, List<Integer> candidates) {
        int from = layout.currentRoom();
        int best = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int r : candidates) {
            int d;
            if (r == from) {
                d = 0;
            } else if (from < 0) {
                d = 1000;
            } else {
                List<DungeonMapPathfinder.RoomStep> path = DungeonMapPathfinder.findPath(layout, from, r, false);
                if (path == null) {
                    continue;
                }
                d = path.size();
            }
            if (d < bestDist) {
                bestDist = d;
                best = r;
            }
        }
        return best;
    }
}

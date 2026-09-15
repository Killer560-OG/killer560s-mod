package com.killer560.hub.livemap.autoclear;

import com.killer560.hub.livemap.DungeonLayout;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Port of QUOI's {@code DungeonMapPathfinder} ("ty rice skyblock addons"): A* over rooms on the 11x11 map, rooms
 * joined by the door cell between two tiles. Reads a {@link DungeonLayout} snapshot instead of {@code ScanUtils.grid}.
 */
public final class DungeonMapPathfinder {

    /** door dx, door dz, next room dx, next room dz: n, s, e, w. */
    private static final int[][] DIRECTIONS = {{0, -1, 0, -2}, {0, 1, 0, 2}, {1, 0, 2, 0}, {-1, 0, -2, 0}};

    private DungeonMapPathfinder() {
    }

    /** QUOI {@code RoomPath}: a room and the door taken out of it (-1 in the goal room). */
    public record RoomStep(int room, int door) {
    }

    private record Node(int room, int doorFromParent, int g, int h, Node parent) implements Comparable<Node> {
        int f() {
            return g + h;
        }

        @Override
        public int compareTo(Node o) {
            return Integer.compare(f(), o.f());
        }
    }

    public static List<RoomStep> findPath(DungeonLayout layout, int start, int goal, boolean ignoreLocked) {
        if (start < 0 || goal < 0) {
            return null;
        }
        PriorityQueue<Node> open = new PriorityQueue<>();
        int[] best = new int[layout.roomCount()];
        java.util.Arrays.fill(best, Integer.MAX_VALUE);
        open.add(new Node(start, -1, 0, heuristic(layout, start, goal), null));
        best[start] = 0;
        while (!open.isEmpty()) {
            Node current = open.poll();
            if (current.g > best[current.room]) {
                continue;
            }
            if (current.room == goal) {
                return reconstruct(current);
            }
            for (int[] neighbour : neighbours(layout, current.room, ignoreLocked)) {
                int room = neighbour[0];
                int g = current.g + 1;
                if (g < best[room]) {
                    best[room] = g;
                    open.add(new Node(room, neighbour[1], g, heuristic(layout, room, goal), current));
                }
            }
        }
        return null;
    }

    /** Rooms/doors away from {@code start} to a door, or {@code Integer.MAX_VALUE}. */
    public static int getDistToDoor(DungeonLayout layout, int start, int door, boolean ignoreLocked) {
        int[] r = resolve(layout, start, door, ignoreLocked);
        return r == null ? Integer.MAX_VALUE : r[2];
    }

    /** Approach position of a door: the door itself if unlocked, else 2 blocks back on the reachable side. */
    public static BlockPos getDoorPos(DungeonLayout layout, int start, int door) {
        BlockPos lock = DungeonLayout.doorBlock(door);
        BlockPos pos = new BlockPos(lock.getX(), 68, lock.getZ());
        if (!layout.isLocked(door)) {
            return pos;
        }
        int[] r = resolve(layout, start, door, false);
        if (r == null) {
            return null;
        }
        int dx = (r[1] % 11) - (door % 11);
        int dz = (r[1] / 11) - (door / 11);
        return new BlockPos(lock.getX() + dx * 2, 68, lock.getZ() + dz * 2);
    }

    /** QUOI {@code stupid()}: {door index, index of the tile you reach it from, room distance}. */
    public static int[] resolve(DungeonLayout layout, int start, int door, boolean ignoreLocked) {
        if (!layout.isDoor(door)) {
            return null;
        }
        int doorX = door % 11;
        int doorZ = door / 11;
        boolean horizontal = doorX % 2 != 0;
        int i1 = horizontal ? doorZ * 11 + (doorX - 1) : (doorZ - 1) * 11 + doorX;
        int i2 = horizontal ? doorZ * 11 + (doorX + 1) : (doorZ + 1) * 11 + doorX;
        int room1 = i1 >= 0 && i1 < 121 ? layout.roomOfCell(i1) : -1;
        int room2 = i2 >= 0 && i2 < 121 ? layout.roomOfCell(i2) : -1;
        int dist1 = distance(layout, start, room1, ignoreLocked);
        int dist2 = distance(layout, start, room2, ignoreLocked);
        if (dist1 == Integer.MAX_VALUE && dist2 == Integer.MAX_VALUE) {
            return null;
        }
        return dist1 <= dist2 ? new int[]{door, i1, dist1} : new int[]{door, i2, dist2};
    }

    private static int distance(DungeonLayout layout, int start, int room, boolean ignoreLocked) {
        if (room < 0) {
            return Integer.MAX_VALUE;
        }
        if (room == start) {
            return 0;
        }
        List<RoomStep> path = findPath(layout, start, room, ignoreLocked);
        return path == null ? Integer.MAX_VALUE : path.size();
    }

    private static List<int[]> neighbours(DungeonLayout layout, int room, boolean ignoreLocked) {
        List<int[]> out = new ArrayList<>();
        for (int z = 0; z <= 10; z += 2) {
            for (int x = 0; x <= 10; x += 2) {
                if (layout.roomOfCell(z * 11 + x) != room) {
                    continue;
                }
                for (int[] dir : DIRECTIONS) {
                    int doorX = x + dir[0];
                    int doorZ = z + dir[1];
                    int nextX = x + dir[2];
                    int nextZ = z + dir[3];
                    if (nextX < 0 || nextX > 10 || nextZ < 0 || nextZ > 10) {
                        continue;
                    }
                    int doorIdx = doorZ * 11 + doorX;
                    int next = layout.roomOfCell(nextZ * 11 + nextX);
                    if (layout.isDoor(doorIdx) && next >= 0 && next != room && (!layout.isLocked(doorIdx) || ignoreLocked)) {
                        out.add(new int[]{next, doorIdx});
                    }
                }
            }
        }
        return out;
    }

    private static int heuristic(DungeonLayout layout, int from, int to) {
        // QUOI: manhattan distance between text placements (20 map px per room) / 20 == grid units / 2.
        return (int) ((Math.abs(layout.labelGX(from) - layout.labelGX(to)) + Math.abs(layout.labelGZ(from) - layout.labelGZ(to))) / 2f);
    }

    private static List<RoomStep> reconstruct(Node node) {
        List<RoomStep> path = new ArrayList<>();
        Node current = node;
        int nextDoor = -1;
        while (current != null) {
            path.add(0, new RoomStep(current.room, nextDoor));
            nextDoor = current.doorFromParent;
            current = current.parent;
        }
        return path;
    }
}

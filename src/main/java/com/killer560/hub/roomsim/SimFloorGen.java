package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.util.ModChat;

import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Generates a whole floor, rather than a corridor to the blood door.
 *
 * <p>killer560 (2026-09-28): "it shouldnt just generate rooms between me and blood it should generate a full
 * map", plus "have an option to choose what map size so for instance entrance, f1, f6, f7", "Max rooms to blood
 * should be 8 and it should be a sliding bar between 2-8. Do not count blood, green room, or fairy those are
 * given. Make puzzles a bar as well from 2-5."
 *
 * <p>The old generator laid a single line of rooms from the entrance to blood. That is not a floor and it is
 * not what a route is written against: on a real floor most of the rooms are off to the side, the order you
 * take them in is the decision being practised, and a corridor removes the decision entirely.
 *
 * <p>So this grows a connected map over the room grid, then decides what each room IS. Rooms-to-blood is the
 * distance from the entrance to the blood door along that map, counted the way he counts it - blood, entrance
 * and fairy are given and do not count towards it.
 *
 * <p><b>One-by-one rooms only, for now.</b> A map code gives one room per cell, so a 2x2 room named in four
 * cells is pasted four times on top of itself rather than once across them. Rather than produce a map that
 * looks subtly wrong, this generates from the 1x1 rooms and says how many were set aside. That is a real
 * limitation and is better stated than discovered.
 */
public final class SimFloorGen {

    private static final Random RNG = new Random();

    /** The room grid inside {@link DungeonLayout}'s 11x11: rooms sit on even coordinates, doors between. */
    private static final int ROOM_GRID = (DungeonLayout.GRID + 1) / 2;

    /**
     * Floor sizes, as the number of rooms on the map.
     *
     * <p>MEASURED, not estimated. killer560 ran Entrance through F7 on 2026-09-28 and {@code FloorSizeLog}
     * recorded each one; these are the largest sample per floor, which is the fully-revealed map - the smaller
     * samples are the same run seen earlier, before the whole thing was on the map.
     *
     * <p>Two of my estimates were wrong in a way worth keeping a note of. I had F6 bigger than F5 and F7
     * bigger than both; the measurements say F5 and F7 are 21 and F6 is 19, so floor number is not room count
     * and guessing from it was never going to work.
     */
    public enum Floor {
        ENTRANCE("Entrance", 11),
        F1("Floor 1", 13),
        F2("Floor 2", 15),
        F3("Floor 3", 16),
        F4("Floor 4", 19),
        F5("Floor 5", 21),
        F6("Floor 6", 19),
        F7("Floor 7", 21);

        public final String label;
        public final int rooms;

        Floor(String label, int rooms) {
            this.label = label;
            this.rooms = rooms;
        }
    }

    /** His sliders' limits, in one place so the menu and the generator cannot disagree. */
    public static final int MIN_ROOMS_TO_BLOOD = 2;
    /**
     * Eight, on killer560's word: "the max is 8 if you do not count blood green room or fairy. It cannot be
     * more."
     *
     * <p>My own measurement said ten and it was measuring the wrong thing - it counted every cell on the path
     * including the ones belonging to the three given rooms, which is not the number he means. The logger now
     * counts his way, so the next set of runs either confirms eight or shows me something I have still got
     * wrong.
     */
    public static final int MAX_ROOMS_TO_BLOOD = 8;
    public static final int MIN_PUZZLES = 2;
    public static final int MAX_PUZZLES = 5;

    private SimFloorGen() {
    }

    /**
     * Builds and opens a floor.
     *
     * @param roomsToBlood how many ordinary rooms stand between the entrance and blood - blood, the entrance
     *                     and the fairy room are given and are not counted, which is how he counts them
     */
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("killer560smod-roomsim");

    public static void generate(Minecraft client, Floor floor, int puzzles, int roomsToBlood) {
        long planStart = System.currentTimeMillis();
        Map<String, RoomLibrary.Room> usable = new HashMap<>();
        for (String name : RoomLibrary.names()) {
            RoomLibrary.Room r = RoomLibrary.get(name);
            if (r == null || !r.complete()) {
                continue;
            }
            // killer560 (2026-09-28): "dont use 1x1 you need to use more than just that." Rooms of every
            // footprint now, which is only possible because a map code's cells are pasted once per ROOM rather
            // than once per cell - a 2x2 used to go down four times on top of itself.
            usable.put(name, r);
        }
        if (usable.isEmpty()) {
            ModChat.send("Sim", ModChat.text("No complete 1x1 rooms captured yet - "),
                    ModChat.dim("run the Room Recorder first."));
            return;
        }

        int wantRooms = Math.min(floor.rooms, ROOM_GRID * ROOM_GRID);
        List<int[]> shape = growConnectedShape(wantRooms);
        if (shape.size() < 3) {
            ModChat.send("Sim", ModChat.text("Could not lay out a floor that size."));
            return;
        }

        // Distances from the entrance, which is the first cell the growth placed.
        int[] entrance = shape.get(0);
        Map<Long, Integer> distance = distancesFrom(shape, entrance);

        // Blood sits at the requested distance when the map reaches that far, and at the furthest room it does
        // reach otherwise - a floor that cannot honour the number should still be playable, and saying so is
        // better than silently building something shorter.
        int wantDistance = Math.max(MIN_ROOMS_TO_BLOOD, Math.min(MAX_ROOMS_TO_BLOOD, roomsToBlood)) + 1;
        int[] blood = cellAtDistance(shape, distance, wantDistance);
        int[] fairy = pickAwayFrom(shape, entrance, blood);

        int gridCells = DungeonLayout.GRID * DungeonLayout.GRID;
        int[] cellRoom = new int[gridCells];
        int[] cellDoor = new int[gridCells];
        int[] cellRotation = new int[gridCells];
        java.util.Arrays.fill(cellRoom, MapCode.NO_ROOM);
        List<String> nameTable = new ArrayList<>();

        List<String> puzzlePool = byType(usable, "PUZZLE");
        List<String> normalPool = byType(usable, "NORMAL", "RARE", "TRAP", "CHAMPION");
        if (normalPool.isEmpty()) {
            // Nothing classified: use whatever is captured rather than refuse. A floor of unclassified rooms
            // is still a floor to walk.
            normalPool = new ArrayList<>(usable.keySet());
        }

        // Puzzles go anywhere that is not one of the three given rooms.
        List<int[]> free = new ArrayList<>();
        for (int[] c : shape) {
            if (!same(c, entrance) && !same(c, blood) && !same(c, fairy)) {
                free.add(c);
            }
        }
        Collections.shuffle(free, RNG);
        int wantPuzzles = Math.max(MIN_PUZZLES, Math.min(MAX_PUZZLES, puzzles));
        int placedPuzzles = 0;

        Set<Long> filled = new HashSet<>();
        Set<Long> inShape = new HashSet<>();
        for (int[] c : shape) {
            inShape.add(key(c));
        }
        int bigPlaced = 0;

        for (int[] c : shape) {
            if (filled.contains(key(c))) {
                continue;   // already covered by a larger room placed from an earlier cell
            }
            String pick;
            boolean given = false;
            if (same(c, entrance)) {
                pick = named(usable, "Entrance", normalPool);
                given = true;
            } else if (same(c, blood)) {
                pick = named(usable, "Blood", normalPool);
                given = true;
            } else if (same(c, fairy)) {
                pick = named(usable, "Fairy", normalPool);
                given = true;
            } else if (placedPuzzles < wantPuzzles && !puzzlePool.isEmpty()
                    && free.indexOf(c) < wantPuzzles && free.contains(c)) {
                pick = puzzlePool.get(RNG.nextInt(puzzlePool.size()));
                placedPuzzles++;
            } else {
                pick = normalPool.get(RNG.nextInt(normalPool.size()));
            }

            // How many cells this room needs, from what was captured. A room that will not fit here without
            // running off the map or over a neighbour is swapped for one that does, rather than squeezed in -
            // a 2x2 crammed into a 1x1 hole is the smeared mess this whole change exists to stop.
            int[] size = cellFootprint(usable.get(pick));
            List<int[]> cells = footprintCells(c, size, inShape, filled);
            if (cells == null && !given) {
                String smaller = firstThatFits(usable, normalPool, c, inShape, filled);
                if (smaller != null) {
                    pick = smaller;
                    size = cellFootprint(usable.get(pick));
                    cells = footprintCells(c, size, inShape, filled);
                }
            }
            if (cells == null) {
                cells = List.of(c);   // one cell, even if the room is bigger - better a room than a hole
            }
            if (cells.size() > 1) {
                bigPlaced++;
            }

            int idx = nameTable.indexOf(pick);
            if (idx < 0) {
                nameTable.add(pick);
                idx = nameTable.size() - 1;
            }
            // Every cell of the footprint carries the same room, and the builder pastes it once across them.
            // One rotation for the whole room, or its halves would face different ways.
            int rotation = RNG.nextInt(4) * 90;
            for (int[] fc : cells) {
                filled.add(key(fc));
                int cell = gridCell(fc);
                cellRoom[cell] = idx;
                cellRotation[cell] = rotation;
                cellDoor[cell] = same(fc, entrance) ? DungeonLayout.DOOR_ENTRANCE
                        : same(fc, blood) ? DungeonLayout.DOOR_BLOOD
                        : DungeonLayout.DOOR_NORMAL;
            }
        }

        LOGGER.info("[SimPhase] layout planned in {} ms", System.currentTimeMillis() - planStart);
        String code = MapCode.encodeDecoded(new MapCode.Decoded(
                nameTable.toArray(new String[0]), cellRoom, cellDoor, cellRotation));
        ModChat.send("Sim", ModChat.text(floor.label + ": "), ModChat.value(String.valueOf(nameTable.size())),
                ModChat.text(" rooms, "), ModChat.value(String.valueOf(placedPuzzles)),
                ModChat.text(" puzzle(s), blood "), ModChat.value(String.valueOf(
                        distance.getOrDefault(key(blood), 0))), ModChat.text(" rooms in"));
        if (bigPlaced > 0) {
            ModChat.send("Sim", ModChat.dim(bigPlaced + " room(s) larger than 1x1"));
        }
        SimWorld.open(client, code, c -> SimBuilder.build(c, code), "Generating " + floor.label);
    }

    /**
     * How many grid cells a captured room covers, as {width, height}.
     *
     * <p>Read from the capture rather than from the database's shape string, because the capture is what will
     * actually be pasted. If those two ever disagree the paste wins, so the layout has to be planned against
     * it - planning against the database and pasting the capture is how you get a room overlapping its
     * neighbour.
     */
    private static int[] cellFootprint(RoomLibrary.Room r) {
        if (r == null) {
            return new int[]{1, 1};
        }
        return new int[]{tilesAcross(r.sizeX), tilesAcross(r.sizeZ)};
    }

    /** Tiles spanned by a captured dimension - the wall margin is not a tile. */
    private static int tilesAcross(int size) {
        int tiles = Math.max(1, (size - 2) / RoomLibrary.TILE);
        // A tile span covers the door cells between rooms too, so N tiles is (N+1)/2 rooms across.
        return Math.max(1, (tiles + 1) / 2);
    }

    /**
     * The cells a room of this footprint would occupy starting here, or null if it does not fit.
     *
     * <p>It must fit entirely inside the shape that was grown and touch nothing already placed. Anchored at the
     * top-left, which is the corner the capture measured from.
     */
    private static List<int[]> footprintCells(int[] at, int[] size, Set<Long> inShape, Set<Long> filled) {
        List<int[]> cells = new ArrayList<>();
        for (int dx = 0; dx < size[0]; dx++) {
            for (int dz = 0; dz < size[1]; dz++) {
                int[] c = {at[0] + dx, at[1] + dz};
                long k = key(c);
                if (!inShape.contains(k) || filled.contains(k)) {
                    return null;
                }
                cells.add(c);
            }
        }
        return cells;
    }

    /** A room from the pool small enough to fit at this cell, or null when even a 1x1 will not. */
    private static String firstThatFits(Map<String, RoomLibrary.Room> usable, List<String> pool, int[] at,
                                        Set<Long> inShape, Set<Long> filled) {
        List<String> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, RNG);
        for (String name : shuffled) {
            if (footprintCells(at, cellFootprint(usable.get(name)), inShape, filled) != null) {
                return name;
            }
        }
        return null;
    }

    private static List<String> byType(Map<String, RoomLibrary.Room> usable, String... types) {
        Set<String> want = new HashSet<>(List.of(types));
        List<String> out = new ArrayList<>();
        for (String name : usable.keySet()) {
            RoomEntry e = RoomDatabase.lookupByName(name);
            if (e != null && e.type != null && want.contains(e.type.toUpperCase(Locale.ROOT))) {
                out.add(name);
            }
        }
        return out;
    }

    /** The named room if it was captured, otherwise something from the pool rather than a hole in the map. */
    private static String named(Map<String, RoomLibrary.Room> usable, String want, List<String> fallback) {
        for (String name : usable.keySet()) {
            if (name.equalsIgnoreCase(want)) {
                return name;
            }
        }
        return fallback.get(RNG.nextInt(fallback.size()));
    }

    /**
     * Grows a connected blob of room cells.
     *
     * <p>Grown outward from one cell rather than scattered and joined afterwards, because connectivity is the
     * property that matters - a floor with an unreachable wing is not a harder floor, it is a broken one.
     */
    private static List<int[]> growConnectedShape(int want) {
        List<int[]> chosen = new ArrayList<>();
        Set<Long> taken = new HashSet<>();
        int[] start = {RNG.nextInt(ROOM_GRID), RNG.nextInt(ROOM_GRID)};
        chosen.add(start);
        taken.add(key(start));

        Deque<int[]> frontier = new ArrayDeque<>();
        frontier.add(start);
        while (chosen.size() < want && !frontier.isEmpty()) {
            int[] from = frontier.peek();
            List<int[]> options = new ArrayList<>();
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = from[0] + d[0];
                int nz = from[1] + d[1];
                if (nx < 0 || nz < 0 || nx >= ROOM_GRID || nz >= ROOM_GRID) {
                    continue;
                }
                int[] n = {nx, nz};
                if (!taken.contains(key(n))) {
                    options.add(n);
                }
            }
            if (options.isEmpty()) {
                frontier.poll();
                continue;
            }
            int[] next = options.get(RNG.nextInt(options.size()));
            chosen.add(next);
            taken.add(key(next));
            frontier.push(next);
        }
        return chosen;
    }

    private static Map<Long, Integer> distancesFrom(List<int[]> shape, int[] start) {
        Set<Long> inShape = new HashSet<>();
        for (int[] c : shape) {
            inShape.add(key(c));
        }
        Map<Long, Integer> dist = new HashMap<>();
        Deque<int[]> queue = new ArrayDeque<>();
        dist.put(key(start), 0);
        queue.add(start);
        while (!queue.isEmpty()) {
            int[] at = queue.poll();
            int d = dist.get(key(at));
            for (int[] step : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int[] n = {at[0] + step[0], at[1] + step[1]};
                long k = key(n);
                if (inShape.contains(k) && !dist.containsKey(k)) {
                    dist.put(k, d + 1);
                    queue.add(n);
                }
            }
        }
        return dist;
    }

    private static int[] cellAtDistance(List<int[]> shape, Map<Long, Integer> dist, int want) {
        int[] best = null;
        int bestD = -1;
        for (int[] c : shape) {
            int d = dist.getOrDefault(key(c), -1);
            if (d == want) {
                return c;
            }
            if (d > bestD) {
                bestD = d;
                best = c;
            }
        }
        return best == null ? shape.get(shape.size() - 1) : best;
    }

    private static int[] pickAwayFrom(List<int[]> shape, int[] a, int[] b) {
        List<int[]> options = new ArrayList<>();
        for (int[] c : shape) {
            if (!same(c, a) && !same(c, b)) {
                options.add(c);
            }
        }
        return options.isEmpty() ? shape.get(shape.size() - 1) : options.get(RNG.nextInt(options.size()));
    }

    /** Room cell to the 11x11 grid index - rooms live on even coordinates. */
    private static int gridCell(int[] c) {
        return (c[1] * 2) * DungeonLayout.GRID + (c[0] * 2);
    }

    private static long key(int[] c) {
        return ((long) c[0] << 32) ^ (c[1] & 0xffffffffL);
    }

    private static boolean same(int[] a, int[] b) {
        return a[0] == b[0] && a[1] == b[1];
    }
}

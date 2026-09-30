package com.killer560.hub.roomsim;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a captured room's doorways actually are.
 *
 * <p>killer560 (2026-09-29): "it should read which rooms have doors where and make sure that each room is
 * reachable via going through doors into rooms. Some rooms only have one door so they have to be at the end of
 * a split like puzzles and trap and some other rooms."
 *
 * <p>Until now the sim ignored room geometry entirely when laying a floor out: it grew a blob of cells, dropped
 * rooms into them, and then {@link SimDoors#carveDoorway} punched a hole through whatever wall happened to be
 * between two of them. That produces a floor you can walk, but not a dungeon - a real room has its doorways
 * built into it, in fixed places, and a floor is the puzzle of fitting those together. It also meant a puzzle
 * room could sit in the middle of the map with four holes knocked in it, when on Hypixel a one-door room is
 * always the end of a branch.
 *
 * <p><b>The doorways are measured, not declared.</b> Nothing in the room database says where a room's doors
 * are, so this reads them off the blocks he captured. A Catacombs doorway is three wide and four high, centred
 * on a tile's edge midpoint, cut through the room's own perimeter wall - so for every tile edge on the
 * perimeter this looks at that 3x4 window and asks whether it is a doorway or wall.
 *
 * <p>"Doorway" is not the same as "air". A wither door is a slab of coal blocks and a blood door is red
 * terracotta, and both are doorways that happen to be shut; the first version of this checked for air alone and
 * decided Spikes, Staircase and Arrow Trap had no doors at all. The y is searched rather than assumed for the
 * same class of reason - not every room's floor sits at the same height, and a fixed y found Blood's walls
 * halfway up a pit.
 *
 * <p>Verified against his 135 captured rooms: 133 of them come out with between one and six doorways, the
 * one-door set is exactly the puzzles and the given rooms (Boulder, Ice Fill, Ice Path, Quiz, Entrance, Fairy
 * and the like), and the two that report none are Blood and Higher Blaze, whose entrances are not cut through a
 * perimeter wall at floor height. Those two are handled by the caller rather than forced into this shape.
 */
public final class RoomDoors {

    /** Sides, in clockwise order starting at -Z, so {@code (side + 1) % 4} is one quarter turn clockwise. */
    public static final int NORTH = 0;
    public static final int EAST = 1;
    public static final int SOUTH = 2;
    public static final int WEST = 3;

    /** Cell steps per side, indexed by the constants above. */
    public static final int[] DX = {0, 1, 0, -1};
    public static final int[] DZ = {-1, 0, 1, 0};

    /**
     * Blocks that can stand in a doorway.
     *
     * <p>Air is an open one. Coal block is a shut wither door and red terracotta a shut blood door - both are
     * doorways, and treating them as wall is what made three of his rooms look sealed. Infested chiseled stone
     * brick is the entrance gate, for the same reason.
     */
    private static final Set<String> DOORWAY_BLOCKS = Set.of(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
            "minecraft:coal_block", "minecraft:red_terracotta",
            "minecraft:infested_chiseled_stone_bricks");

    /** A doorway is three wide and four high; 11 of those 12 blocks must be doorway material. */
    private static final int DOOR_WIDTH = 3;
    private static final int DOOR_HEIGHT = 4;
    private static final int DOOR_MIN_MATCH = 11;

    /** The y range a doorway's floor can sit at. Wider than any room needs, and cheap - it is one column. */
    private static final int SEARCH_MIN_Y = 64;
    private static final int SEARCH_MAX_Y = 86;

    /** Highest tile index a side can hold, which bounds the packed edge key below. */
    private static final int MAX_TILES = 8;

    private static final Map<String, Mask> CACHE = new ConcurrentHashMap<>();

    private RoomDoors() {
    }

    /**
     * One room's doorways, at one rotation.
     *
     * @param tilesX tiles across on X at this rotation
     * @param tilesZ tiles across on Z at this rotation
     * @param edges  packed {@code side * MAX_TILES + index} keys - see {@link #edge}
     */
    public record Mask(int tilesX, int tilesZ, Set<Integer> edges) {

        /** Whether this room has a doorway on {@code side} at tile {@code index} along it. */
        public boolean has(int side, int index) {
            return edges.contains(edge(side, index));
        }
    }

    /** Packs a side and an index along it into one key. */
    public static int edge(int side, int index) {
        return side * MAX_TILES + index;
    }

    public static int sideOf(int packed) {
        return packed / MAX_TILES;
    }

    public static int indexOf(int packed) {
        return packed % MAX_TILES;
    }

    /**
     * The doorways of a captured room, measured once and remembered.
     *
     * @return the mask, or null when the room is unknown or not in the current capture format
     */
    public static Mask of(String name) {
        if (name == null) {
            return null;
        }
        Mask cached = CACHE.get(name);
        if (cached != null) {
            return cached;
        }
        RoomLibrary.Room room = RoomLibrary.get(name);
        if (room == null || !room.usable()) {
            return null;
        }
        Mask fresh = detect(room);
        if (fresh.edges().isEmpty()) {
            // ONE door, on one side, which the layout's rotation is free to point anywhere.
            //
            // Blood and Higher Blaze are the only two rooms this cannot measure: their captured perimeters are
            // solid stone at every height from 60 to 140, checked column by column, so whatever way in they
            // have was not cut through the wall the capture took in. killer560 (2026-09-29): "For blood there
            // is 1 door on it", and Higher Blaze is the upper entrance to a two-level blaze room - "high you
            // enter near the top on the door side".
            //
            // A single doorway is therefore right and four was wrong: four made the blood room a junction the
            // floor could route THROUGH, when blood is the end of the clear. The builder carves the opening,
            // as it did for every door before doorways were measured at all.
            fresh = new Mask(fresh.tilesX(), fresh.tilesZ(), Set.of(edge(NORTH, 0)));
        }
        CACHE.put(name, fresh);
        return fresh;
    }

    /** Forgets every measurement. Called when the library is reloaded, or the masks would outlive their rooms. */
    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * The same room turned clockwise.
     *
     * <p>A quarter turn takes a room of {@code tilesX x tilesZ} to {@code tilesZ x tilesX} and carries every
     * doorway round with it: north becomes east, east becomes south, and so on. The INDEX along the side turns
     * too, and in two of the four cases it reverses - which is the part worth writing down, because getting it
     * wrong produces a floor that looks right and has its doors a tile out.
     *
     * <p>Derived from {@link RoomPlacer#rotateLocal}, which is the transform the paste itself uses: at 90
     * degrees a local {@code (x, z)} becomes {@code (sizeZ - 1 - z, x)}. Applying that to a tile at
     * {@code (i, j)} gives {@code (tilesZ - 1 - j, i)}, and reading off which side each edge tile lands on
     * gives the four cases below. Tying it to the paste's own formula rather than re-deriving it means the
     * doors cannot disagree with the blocks.
     */
    public static Mask rotate(Mask mask, int degrees) {
        int turns = ((degrees / 90) % 4 + 4) % 4;
        Mask current = mask;
        for (int t = 0; t < turns; t++) {
            Set<Integer> next = new LinkedHashSet<>();
            for (int packed : current.edges()) {
                int side = sideOf(packed);
                int index = indexOf(packed);
                next.add(switch (side) {
                    case NORTH -> edge(EAST, index);
                    case EAST -> edge(SOUTH, current.tilesZ() - 1 - index);
                    case SOUTH -> edge(WEST, index);
                    default -> edge(NORTH, current.tilesZ() - 1 - index);
                });
            }
            current = new Mask(current.tilesZ(), current.tilesX(), next);
        }
        return current;
    }

    /**
     * The cell a doorway opens FROM, given where the room's top-left cell sits.
     *
     * @return {@code {cellX, cellZ}} of the room's own cell that the doorway is in
     */
    public static int[] doorCell(int originX, int originZ, int tilesX, int tilesZ, int side, int index) {
        return switch (side) {
            case NORTH -> new int[]{originX + index, originZ};
            case SOUTH -> new int[]{originX + index, originZ + tilesZ - 1};
            case WEST -> new int[]{originX, originZ + index};
            default -> new int[]{originX + tilesX - 1, originZ + index};
        };
    }

    /** Every doorway of a placed room, as {@code {cellX, cellZ, side}}. */
    public static List<int[]> doorCells(Mask mask, int originX, int originZ) {
        List<int[]> out = new ArrayList<>();
        for (int packed : mask.edges()) {
            int side = sideOf(packed);
            int[] c = doorCell(originX, originZ, mask.tilesX(), mask.tilesZ(), side, indexOf(packed));
            out.add(new int[]{c[0], c[1], side});
        }
        return out;
    }

    /**
     * Reads a room's perimeter and returns the doorways in it.
     *
     * <p>The wall line is at {@code margin} blocks in from the captured window's edge on each side - the window
     * deliberately takes in one column of the shared wall (see {@link RoomLibrary#WALL_MARGIN}), so the wall
     * itself is the next column in. Tile centres sit {@code margin + TILE/2} in and every {@code TILE + 1}
     * after that, which is the same arithmetic {@link RoomPlacer} pastes with.
     */
    private static Mask detect(RoomLibrary.Room room) {
        int tilesX = Math.max(1, (room.sizeX - 1) / (RoomLibrary.TILE + 1));
        int tilesZ = Math.max(1, (room.sizeZ - 1) / (RoomLibrary.TILE + 1));
        int margin = room.margin;
        Set<Integer> edges = new LinkedHashSet<>();

        for (int i = 0; i < tilesX; i++) {
            int cx = margin + RoomLibrary.TILE / 2 + i * (RoomLibrary.TILE + 1);
            if (openAlongX(room, cx, margin)) {
                edges.add(edge(NORTH, i));
            }
            if (openAlongX(room, cx, room.sizeZ - 1 - margin)) {
                edges.add(edge(SOUTH, i));
            }
        }
        for (int j = 0; j < tilesZ; j++) {
            int cz = margin + RoomLibrary.TILE / 2 + j * (RoomLibrary.TILE + 1);
            if (openAlongZ(room, margin, cz)) {
                edges.add(edge(WEST, j));
            }
            if (openAlongZ(room, room.sizeX - 1 - margin, cz)) {
                edges.add(edge(EAST, j));
            }
        }
        return new Mask(tilesX, tilesZ, dropStaircase(room, edges));
    }

    /**
     * The Entrance's back staircase is not a door.
     *
     * <p>killer560 (2026-09-29): "for entrance the second picture shows the front. That long staircase is the
     * back and nothing can attach there." The detector was finding TWO ways out of the Entrance and letting
     * the generator hang a room off either, so half the floors grew out of the back of the green room.
     *
     * <p>Measured on his own capture rather than assumed. Of the two, one is a doorway and one is not:
     *
     * <pre>
     * WEST  door box 3x4: 12/12 air   surrounding 11x12:  12/132 air   &lt;- a doorway
     * EAST  door box 3x4:  0/12 air   surrounding 11x12:  39/132 air   &lt;- solid at floor level
     * </pre>
     *
     * The east side is solid where a door would be and open higher up, because the staircase RISES - and the
     * search walks y from {@value #SEARCH_MIN_Y} to {@value #SEARCH_MAX_Y} looking for any 3x4 opening, so it
     * matched the stairwell partway up. A real doorway sits at the room's floor.
     *
     * <p>So for the Entrance only, the doorway that matches LOWEST wins and the rest are dropped. It is
     * scoped to the Entrance deliberately: Higher and Lower Blaze genuinely have doors at unusual heights,
     * and a blanket "lowest only" rule would break them.
     */
    private static Set<Integer> dropStaircase(RoomLibrary.Room room, Set<Integer> edges) {
        if (!"entrance".equalsIgnoreCase(room.name) || edges.size() <= 1) {
            return edges;
        }
        int best = -1;
        int bestY = Integer.MAX_VALUE;
        for (int e : edges) {
            int y = lowestMatch(room, sideOf(e), indexOf(e));
            if (y < bestY) {
                bestY = y;
                best = e;
            }
        }
        return best < 0 ? edges : Set.of(best);
    }

    /** The lowest y at which this edge's doorway matches, or {@code Integer.MAX_VALUE} if it never does. */
    private static int lowestMatch(RoomLibrary.Room room, int side, int index) {
        int margin = room.margin;
        int tilesX = Math.max(1, (room.sizeX - 1) / (RoomLibrary.TILE + 1));
        int centre = margin + index * (RoomLibrary.TILE + 1) + RoomLibrary.TILE / 2;
        for (int base = SEARCH_MIN_Y; base <= SEARCH_MAX_Y - DOOR_HEIGHT; base++) {
            int hits = 0;
            for (int d = -(DOOR_WIDTH / 2); d <= DOOR_WIDTH / 2; d++) {
                for (int y = base; y < base + DOOR_HEIGHT; y++) {
                    boolean open = switch (side) {
                        case NORTH -> isDoorway(room, centre + d, y, margin);
                        case SOUTH -> isDoorway(room, centre + d, y, room.sizeZ - 1 - margin);
                        case WEST -> isDoorway(room, margin, y, centre + d);
                        default -> isDoorway(room, room.sizeX - 1 - margin, y, centre + d);
                    };
                    if (open) {
                        hits++;
                    }
                }
            }
            if (hits >= DOOR_MIN_MATCH) {
                return base;
            }
        }
        return Integer.MAX_VALUE;
    }

    /** A doorway in a wall that runs along X - so the three blocks vary in X and the wall's Z is fixed. */
    private static boolean openAlongX(RoomLibrary.Room room, int centreX, int wallZ) {
        for (int base = SEARCH_MIN_Y; base <= SEARCH_MAX_Y - DOOR_HEIGHT; base++) {
            int hits = 0;
            for (int dx = -(DOOR_WIDTH / 2); dx <= DOOR_WIDTH / 2; dx++) {
                for (int y = base; y < base + DOOR_HEIGHT; y++) {
                    if (isDoorway(room, centreX + dx, y, wallZ)) {
                        hits++;
                    }
                }
            }
            if (hits >= DOOR_MIN_MATCH) {
                return true;
            }
        }
        return false;
    }

    /** A doorway in a wall that runs along Z. */
    private static boolean openAlongZ(RoomLibrary.Room room, int wallX, int centreZ) {
        for (int base = SEARCH_MIN_Y; base <= SEARCH_MAX_Y - DOOR_HEIGHT; base++) {
            int hits = 0;
            for (int dz = -(DOOR_WIDTH / 2); dz <= DOOR_WIDTH / 2; dz++) {
                for (int y = base; y < base + DOOR_HEIGHT; y++) {
                    if (isDoorway(room, wallX, y, centreZ + dz)) {
                        hits++;
                    }
                }
            }
            if (hits >= DOOR_MIN_MATCH) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether one captured block could be part of a doorway.
     *
     * <p>A never-read block counts as one. A complete room has none, and {@link #of} only measures complete
     * rooms, so this only matters if that ever stops being true - and an unknown block is better read as a
     * doorway than as a wall, because a doorway that turns out to be wall is a sealed opening while a wall that
     * turns out to be a doorway is a hole into the void.
     */
    private static boolean isDoorway(RoomLibrary.Room room, int x, int y, int z) {
        if (x < 0 || z < 0 || x >= room.sizeX || z >= room.sizeZ
                || y < room.minY || y > room.maxY) {
            return true;
        }
        short idx = room.blocks[room.index(x, y, z)];
        if (idx < 0 || idx >= room.palette.size()) {
            return true;
        }
        String state = room.palette.get(idx);
        int bracket = state.indexOf('[');
        return DOORWAY_BLOCKS.contains(bracket < 0 ? state : state.substring(0, bracket));
    }
}

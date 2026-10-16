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
 * decided Spikes, Staircase and Arrow Trap had no doors at all.
 *
 * <p>The height is NOT searched (since 2026-10-15): a doorway is the window at {@link #DOOR_FLOOR_Y}, framed by wall,
 * because that is where every Hypixel doorway is and where every one of the 277 in the shipped captures measures. The
 * search it replaced (any opening from y64 to y86) found three openings that are not doors and the open edges of the
 * L rooms' empty quarters - see the two constants.
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

    /**
     * The floor of every Catacombs doorway, in capture (Hypixel) coordinates: the opening is y 69..72.
     *
     * <p>Hypixel's doors sit at one fixed height on the 32-block grid (DungeonLayout's door scan, the wither and blood
     * doors, every connector), and the captures agree exactly: swept 2026-10-15 over the 135 shipped captures, all 277
     * perimeter doorways measure at this floor and no other, each with wall on both sides of the opening and a lintel
     * over it. What used to be searched (any 3x4 opening from y64 to y86) also matched three openings that are not
     * doors - Entrance's west wall above its back staircase (y76 up, open to the sky), Mage's north wall on its second
     * tile (y72..87, which is a tile of ALTAR in that capture: its centre column hashes to Altar's core in the room
     * database), and a 5x4 red terracotta panel in Quad Lava's east wall at y77..80 (red terracotta reads as a shut blood
     * door). The layout linked rooms through the last two, and the carve at the doorway floor then cut into the solid
     * wall below the opening (testkit 1441-sim-door-height).
     * {@link SimDoors#carveDoorway} cuts here; {@link #carvable} asks for floor here.
     */
    public static final int DOOR_FLOOR_Y = 69;

    /**
     * Of the eleven blocks framing an opening (two columns of four either side, three over it), how many must be wall.
     * Every real doorway has all eleven; an edge of an L room's missing quarter, which is open air from top to bottom,
     * has none - twenty of those were being read as doorways, two in each of the L rooms Altar, Chambers, Dino Site, Lava
     * Ravine, Layers, Melon, Pirate, Spider, Well and Withermancer (generated floors leave L rooms out; a drawn or
     * pinned one could be linked through its empty quarter).
     */
    private static final int DOOR_MIN_FRAME = 8;

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
            // Blood and Higher Blaze were the two rooms this could not measure: their captured perimeters were
            // solid stone at every height from 60 to 140, checked column by column, so whatever way in they
            // had was not cut through the wall the capture took in. (Their current captures measure a doorway at
            // the doorway floor, as every shipped capture does - 2026-10-15 - so this is a fallback for a capture
            // that does not.) killer560 (2026-09-29): "For blood there
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

    /**
     * The tile edges of a room's perimeter a doorway can be CARVED through and walked both ways: a 3x4 opening cut
     * at the doorway floor ({@link #DOOR_FLOOR_Y}, where every carve in the sim lands) reaches three blocks in from the seam, and
     * {@code SimDoors.carveDoorway} floors it; this asks whether, past the cut, the room has somewhere to stand
     * within one block of that floor - solid underfoot, nothing in the feet and head blocks. In the capture's own
     * frame, so rotate it with {@link #rotate} exactly like the doorway mask.
     *
     * <p>For {@code SimFloorLayout}'s last-resort fill, which carves a door through a neighbour's wall where the
     * neighbour has no doorway. Measured 2026-10-04 with tools/bench/FloorBench over 40 floors: 27 rooms were put
     * in that way, and clicks into or out of 8 of them failed for the etherwarp planner - Criss Cross through the
     * Entrance landed against its two-block platform, Rail Track through Three Floors in a drop - which was 54 of
     * the 88 clicks the Interactive Map's planner failed.
     */
    public static Mask carvable(String name) {
        if (name == null) {
            return null;
        }
        Mask cached = CARVABLE.get(name);
        if (cached != null) {
            return cached;
        }
        RoomLibrary.Room room = RoomLibrary.get(name);
        if (room == null || !room.usable()) {
            return null;
        }
        int tilesX = Math.max(1, (room.sizeX - 1) / (RoomLibrary.TILE + 1));
        int tilesZ = Math.max(1, (room.sizeZ - 1) / (RoomLibrary.TILE + 1));
        int m = room.margin;
        Set<Integer> edges = new LinkedHashSet<>();
        for (int i = 0; i < tilesX; i++) {
            int cx = m + RoomLibrary.TILE / 2 + i * (RoomLibrary.TILE + 1);
            if (standBehind(room, cx, m, 1, 0, 0, 1)) {
                edges.add(edge(NORTH, i));
            }
            if (standBehind(room, cx, room.sizeZ - 1 - m, 1, 0, 0, -1)) {
                edges.add(edge(SOUTH, i));
            }
        }
        for (int j = 0; j < tilesZ; j++) {
            int cz = m + RoomLibrary.TILE / 2 + j * (RoomLibrary.TILE + 1);
            if (standBehind(room, m, cz, 0, 1, 1, 0)) {
                edges.add(edge(WEST, j));
            }
            if (standBehind(room, room.sizeX - 1 - m, cz, 0, 1, -1, 0)) {
                edges.add(edge(EAST, j));
            }
        }
        Mask fresh = new Mask(tilesX, tilesZ, edges);
        CARVABLE.put(name, fresh);
        return fresh;
    }

    private static final Map<String, Mask> CARVABLE = new ConcurrentHashMap<>();

    /**
     * From the wall at {@code (wallX, wallZ)}, stepping inwards by {@code (inX, inZ)}: along any of the opening's three
     * columns ({@code (alongX, alongZ)}), a floor he can walk on from three blocks in from the wall (the first one
     * the carve does not reach) to six, starting within one block of the carve's floor and never stepping more
     * than one block up or down. A one-block ledge in front of a pit (Jumping Skulls) does not count.
     */
    private static boolean standBehind(RoomLibrary.Room room, int wallX, int wallZ, int alongX, int alongZ,
                                       int inX, int inZ) {
        for (int w = -1; w <= 1; w++) {
            int feet = DOOR_FLOOR_Y;
            boolean ok = true;
            for (int in = 3; in <= 6 && ok; in++) {
                int x = wallX + inX * in + alongX * w;
                int z = wallZ + inZ * in + alongZ * w;
                ok = false;
                for (int k : new int[]{0, 1, -1}) {
                    int f = feet + k;
                    if (!isOpen(room, x, f - 1, z) && isOpen(room, x, f, z) && isOpen(room, x, f + 1, z)) {
                        feet = f;
                        ok = true;
                        break;
                    }
                }
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }

    /** Air, or something a player walks through. A never-read block is NOT open here (unlike isDoorway). */
    private static boolean isOpen(RoomLibrary.Room room, int x, int y, int z) {
        if (x < 0 || z < 0 || x >= room.sizeX || z >= room.sizeZ || y < room.minY || y > room.maxY) {
            return false;
        }
        short idx = room.blockAt(room.index(x, y, z));
        if (idx < 0 || idx >= room.palette.size()) {
            return false;
        }
        String state = room.palette.get(idx);
        int bracket = state.indexOf('[');
        String id = bracket < 0 ? state : state.substring(0, bracket);
        return id.endsWith(":air") || id.endsWith("_air") || id.endsWith("carpet") || id.endsWith("torch")
                || id.endsWith("_button") || id.endsWith(":lever") || id.endsWith("_sign") || id.endsWith("rail")
                || id.endsWith(":vine") || id.endsWith(":ladder") || id.endsWith(":short_grass")
                || id.endsWith(":tall_grass") || id.endsWith(":cobweb") || id.endsWith("_pressure_plate");
    }

    /** Forgets every measurement. Called when the library is reloaded, or the masks would outlive their rooms. */
    public static void clearCache() {
        CARVABLE.clear();
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
    /** {@link #of}'s measurement for a room that is not (yet) in the library - {@link RoomCapture}'s check. No cache. */
    static Mask measure(RoomLibrary.Room room) {
        return detect(room);
    }

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
        return new Mask(tilesX, tilesZ, edges);
    }

    /** A doorway in a wall that runs along X - so the three blocks vary in X and the wall's Z is fixed. */
    private static boolean openAlongX(RoomLibrary.Room room, int centreX, int wallZ) {
        return framedOpening((u, y) -> isDoorway(room, centreX + u, y, wallZ));
    }

    /** A doorway in a wall that runs along Z. */
    private static boolean openAlongZ(RoomLibrary.Room room, int wallX, int centreZ) {
        return framedOpening((u, y) -> isDoorway(room, wallX, y, centreZ + u));
    }

    /**
     * A doorway: the 3x4 window at {@link #DOOR_FLOOR_Y} is at least {@value #DOOR_MIN_MATCH} of 12 doorway material,
     * and at least {@value #DOOR_MIN_FRAME} of the 11 blocks round it are wall. {@code open.test(u, y)} reads the wall
     * plane at {@code u} blocks along it from the edge's centre.
     *
     * <p>Only that one height. The Entrance's back staircase used to need its own rule ("the doorway that matches
     * LOWEST wins", 2026-09-29) because the search walked y up to 86 and matched the stairwell; at one height it is not
     * a candidate at all, and neither is anything else above the doorway floor.
     */
    private static boolean framedOpening(java.util.function.BiPredicate<Integer, Integer> open) {
        int hits = 0;
        for (int u = -(DOOR_WIDTH / 2); u <= DOOR_WIDTH / 2; u++) {
            for (int y = DOOR_FLOOR_Y; y < DOOR_FLOOR_Y + DOOR_HEIGHT; y++) {
                if (open.test(u, y)) {
                    hits++;
                }
            }
        }
        if (hits < DOOR_MIN_MATCH) {
            return false;
        }
        int frame = 0;
        for (int y = DOOR_FLOOR_Y; y < DOOR_FLOOR_Y + DOOR_HEIGHT; y++) {
            if (!open.test(-(DOOR_WIDTH / 2) - 1, y)) {
                frame++;
            }
            if (!open.test(DOOR_WIDTH / 2 + 1, y)) {
                frame++;
            }
        }
        for (int u = -(DOOR_WIDTH / 2); u <= DOOR_WIDTH / 2; u++) {
            if (!open.test(u, DOOR_FLOOR_Y + DOOR_HEIGHT)) {
                frame++;
            }
        }
        return frame >= DOOR_MIN_FRAME;
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
        short idx = room.blockAt(room.index(x, y, z));
        if (idx < 0 || idx >= room.palette.size()) {
            return true;
        }
        String state = room.palette.get(idx);
        int bracket = state.indexOf('[');
        return DOORWAY_BLOCKS.contains(bracket < 0 ? state : state.substring(0, bracket));
    }
}

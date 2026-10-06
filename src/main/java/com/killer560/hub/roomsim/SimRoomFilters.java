package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModPaths;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The sim's three room filters, and the Map Designer's questions about its own.
 *
 * <p>Each list that picks rooms has its own {@link SimRoomFilter}, saved in its own file, all edited by the one
 * Filters panel ({@link SimRoomFilterScreen}) and tested by the one {@link SimRoomFilter#matches}:
 * <ul>
 *   <li>{@link #DESIGNER} - the Map Designer's room list, Fill (which draws from the list as shown) and Generate
 *       (killer560, 2026-10-06: "in the generate a map thing have a filter section where i can filter based on things
 *       like puzzles, room size, secrets in a room, etc.");</li>
 *   <li>{@link #PICKER} - the Load a Room list;</li>
 *   <li>{@link #CYCLE} - which rooms All Rooms' {@code /next} and {@code /back} step through.</li>
 * </ul>
 *
 * <p>Generate reads the designer's filter through {@link #generatorAllows}: the same test, except that the four rooms
 * every floor must have - Entrance, Blood, Fairy and the trap - are never filtered out, so a filter can narrow a floor
 * but never make it invalid. A room he pinned on the grid is always kept as well ({@link SimFloorGen}'s job).
 */
public final class SimRoomFilters {

    public static final SimRoomFilter DESIGNER = new SimRoomFilter(SimRoomFilter.Use.DESIGNER,
            ModPaths.config("killer560smod-sim-designer-filters.json"));
    public static final SimRoomFilter PICKER = new SimRoomFilter(SimRoomFilter.Use.PICKER,
            ModPaths.config("killer560smod-sim-picker-filters.json"));
    public static final SimRoomFilter CYCLE = new SimRoomFilter(SimRoomFilter.Use.CYCLE,
            ModPaths.config("killer560smod-sim-allrooms-filters.json"));

    /** The rooms a generated floor must have; Generate ignores the filters for these. */
    public static final Set<String> REQUIRED = Set.of("ENTRANCE", "BLOOD", "FAIRY", "TRAP");

    private SimRoomFilters() {
    }

    // ------------------------------------------------------------------------------------------- the designer's

    /** Re-reads the designer's file. For the testkit. */
    public static void load() {
        DESIGNER.load();
    }

    /** The designer's filter back to none. */
    public static void reset() {
        DESIGNER.clear();
    }

    public static int activeCount() {
        return DESIGNER.activeCount();
    }

    public static boolean isDefault() {
        return DESIGNER.isDefault();
    }

    /** Whether the designer's room list shows this room. */
    public static boolean listMatches(String name) {
        return DESIGNER.matches(name);
    }

    /** Whether Generate may put this room on a floor: {@link #listMatches}, but never refusing a required room. */
    public static boolean generatorAllows(String name) {
        return REQUIRED.contains(typeKey(name)) || listMatches(name);
    }

    /**
     * How many puzzle rooms Generate can place: allowed by the designer's filter AND a single tile, since the
     * generator only places 1x1 puzzles ({@code SimFloorLayout.shortfalls} counts the same way).
     */
    public static int allowedPuzzleCount() {
        int n = 0;
        for (String name : puzzleNames()) {
            RoomLibrary.Room r = RoomLibrary.get(name);
            boolean single = r != null && Math.max(1, (r.sizeX - 1) / (RoomLibrary.TILE + 1)) == 1
                    && Math.max(1, (r.sizeZ - 1) / (RoomLibrary.TILE + 1)) == 1;
            if (single && listMatches(name)) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------- shared

    public static String typeKey(String name) {
        return SimRoomFilter.typeKey(name);
    }

    public static String shapeKey(String name) {
        return SimRoomFilter.shapeKey(name);
    }

    /** Usable rooms in the library, by name - the "M" of "N of M rooms". */
    public static List<String> usableNames() {
        List<String> out = new ArrayList<>();
        for (String name : RoomLibrary.names()) {
            RoomLibrary.Room r = RoomLibrary.get(name);
            if (r != null && r.usable()) {
                out.add(name);
            }
        }
        return out;
    }

    /** The puzzle rooms in the library, sorted - what the Puzzles row lists. */
    public static List<String> puzzleNames() {
        List<String> out = new ArrayList<>();
        for (String name : usableNames()) {
            if ("PUZZLE".equals(typeKey(name))) {
                out.add(name);
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }
}

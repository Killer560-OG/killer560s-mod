package com.killer560.hub.roomsim;

import java.util.Map;

/**
 * Relights the braziers a capture recorded unlit.
 *
 * <p>A Catacombs brazier is a hopper with fire standing on it. killer560 (2026-10-15), Deuces on Hypixel: the doorway
 * under the secret chest is flanked by two of them, both burning; the sim drew two cold gray bowls, because
 * {@code Dueces.json} holds air on top of all sixteen of its hoppers. It is not Deuces alone. Across the shipped
 * library 1,471 hoppers have air or fire above them: 372 fire and 1,099 air, in 68 rooms, and the two groups are the
 * same thing - wall-mounted and hanging 961 of the unlit against 285 of the lit, sitting 132 against 86 - with rooms
 * that hold both (Cages 11 lit and 9 not, Market 2 and 12, Entrance 1 and 5). Nothing about the hopper decides it; the
 * capture does. Fire is the one block a player puts out by hitting it, and the room database leaves it out of its
 * core hash for the same reason (RoomDatabase.IGNORED_CORE_BLOCKS), so a capture taken partway through a run keeps
 * whatever had not been knocked out yet.
 *
 * <p>So every hopper with AIR directly above it gets the fire back, in the state the lit ones were captured in. Only
 * air is replaced: an anvil, torch or second hopper on top is left alone (77 of them). Never in the room's perimeter
 * wall plane or outside it, where {@link RoomDoors} measures doorways and the seam belongs to the neighbor - no
 * brazier sits there, and fire in a wall plane would read as a blocked doorway.
 *
 * <p>Run once on the library at load ({@link RoomLibrary#load}), so the paste, the build audit and anything else
 * reading a room all see the same blocks - one answer per room.
 */
public final class RoomBraziers {

    /** Exactly the state 372 lit braziers were captured in. */
    static final String FIRE = "minecraft:fire[age=0,east=false,north=false,south=false,up=false,west=false]";

    private static volatile int lastLit;
    private static volatile int lastRooms;

    private RoomBraziers() {
    }

    /** Lights every brazier in the library; returns how many fires went in. */
    static int lightAll(Map<String, RoomLibrary.Room> rooms) {
        int lit = 0;
        int touched = 0;
        for (RoomLibrary.Room room : rooms.values()) {
            int n = light(room);
            if (n > 0) {
                lit += n;
                touched++;
            }
        }
        lastLit = lit;
        lastRooms = touched;
        return lit;
    }

    /** How many braziers the last library load lit, and in how many rooms. */
    public static int lastLit() {
        return lastLit;
    }

    public static int lastRooms() {
        return lastRooms;
    }

    /** Lights one room's unlit braziers in place; returns how many. */
    static int light(RoomLibrary.Room room) {
        if (room.blockCount() == 0 || room.palette.isEmpty()) {
            return 0;
        }
        boolean[] hopper = new boolean[room.palette.size()];
        boolean[] air = new boolean[room.palette.size()];
        boolean any = false;
        for (int i = 0; i < hopper.length; i++) {
            String state = room.palette.get(i);
            String id = state.indexOf('[') < 0 ? state : state.substring(0, state.indexOf('['));
            hopper[i] = id.equals("minecraft:hopper");
            air[i] = id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air");
            any |= hopper[i];
        }
        if (!any) {
            return 0;
        }
        int margin = room.margin;
        short fire = -1;
        int lit = 0;
        for (int y = room.minY; y < room.maxY; y++) {
            for (int z = margin + 1; z < room.sizeZ - 1 - margin; z++) {
                for (int x = margin + 1; x < room.sizeX - 1 - margin; x++) {
                    short here = room.blockAt(room.index(x, y, z));
                    if (here < 0 || here >= hopper.length || !hopper[here]) {
                        continue;
                    }
                    int above = room.index(x, y + 1, z);
                    short up = room.blockAt(above);
                    if (up < 0 || up >= air.length || !air[up]) {
                        continue;
                    }
                    if (fire < 0) {
                        fire = room.paletteFor(FIRE);
                    }
                    room.setBlockAt(above, fire);
                    lit++;
                }
            }
        }
        return lit;
    }
}

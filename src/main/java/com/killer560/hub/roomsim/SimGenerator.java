package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.util.ModChat;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Builds a map out of the rooms that have actually been captured.
 *
 * <p>killer560 asked for "how many puzzles how many rooms to blood and if I want specific rooms". Those two
 * numbers are what make a generated floor worth practising: a route is mostly decided by how far the blood door
 * is and how many puzzles are in the way, so they are the knobs rather than a seed.
 *
 * <p><b>It only ever uses rooms in the library.</b> Generating a map that names rooms nobody has captured would
 * produce a floor with holes in it, and holes look like the builder being broken rather than the library being
 * short. Until enough rooms exist it says so and generates what it can.
 */
public final class SimGenerator {

    private static final Random RNG = new Random();

    private SimGenerator() {
    }

    /**
     * Every captured room, in one line.
     *
     * <p>killer560 (2026-09-28): "make a preset for the map called all rooms where it is every room in a line
     * for route configuring." Not a dungeon anyone would clear - it is a workbench. Walking one line past every
     * room is how you write routes for rooms you rarely draw, without rerolling floors until one shows up.
     *
     * <p>The 11x11 grid holds fewer cells than he will eventually have rooms, so it lays out as many as fit and
     * says how many were left over rather than silently truncating.
     */
    public static void generateAllRooms(Minecraft client) {
        List<String> all = new ArrayList<>(RoomLibrary.names());
        all.removeIf(n -> {
            RoomLibrary.Room r = RoomLibrary.get(n);
            return r == null || !r.complete();
        });
        if (all.isEmpty()) {
            ModChat.send("Sim", ModChat.text("No complete rooms captured yet."));
            return;
        }
        int cells = DungeonLayout.GRID * DungeonLayout.GRID;
        int[] cellRoom = new int[cells];
        int[] cellDoor = new int[cells];
        int[] cellRotation = new int[cells];
        java.util.Arrays.fill(cellRoom, MapCode.NO_ROOM);

        List<String> nameTable = new ArrayList<>();
        int placed = 0;
        outer:
        for (int row = 0; row < DungeonLayout.GRID; row++) {
            for (int col = 0; col < DungeonLayout.GRID; col++) {
                if (placed >= all.size()) {
                    break outer;
                }
                String pick = all.get(placed);
                nameTable.add(pick);
                int cell = row * DungeonLayout.GRID + col;
                cellRoom[cell] = nameTable.size() - 1;
                cellDoor[cell] = DungeonLayout.DOOR_NORMAL;
                // Unrotated on purpose: this is for writing routes against a room's own layout, and a random
                // rotation would mean the route he writes does not match the room as he studied it.
                cellRotation[cell] = 0;
                placed++;
            }
        }
        String code = MapCode.encodeDecoded(new MapCode.Decoded(
                nameTable.toArray(new String[0]), cellRoom, cellDoor, cellRotation));
        ModChat.send("Sim", ModChat.text("All Rooms: "), ModChat.value(String.valueOf(placed)),
                ModChat.text(" room(s) laid out"));
        if (placed < all.size()) {
            ModChat.send("Sim", ModChat.dim((all.size() - placed) + " did not fit on the grid"));
        }
        SimWorld.open(client, code);
    }

    /**
     * Lays out a path of rooms from the entrance to the blood door, with puzzles spaced along it.
     *
     * <p>A corridor rather than a tree: the thing being practised is the order rooms are cleared in and the
     * route between them, and a generated branch nobody walks adds nothing to that.
     */
    public static void generate(Minecraft client, int puzzles, int roomsToBlood) {
        List<String> available = new ArrayList<>(RoomLibrary.names());
        available.removeIf(n -> {
            RoomLibrary.Room r = RoomLibrary.get(n);
            return r == null || !r.complete();
        });
        if (available.isEmpty()) {
            ModChat.send("Sim", ModChat.text("No complete rooms captured yet - "),
                    ModChat.dim("run the Room Recorder first."));
            return;
        }
        List<String> puzzleRooms = new ArrayList<>(available);
        puzzleRooms.removeIf(n -> !n.toLowerCase(Locale.ROOT).contains("puzzle"));

        int cells = DungeonLayout.GRID * DungeonLayout.GRID;
        int[] cellRoom = new int[cells];
        int[] cellDoor = new int[cells];
        int[] cellRotation = new int[cells];
        java.util.Arrays.fill(cellRoom, MapCode.NO_ROOM);

        List<String> nameTable = new ArrayList<>();
        int placedPuzzles = 0;
        int row = DungeonLayout.GRID / 2;
        for (int step = 0; step < Math.min(roomsToBlood, DungeonLayout.GRID); step++) {
            boolean wantPuzzle = placedPuzzles < puzzles && !puzzleRooms.isEmpty()
                    && step > 0 && step % Math.max(1, roomsToBlood / Math.max(1, puzzles)) == 0;
            List<String> pool = wantPuzzle ? puzzleRooms : available;
            String pick = pool.get(RNG.nextInt(pool.size()));
            if (wantPuzzle) {
                placedPuzzles++;
            }
            int nameIdx = nameTable.indexOf(pick);
            if (nameIdx < 0) {
                nameTable.add(pick);
                nameIdx = nameTable.size() - 1;
            }
            int cell = row * DungeonLayout.GRID + step;
            cellRoom[cell] = nameIdx;
            cellRotation[cell] = RNG.nextInt(4) * 90;
            // The last room's door is the blood door; everything before it is a normal one.
            cellDoor[cell] = step == Math.min(roomsToBlood, DungeonLayout.GRID) - 1
                    ? DungeonLayout.DOOR_BLOOD : DungeonLayout.DOOR_NORMAL;
        }

        String code = MapCode.encodeDecoded(new MapCode.Decoded(
                nameTable.toArray(new String[0]), cellRoom, cellDoor, cellRotation));
        ModChat.send("Sim", ModChat.text("Generated a map with "),
                ModChat.value(String.valueOf(placedPuzzles)), ModChat.text(" puzzle(s), "),
                ModChat.value(String.valueOf(Math.min(roomsToBlood, DungeonLayout.GRID))),
                ModChat.text(" rooms to blood."));
        SimWorld.open(client, code);
    }
}

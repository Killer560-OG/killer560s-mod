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
            // usable(), not complete(): an old-footprint room is a different size from the slot planned for
            // it, so it pastes over its neighbour.
            return r == null || !r.usable();
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
        int skipped = 0;
        // EVEN cells only, and a room's own connectors filled.
        //
        // This walked every cell, so each 33-block room was placed 16 blocks from the last and every room
        // overlapped its neighbours by half - the "smear" this menu entry produced. It also wrote
        // DOOR_NORMAL onto the ROOM cells, so a doorway was carved at every one of them. Rooms live on even
        // cells; the odd cells between them are where doors go, and a multi-tile room owns the connectors
        // inside its own footprint.
        for (int row = 0; row < DungeonLayout.GRID && placed < all.size(); row += 2) {
            for (int col = 0; col < DungeonLayout.GRID && placed < all.size(); col += 2) {
                String pick = all.get(placed);
                RoomLibrary.Room room = RoomLibrary.get(pick);
                int tilesX = Math.max(1, (room.sizeX - 1) / (RoomLibrary.TILE + 1));
                int tilesZ = Math.max(1, (room.sizeZ - 1) / (RoomLibrary.TILE + 1));
                // Needs its whole footprint free and on the grid, or it would run over the next room.
                if (col + (tilesX - 1) * 2 >= DungeonLayout.GRID
                        || row + (tilesZ - 1) * 2 >= DungeonLayout.GRID) {
                    placed++;
                    skipped++;
                    continue;
                }
                boolean free = true;
                for (int dz = 0; dz <= (tilesZ - 1) * 2 && free; dz++) {
                    for (int dx = 0; dx <= (tilesX - 1) * 2 && free; dx++) {
                        if (cellRoom[(row + dz) * DungeonLayout.GRID + col + dx] != MapCode.NO_ROOM) {
                            free = false;
                        }
                    }
                }
                if (!free) {
                    placed++;
                    skipped++;
                    continue;
                }
                nameTable.add(pick);
                int id = nameTable.size() - 1;
                for (int dz = 0; dz <= (tilesZ - 1) * 2; dz++) {
                    for (int dx = 0; dx <= (tilesX - 1) * 2; dx++) {
                        int cell = (row + dz) * DungeonLayout.GRID + col + dx;
                        cellRoom[cell] = id;
                        // Unrotated on purpose: this is for writing routes against a room's own layout, and a
                        // rotation would mean the route he writes does not match the room as he studied it.
                        cellRotation[cell] = 0;
                    }
                }
                placed++;
            }
        }
        // Doors on the ODD cells between two different rooms, after the rooms are known.
        for (int row = 0; row < DungeonLayout.GRID; row++) {
            for (int col = 0; col < DungeonLayout.GRID; col++) {
                int cell = row * DungeonLayout.GRID + col;
                if (cellRoom[cell] != MapCode.NO_ROOM) {
                    continue;
                }
                int left = col > 0 ? cellRoom[cell - 1] : MapCode.NO_ROOM;
                int right = col + 1 < DungeonLayout.GRID ? cellRoom[cell + 1] : MapCode.NO_ROOM;
                int up = row > 0 ? cellRoom[cell - DungeonLayout.GRID] : MapCode.NO_ROOM;
                int down = row + 1 < DungeonLayout.GRID
                        ? cellRoom[cell + DungeonLayout.GRID] : MapCode.NO_ROOM;
                boolean joinsX = left != MapCode.NO_ROOM && right != MapCode.NO_ROOM && left != right;
                boolean joinsZ = up != MapCode.NO_ROOM && down != MapCode.NO_ROOM && up != down;
                if (joinsX || joinsZ) {
                    cellDoor[cell] = DungeonLayout.DOOR_NORMAL;
                }
            }
        }
        String code = MapCode.encodeDecoded(new MapCode.Decoded(
                nameTable.toArray(new String[0]), cellRoom, cellDoor, cellRotation));
        ModChat.send("Sim", ModChat.text("All Rooms: "), ModChat.value(String.valueOf(placed)),
                ModChat.text(" room(s) laid out"));
        if (placed < all.size() || skipped > 0) {
            ModChat.send("Sim", ModChat.dim(((all.size() - placed) + skipped)
                    + " did not fit on the grid"));
        }
        SimWorld.open(client, code, c -> SimBuilder.build(c, code), "Generating the map");
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
            // usable(), not complete(): an old-footprint room is a different size from the slot planned for
            // it, so it pastes over its neighbour.
            return r == null || !r.usable();
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
        SimWorld.open(client, code, c -> SimBuilder.build(c, code), "Generating the map");
    }
}

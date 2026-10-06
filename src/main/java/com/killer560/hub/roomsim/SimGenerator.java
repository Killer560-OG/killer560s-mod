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

    // The "All Rooms" preset that pasted every captured room in one line (2026-09-28) was replaced on 2026-10-06 by
    // SimRoomCycle: one room at a time out of a chosen set, stepped with /next and /back. Nothing else called it.

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
            ModChat.send("Sim", ModChat.text("No complete rooms in the room library - "),
                    ModChat.dim("the shipped rooms did not load (see the log)."));
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

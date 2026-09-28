package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encodes a captured {@link DungeonLayout} as a short, copy-pasteable string, and decodes it back. Killer560:
 * "The code needs to carry which rooms go where with what doors" - so this stores, per one of the 121 grid
 * cells, which room occupies it (by room NAME, since {@code DungeonLayout}'s room ids are only stable within
 * one capture) and the door type of that cell.
 *
 * <p>Deliberately does not touch {@code DungeonLayout} or anything in the sim itself - this is a pure
 * java.*-only codec so it can be exercised with plain {@code javac}, no client running.
 */
public final class MapCode {

    private MapCode() {
    }

    /** Bumping this changes the string every future code starts with, so an old parser refuses a new code
     *  instead of silently misreading its bytes as something else. */
    private static final String PREFIX = "MC1:";

    /** Room slot with no room in it (a bare connector or the border). Stored as a room-index byte of 0, since
     *  a real name-table index is stored as (index + 1) - see {@link #encode(Decoded)}. */
    private static final int NO_ROOM = -1;

    /**
     * Plain data form of a map code: {@code nameTable[cellRoom[i]]} is the room name occupying cell {@code i}
     * (or {@link #NO_ROOM} if none), and {@code cellDoor[i]} is that cell's {@code DungeonLayout.DOOR_*} value.
     * Both cell arrays are always exactly {@code DungeonLayout.GRID * DungeonLayout.GRID} long.
     */
    public record Decoded(String[] nameTable, int[] cellRoom, int[] cellDoor) {
    }

    /**
     * Builds a map code string from a captured layout. Room ids in {@code layout} only mean anything within
     * that one capture, so this re-keys every cell onto a name table built fresh from
     * {@link DungeonLayout#name(int)}, deduplicating rooms that share a name (two identical tile-set copies of
     * the same room, which happens on several floors, must collapse to one table entry).
     */
    public static String encode(DungeonLayout layout) {
        int cells = DungeonLayout.GRID * DungeonLayout.GRID;
        Map<String, Integer> nameIndex = new LinkedHashMap<>();
        String[] roomName = new String[layout.roomCount()];
        for (int room = 0; room < layout.roomCount(); room++) {
            String name = layout.name(room);
            if (name == null) {
                name = "Unknown";
            }
            roomName[room] = name;
            nameIndex.putIfAbsent(name, nameIndex.size());
        }
        int[] cellRoom = new int[cells];
        int[] cellDoor = new int[cells];
        for (int idx = 0; idx < cells; idx++) {
            int room = layout.roomOfCell(idx);
            cellRoom[idx] = room >= 0 ? nameIndex.get(roomName[room]) : NO_ROOM;
            cellDoor[idx] = layout.doorType(idx);
        }
        String[] nameTable = new String[nameIndex.size()];
        for (Map.Entry<String, Integer> e : nameIndex.entrySet()) {
            nameTable[e.getValue()] = e.getKey();
        }
        return encode(new Decoded(nameTable, cellRoom, cellDoor));
    }

    /**
     * Core encoder, kept separate from {@link #encode(DungeonLayout)} so {@link #selfTest()} can round-trip a
     * synthetic {@link Decoded} without needing a live game to produce a {@code DungeonLayout}.
     *
     * <p>Wire form, all big-endian via {@link DataOutputStream}: 1 byte cell-grid size (so a future grid
     * resize is at least detectable even though {@link #PREFIX} already forces a hard version check), 1 byte
     * name-table count N, then for each of the N names a 1-byte UTF-8 length + that many bytes, then for each
     * of GRID*GRID cells a 1-byte door type followed by a 1-byte room-index-plus-one (0 = no room). That whole
     * byte array is URL-safe Base64 without padding, and {@link #PREFIX} is prepended in plain ASCII.
     */
    private static String encode(Decoded decoded) {
        int cells = DungeonLayout.GRID * DungeonLayout.GRID;
        if (decoded.cellRoom().length != cells || decoded.cellDoor().length != cells) {
            throw new IllegalArgumentException("Decoded cell arrays must be GRID*GRID (" + cells + ") long");
        }
        if (decoded.nameTable().length > 255) {
            // Real captures top out around a few dozen rooms; this is a hard cap of the wire format, not a
            // tuning knob, so it fails loudly rather than truncating the table and corrupting every index.
            throw new IllegalArgumentException("Name table too large for a 1-byte index: " + decoded.nameTable().length);
        }
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buf);
            out.writeByte(DungeonLayout.GRID);
            out.writeByte(decoded.nameTable().length);
            for (String name : decoded.nameTable()) {
                byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 255) {
                    throw new IllegalArgumentException("Room name too long to encode: " + name);
                }
                out.writeByte(bytes.length);
                out.write(bytes);
            }
            for (int idx = 0; idx < cells; idx++) {
                int room = decoded.cellRoom()[idx];
                if (room < NO_ROOM || room >= decoded.nameTable().length) {
                    throw new IllegalArgumentException("cellRoom[" + idx + "] = " + room + " is not a valid table index");
                }
                int door = decoded.cellDoor()[idx];
                if (door < DungeonLayout.DOOR_NONE || door > DungeonLayout.DOOR_ENTRANCE) {
                    throw new IllegalArgumentException("cellDoor[" + idx + "] = " + door + " is not a known door type");
                }
                out.writeByte(door);
                out.writeByte(room + 1);
            }
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(buf.toByteArray());
        } catch (IOException e) {
            // ByteArrayOutputStream never actually throws, but DataOutputStream's signature does.
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Parses a map code back into plain data. Returns {@code null} - never a partly-filled {@link Decoded} -
     * for anything that does not match {@link #PREFIX} exactly, is not valid Base64, is truncated, carries a
     * different grid size, or has a room/door value outside the range that {@link #encode(Decoded)} could
     * ever have written. A corrupt or hand-edited code must come back as "no map", not as a wrong map.
     */
    public static Decoded decode(String code) {
        if (code == null || !code.startsWith(PREFIX)) {
            return null;
        }
        byte[] payload;
        try {
            payload = Base64.getUrlDecoder().decode(code.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            int grid = in.readUnsignedByte();
            if (grid != DungeonLayout.GRID) {
                return null;
            }
            int nameCount = in.readUnsignedByte();
            String[] nameTable = new String[nameCount];
            for (int i = 0; i < nameCount; i++) {
                int len = in.readUnsignedByte();
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                nameTable[i] = new String(bytes, StandardCharsets.UTF_8);
            }
            int cells = grid * grid;
            int[] cellRoom = new int[cells];
            int[] cellDoor = new int[cells];
            for (int idx = 0; idx < cells; idx++) {
                int door = in.readUnsignedByte();
                int roomPlusOne = in.readUnsignedByte();
                if (door > DungeonLayout.DOOR_ENTRANCE || roomPlusOne > nameCount) {
                    // roomPlusOne == 0 means NO_ROOM and is always valid; > nameCount points past the table.
                    return null;
                }
                cellDoor[idx] = door;
                cellRoom[idx] = roomPlusOne - 1;
            }
            if (in.available() != 0) {
                // Trailing bytes mean this wasn't one of ours (or the string was concatenated with something
                // else) - refuse rather than silently ignore the extra data.
                return null;
            }
            return new Decoded(nameTable, cellRoom, cellDoor);
        } catch (IOException e) {
            // Truncated payload: readUnsignedByte()/readFully() hit end-of-stream.
            return null;
        }
    }

    /**
     * Builds a synthetic layout, encodes it, decodes the result, and checks the round trip is exact. Lets the
     * codec be proven correct from a plain {@code javac} run, with no client and no {@code DungeonLayout}
     * capture required.
     */
    public static boolean selfTest() {
        try {
            int cells = DungeonLayout.GRID * DungeonLayout.GRID;
            String[] nameTable = {"Blue Trap Room", "3-Sided Puzzle: Water Board", "Long Corridor", "Unknown"};
            int[] cellRoom = new int[cells];
            int[] cellDoor = new int[cells];
            for (int idx = 0; idx < cells; idx++) {
                // Deliberately exercise every door type and NO_ROOM, not just room cells, so a bug that only
                // shows up on a mix of doors and empty cells (the shape a real capture has) would be caught.
                cellRoom[idx] = idx % 5 == 0 ? NO_ROOM : idx % nameTable.length;
                cellDoor[idx] = idx % (DungeonLayout.DOOR_ENTRANCE + 1);
            }
            Decoded original = new Decoded(nameTable, cellRoom, cellDoor);
            String code = encode(original);
            Decoded roundTripped = decode(code);
            return roundTripped != null
                    && Arrays.equals(original.nameTable(), roundTripped.nameTable())
                    && Arrays.equals(original.cellRoom(), roundTripped.cellRoom())
                    && Arrays.equals(original.cellDoor(), roundTripped.cellDoor());
        } catch (RuntimeException e) {
            return false;
        }
    }
}

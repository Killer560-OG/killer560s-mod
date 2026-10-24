package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.runsummary.RunHistoryStore;
import com.killer560.hub.runsummary.RunRecord;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;

import net.minecraft.client.Minecraft;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "Load a Previous Run", read out of the run history this mod already keeps.
 *
 * <p>killer560 (2026-09-28): "Won't the previous run portion be able to be filled from that tracker that shows
 * the map who you did the run with the splits of the run who did what secrets and all of that?" - and he is
 * right. {@link RunRecord} already stores a {@code MapSnapshot} with the room in every cell, the door in every
 * cell and the room names, next to the splits, the party and the secrets.
 *
 * <p>So this reads that rather than keeping a second store. An earlier version of this file wrote its own map
 * codes to their own folder, which meant two records of the same run that could disagree, and a "previous runs"
 * list that would have been empty for every floor he had already played. Reading the existing history means his
 * whole back catalog is available immediately.
 *
 * <p><b>Rotation (2026-10-15).</b> killer560: "those saved rooms did not save the proper orientation from the real
 * run, rooms have no doors that are aligning properly." The snapshot never stored a rotation and this file put every
 * room down at 0, so most rooms faced the wrong way and their doorways missed their neighbors'. The snapshot now
 * stores each room's DATABASE rotation as the live map read it ({@code RunRecord.MapRoom.rotation}), and
 * {@link #pasteRotations} turns that into the PASTE rotation the sim needs by taking off the capture's own turn
 * ({@link RoomCaptureRotation}) - the inverse of what {@code SimRoomIndex.add} does, so the rebuilt room's
 * {@code SimRoomIndex} rotation is the one the run recorded. A run saved before then has no rotation, so each room
 * takes the turn whose measured doorways ({@link RoomDoors}) meet the doors on the run's map; that same check also
 * catches a recorded rotation that cannot be right for this capture, which is logged and overruled.
 */
public final class SimRunHistory {

    private static final Logger LOGGER = ModLog.get("killer560smod-roomsim");

    private SimRunHistory() {
    }

    /** Label to the run it came from, rebuilt on each listing so it cannot go stale. */
    private static final Map<String, RunRecord> BY_LABEL = new LinkedHashMap<>();

    /**
     * Runs that can actually be rebuilt, newest first.
     *
     * <p>A run with no map snapshot is left out rather than listed and then refused - an entry that cannot load
     * is worse than an entry that is not there.
     */
    public static synchronized List<RunRecord> runs() {
        // The store's own copy (loaded once, kept current by every add). Forcing a re-read here, as this used to,
        // could read the file back before a just-finished run's async save had reached it and drop that run.
        List<RunRecord> runs = new ArrayList<>();
        for (RunRecord run : RunHistoryStore.runs()) {
            if (run.map() != null && !run.map().isEmpty()) {
                runs.add(run);
            }
        }
        // Newest first: the run he most likely wants to redo is the one he just did.
        runs.sort(Comparator.comparingLong(SimRunHistory::when).reversed());
        return runs;
    }

    /** The runs sorted the way the menu asks, filtered to one floor key ("All" for every floor). */
    public static List<RunRecord> runs(SimMenuPrefs.RunSort sort, String floor) {
        List<RunRecord> out = new ArrayList<>();
        for (RunRecord r : runs()) {
            if (floor == null || "All".equals(floor) || floor.equals(RunHistoryStore.floorKey(r))) {
                out.add(r);
            }
        }
        Comparator<RunRecord> newest = Comparator.comparingLong(SimRunHistory::when).reversed();
        Comparator<RunRecord> order = switch (sort) {
            case NEWEST -> newest;
            case OLDEST -> Comparator.comparingLong(SimRunHistory::when);
            // Unknown scores and times last, whichever way.
            case SCORE -> Comparator.<RunRecord>comparingInt(r -> r.effectiveScore() < 0 ? Integer.MIN_VALUE
                    : r.effectiveScore()).reversed().thenComparing(newest);
            case FASTEST -> Comparator.<RunRecord>comparingLong(r -> r.totalMs() > 0 ? r.totalMs() : Long.MAX_VALUE)
                    .thenComparing(newest);
            case FLOOR -> Comparator.comparing(SimRunHistory::floorOrder).thenComparing(newest);
        };
        out.sort(order);
        return out;
    }

    /** Every floor key that has a run, in floor order, after "All". */
    public static List<String> floors() {
        List<String> out = new ArrayList<>();
        out.add("All");
        List<RunRecord> sorted = new ArrayList<>(runs());
        sorted.sort(Comparator.comparing(SimRunHistory::floorOrder));
        for (RunRecord r : sorted) {
            String key = RunHistoryStore.floorKey(r);
            if (!out.contains(key)) {
                out.add(key);
            }
        }
        return out;
    }

    private static long when(RunRecord r) {
        return r.endedAtMs() > 0 ? r.endedAtMs() : r.startedAtMs();
    }

    /** "E" first, then F1..F7, then M1..M7, anything else last. */
    private static String floorOrder(RunRecord r) {
        String f = r.floor() == null ? "" : r.floor().toUpperCase(Locale.ROOT);
        if (f.equals("E")) {
            return "0";
        }
        if (f.startsWith("F")) {
            return "1" + f;
        }
        if (f.startsWith("M")) {
            return "2" + f;
        }
        return "9" + f;
    }

    /** The old label list (one line per run, newest first). Kept for anything that still picks by label. */
    public static synchronized List<String> savedRuns() {
        BY_LABEL.clear();
        List<String> labels = new ArrayList<>();
        for (RunRecord run : runs()) {
            String label = String.format(Locale.US, "%s  %s  score %d  %s",
                    run.floorLabel(), run.dateText(), run.effectiveScore(), run.hypixelTime());
            // Duplicate labels would make the picker ambiguous; the timestamp makes them unique.
            while (BY_LABEL.containsKey(label)) {
                label = label + " ";
            }
            BY_LABEL.put(label, run);
            labels.add(label);
        }
        return labels;
    }

    /** Rebuilds a listed run in the sim, by its {@link #savedRuns} label. */
    public static synchronized void load(Minecraft client, String label) {
        load(client, BY_LABEL.get(label));
    }

    /** Rebuilds a run in the sim. */
    public static void load(Minecraft client, RunRecord run) {
        if (run == null || run.map() == null || run.map().isEmpty()) {
            ModChat.send("Sim", ModChat.text("That run has no map saved with it."));
            return;
        }
        String code = mapCodeFor(run);
        if (code == null) {
            ModChat.send("Sim", ModChat.text("Could not turn that run's map into a sim map."));
            return;
        }
        SimWorld.open(client, code, c -> SimBuilder.build(c, code), "Rebuilding " + run.floorLabel());
    }

    /**
     * The sim map code a run rebuilds as, or null. Public for the testkit, which decodes it to check every room's
     * rotation before the floor is built.
     */
    public static String mapCodeFor(RunRecord run) {
        return run == null || run.map() == null ? null : toMapCode(run.map());
    }

    /**
     * Turns a run's map snapshot into a sim map code.
     *
     * <p>Room ids in the snapshot are per-run, so they are remapped onto a name table - the sim loads rooms by
     * NAME out of the captured library, and a numeric id from someone else's run means nothing to it.
     */
    private static String toMapCode(RunRecord.MapSnapshot map) {
        int cells = DungeonLayout.GRID * DungeonLayout.GRID;
        int[] cellRoom = new int[cells];
        int[] cellDoor = new int[cells];
        int[] cellRotation = new int[cells];
        java.util.Arrays.fill(cellRoom, MapCode.NO_ROOM);
        int[] paste = pasteRotations(map);

        List<String> nameTable = new ArrayList<>();
        Map<Integer, Integer> idToIndex = new LinkedHashMap<>();
        for (int cell = 0; cell < cells; cell++) {
            int roomId = map.roomAt(cell);
            cellDoor[cell] = Math.min(DungeonLayout.DOOR_ENTRANCE, Math.max(0, map.doorAt(cell)));
            if (roomId < 0) {
                continue;
            }
            RunRecord.MapRoom room = map.room(roomId);
            if (room == null || room.name() == null || room.name().isBlank()) {
                continue;
            }
            Integer idx = idToIndex.get(roomId);
            if (idx == null) {
                nameTable.add(room.name());
                idx = nameTable.size() - 1;
                idToIndex.put(roomId, idx);
            }
            cellRoom[cell] = idx;
            cellRotation[cell] = roomId < paste.length ? paste[roomId] : 0;
        }
        if (nameTable.isEmpty()) {
            return null;
        }
        return MapCode.encodeDecoded(new MapCode.Decoded(
                nameTable.toArray(new String[0]), cellRoom, cellDoor, cellRotation));
    }

    /**
     * The paste rotation for every room id of {@code map}.
     *
     * <p>Recorded: the run's database rotation less the capture's own turn, so {@code SimRoomIndex} (paste + turn)
     * gives back exactly the recorded rotation. Kept unless a different turn fits the run's doors strictly better -
     * the doorways are measured off the same capture the paste puts down, so a turn that leaves one of the run's doors
     * against a wall is wrong for this capture whatever was recorded.
     *
     * <p>Not recorded (runs saved before 2026-10-15, or a room whose marker the live map never read): the turn whose
     * doorways back the most of the run's doors around the room, among the turns whose footprint fits the room's cells.
     */
    static int[] pasteRotations(RunRecord.MapSnapshot map) {
        int[] out = new int[map.rooms().size()];
        for (int id = 0; id < out.length; id++) {
            RunRecord.MapRoom entry = map.room(id);
            out[id] = entry == null ? 0 : pasteRotation(map, id, entry);
        }
        return out;
    }

    private static int pasteRotation(RunRecord.MapSnapshot map, int id, RunRecord.MapRoom entry) {
        int grid = DungeonLayout.GRID;
        RoomLibrary.Room room = entry.name() == null ? null : RoomLibrary.get(entry.name());
        int recorded = -1;
        if (room != null && entry.rotation() >= 0) {
            recorded = Math.floorMod(entry.rotation() - RoomCaptureRotation.of(room), 360);
        }
        if (room == null || !room.usable()) {
            return Math.max(0, recorded);
        }
        // The room's tiles, in tile coordinates.
        Set<Long> tiles = new HashSet<>();
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int cell = 0; cell < grid * grid; cell++) {
            int gx = cell % grid;
            int gz = cell / grid;
            if (map.roomAt(cell) != id || gx % 2 != 0 || gz % 2 != 0) {
                continue;
            }
            tiles.add(key(gx / 2, gz / 2));
            minX = Math.min(minX, gx / 2);
            minZ = Math.min(minZ, gz / 2);
            maxX = Math.max(maxX, gx / 2);
            maxZ = Math.max(maxZ, gz / 2);
        }
        if (tiles.isEmpty()) {
            return Math.max(0, recorded);
        }
        int spanX = maxX - minX + 1;
        int spanZ = maxZ - minZ + 1;
        // The run's doors around this room: a door cell next to one of its tiles that is not inside it.
        Set<Integer> mapDoors = new HashSet<>();
        for (long t : tiles) {
            int tx = (int) (t >> 16);
            int tz = (int) (t & 0xFFFF);
            for (int side = 0; side < 4; side++) {
                int cx = tx * 2 + RoomDoors.DX[side];
                int cz = tz * 2 + RoomDoors.DZ[side];
                if (cx < 0 || cz < 0 || cx >= grid || cz >= grid) {
                    continue;
                }
                int c = cz * grid + cx;
                if (map.roomAt(c) != id && map.doorAt(c) != 0) {
                    mapDoors.add(c);
                }
            }
        }
        RoomDoors.Mask base = RoomDoors.of(entry.name());
        int best = -1;
        int bestMissed = Integer.MAX_VALUE;
        int recordedMissed = Integer.MAX_VALUE;
        for (int rot = 0; rot < 360; rot += 90) {
            boolean quarter = rot == 90 || rot == 270;
            int tilesX = Math.max(1, (room.sizeX - 1) / (RoomLibrary.TILE + 1));
            int tilesZ = Math.max(1, (room.sizeZ - 1) / (RoomLibrary.TILE + 1));
            if ((quarter ? tilesZ : tilesX) != spanX || (quarter ? tilesX : tilesZ) != spanZ) {
                continue;   // this turn does not fit the room's cells
            }
            int missed = 0;
            if (base != null) {
                Set<Integer> doorways = new HashSet<>();
                for (int[] d : RoomDoors.doorCells(RoomDoors.rotate(base, rot), minX, minZ)) {
                    if (tiles.contains(key(d[0], d[1]))) {
                        doorways.add((d[1] * 2 + RoomDoors.DZ[d[2]]) * grid + d[0] * 2 + RoomDoors.DX[d[2]]);
                    }
                }
                for (int door : mapDoors) {
                    if (!doorways.contains(door)) {
                        missed++;
                    }
                }
            }
            if (rot == recorded) {
                recordedMissed = missed;
            }
            if (missed < bestMissed) {
                bestMissed = missed;
                best = rot;
            }
        }
        if (recorded >= 0 && (best < 0 || recordedMissed <= bestMissed)) {
            return recorded;
        }
        if (recorded >= 0) {
            LOGGER.warn("Sim replay: {} was recorded at rotation {} (paste {}), which leaves {} of its {} door(s) "
                            + "against a wall in this capture; using paste {} ({} missed) instead",
                    entry.name(), entry.rotation(), recorded,
                    recordedMissed == Integer.MAX_VALUE ? "all" : recordedMissed, mapDoors.size(), best, bestMissed);
        }
        return Math.max(0, best);
    }

    private static long key(int x, int z) {
        return ((long) x << 16) | (z & 0xFFFF);
    }
}

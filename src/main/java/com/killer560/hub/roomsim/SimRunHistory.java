package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.runsummary.RunHistoryStore;
import com.killer560.hub.runsummary.RunRecord;
import com.killer560.hub.util.ModChat;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * whole back catalogue is available immediately.
 *
 * <p>The one thing the snapshot does not carry is ROTATION - the dungeon map has no notion of it - so a replayed
 * floor has the right rooms in the right cells with the right doors, and each room in its default orientation.
 * That is said plainly rather than hidden, because a route practised against a wrongly-rotated room is worse
 * than no route.
 */
public final class SimRunHistory {

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
    public static synchronized List<String> savedRuns() {
        RunHistoryStore.load();
        BY_LABEL.clear();
        List<RunRecord> runs = new ArrayList<>(RunHistoryStore.runs());
        // Newest first: the run he most likely wants to redo is the one he just did.
        java.util.Collections.reverse(runs);
        List<String> labels = new ArrayList<>();
        for (RunRecord run : runs) {
            if (run.map() == null || run.map().isEmpty()) {
                continue;
            }
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

    /** Rebuilds a listed run in the sim. */
    public static synchronized void load(Minecraft client, String label) {
        RunRecord run = BY_LABEL.get(label);
        if (run == null || run.map() == null || run.map().isEmpty()) {
            ModChat.send("Sim", ModChat.text("That run has no map saved with it."));
            return;
        }
        String code = toMapCode(run.map());
        if (code == null) {
            ModChat.send("Sim", ModChat.text("Could not turn that run's map into a sim map."));
            return;
        }
        SimWorld.open(client, code);
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

        List<String> nameTable = new ArrayList<>();
        Map<Integer, Integer> idToIndex = new LinkedHashMap<>();
        for (int cell = 0; cell < cells; cell++) {
            int roomId = map.roomAt(cell);
            cellDoor[cell] = Math.min(DungeonLayout.DOOR_ENTRANCE, Math.max(0, map.doorAt(cell)));
            // No rotation in the snapshot - the dungeon map does not record one. Left at 0 and documented.
            cellRotation[cell] = 0;
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
        }
        if (nameTable.isEmpty()) {
            return null;
        }
        return MapCode.encodeDecoded(new MapCode.Decoded(
                nameTable.toArray(new String[0]), cellRoom, cellDoor, cellRotation));
    }
}

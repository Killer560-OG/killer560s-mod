package com.killer560.hub.autoclear;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.livemap.autoclear.DungeonMapPathfinder;
import com.killer560.hub.roomdatabase.RoomEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Which rooms Auto Clear works through, read off the live map ({@link DungeonLayout}) - the same room graph the
 * Interactive Map and Auto Blood Rush path over. Client thread.
 */
final class AutoClearTargets {

    private AutoClearTargets() {
    }

    /**
     * A room whose clear is its starred mobs: the room database's NORMAL, RARE and CHAMPION types (minibosses live in
     * the last two, and a starred miniboss counts). Puzzle, trap, fairy, blood and entrance rooms are never targets.
     */
    static boolean isMobRoom(DungeonLayout layout, int room) {
        RoomEntry entry = layout.entry(room);
        if (entry == null || entry.type == null) {
            return false;
        }
        String type = entry.type.toUpperCase(java.util.Locale.ROOT);
        return type.equals("NORMAL") || type.equals("RARE") || type.equals("CHAMPION");
    }

    /** Cleared on the map (the checkmark, green or white) on any of its tiles. */
    static boolean isCleared(DungeonLayout layout, int room) {
        int[] tiles = layout.tiles(room);
        if (tiles == null) {
            return false;
        }
        for (int t : tiles) {
            if (LiveMapFeature.isRoomCleared(t)) {
                return true;
            }
        }
        return false;
    }

    static int roomByName(DungeonLayout layout, String name) {
        if (name == null) {
            return -1;
        }
        for (int r = 0; r < layout.roomCount(); r++) {
            if (name.equalsIgnoreCase(layout.name(r))) {
                return r;
            }
        }
        return -1;
    }

    static int entranceRoom(DungeonLayout layout) {
        for (int r = 0; r < layout.roomCount(); r++) {
            RoomEntry e = layout.entry(r);
            if ((e != null && "ENTRANCE".equalsIgnoreCase(e.type)) || "Entrance".equalsIgnoreCase(layout.name(r))) {
                return r;
            }
        }
        return -1;
    }

    /**
     * The blood rush (Blood Rush Split mode): every room on the shortest room-graph path from the Entrance (or where he
     * stands, when the Entrance is not on the map) to the room in front of the Blood door, locked doors counted as
     * passable because a blood rush opens them. Null when the Blood door is not on the map yet. Same resolution of
     * "the room in front of the Blood door" as Auto Blood Rush's {@code nextDoor}.
     */
    static List<Integer> bloodRushRooms(DungeonLayout layout) {
        int blood = layout.bloodDoor();
        if (blood < 0) {
            return null;
        }
        int start = entranceRoom(layout);
        if (start < 0) {
            start = layout.currentRoom();
        }
        if (start < 0) {
            return null;
        }
        int[] resolved = DungeonMapPathfinder.resolve(layout, start, blood, true);
        if (resolved == null) {
            return null;
        }
        int bloodSide = layout.roomOfCell(resolved[1]);
        List<Integer> out = new ArrayList<>();
        if (bloodSide == start) {
            out.add(start);
            return out;
        }
        List<DungeonMapPathfinder.RoomStep> path = DungeonMapPathfinder.findPath(layout, start, bloodSide, true);
        if (path == null) {
            return null;
        }
        for (DungeonMapPathfinder.RoomStep step : path) {
            out.add(step.room());
        }
        return out;
    }

    /** Uncleared mob rooms the mode allows, minus the ones given up on this run (by name). */
    static List<Integer> candidates(DungeonLayout layout, AutoClearConfig.Mode mode, Set<String> skipped) {
        List<Integer> pool = new ArrayList<>();
        if (mode == AutoClearConfig.Mode.BLOOD_RUSH_SPLIT) {
            List<Integer> rush = bloodRushRooms(layout);
            if (rush == null) {
                return null;
            }
            pool.addAll(rush);
        } else {
            for (int r = 0; r < layout.roomCount(); r++) {
                pool.add(r);
            }
        }
        List<Integer> out = new ArrayList<>();
        for (int r : pool) {
            if (isMobRoom(layout, r) && !isCleared(layout, r) && !skipped.contains(layout.name(r))) {
                out.add(r);
            }
        }
        return out;
    }

    /** The candidate fewest rooms away through unlocked doors, or -1 when none can be reached from here. */
    static int nearest(DungeonLayout layout, List<Integer> candidates) {
        int from = layout.currentRoom();
        int best = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int r : candidates) {
            int d;
            if (r == from) {
                d = 0;
            } else if (from < 0) {
                d = 1000;
            } else {
                List<DungeonMapPathfinder.RoomStep> path = DungeonMapPathfinder.findPath(layout, from, r, false);
                if (path == null) {
                    continue;
                }
                d = path.size();
            }
            if (d < bestDist) {
                bestDist = d;
                best = r;
            }
        }
        return best;
    }
}

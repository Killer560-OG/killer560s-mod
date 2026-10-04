package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Where a real Hypixel floor would have its wither doors, for a floor the sim built with none.
 *
 * <p>killer560 (2026-10-04): "Make the doors on map also show theoretical wither doors." The sim never builds a
 * wither door (see docs/SIM.md, "The 2026-10-04 round"), so neither the live map nor the Map Designer could show
 * one on a generated floor. The rule is the wiki's: "The path to the Blood Room is guarded by a series of
 * Wither Doors and ends at the Blood Door", and the fairy room "always generates along this path". So: every
 * door on the room path from the Entrance to the Blood room, except the entrance's own gate and the blood door.
 * A sim floor is a tree (SimFloorGen refuses loops), so that path is unique.
 *
 * <p>SIM AND DESIGNER ONLY. {@link #isTheoretical} answers false unless {@link SimState#isActive()}, and the
 * table is only ever filled from {@code LiveMapFeature.publishSimFloor}, which nothing on Hypixel calls. A real
 * floor's wither doors come from the map item and the world scan, and nothing here touches either.
 */
public final class SimWitherDoors {

    private static final int GRID = DungeonLayout.GRID;

    /** The published sim floor's theoretical wither doors, by 11x11 cell, or null. */
    private static volatile boolean[] current;

    private SimWitherDoors() {
    }

    /** Called with the floor the sim just published to the live map. */
    public static void publish(int[] roomCells, int[] doorCells, String[] names) {
        current = compute(roomCells, doorCells, names);
    }

    public static void clear() {
        current = null;
    }

    /** Whether the live map should mark this ordinary door as a theoretical wither door. Never on Hypixel. */
    public static boolean isTheoretical(int idx) {
        boolean[] c = current;
        return c != null && SimState.isActive() && idx >= 0 && idx < c.length && c[idx];
    }

    /**
     * The doors on the Entrance-to-Blood room path that are ordinary doors, by 11x11 cell. All false when the
     * floor has no entrance or no blood room, or they are not joined.
     */
    public static boolean[] compute(int[] roomCells, int[] doorCells, String[] names) {
        boolean[] out = new boolean[GRID * GRID];
        if (roomCells == null || doorCells == null || names == null) {
            return out;
        }
        int entrance = -1;
        int blood = -1;
        for (int i = 0; i < names.length; i++) {
            String t = SimFloorGen.typeOf(names[i]);
            if (entrance < 0 && ("ENTRANCE".equalsIgnoreCase(t) || "Entrance".equalsIgnoreCase(names[i]))) {
                entrance = i;
            }
            if (blood < 0 && ("BLOOD".equalsIgnoreCase(t) || "Blood".equalsIgnoreCase(names[i]))) {
                blood = i;
            }
        }
        if (entrance < 0 || blood < 0) {
            return out;
        }
        // Room graph: for each room, the (door cell, other room) pairs.
        List<List<int[]>> adj = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            adj.add(new ArrayList<>());
        }
        int cells = Math.min(GRID * GRID, Math.min(roomCells.length, doorCells.length));
        for (int idx = 0; idx < cells; idx++) {
            if (doorCells[idx] == DungeonLayout.DOOR_NONE || roomCells[idx] >= 0) {
                continue;
            }
            int gx = idx % GRID;
            int gz = idx / GRID;
            int a;
            int b;
            if (gx % 2 == 1 && gz % 2 == 0) {
                a = roomCells[idx - 1];
                b = roomCells[idx + 1];
            } else if (gx % 2 == 0 && gz % 2 == 1) {
                a = roomCells[idx - GRID];
                b = roomCells[idx + GRID];
            } else {
                continue;
            }
            if (a < 0 || b < 0 || a == b || a >= names.length || b >= names.length) {
                continue;
            }
            adj.get(a).add(new int[]{idx, b});
            adj.get(b).add(new int[]{idx, a});
        }
        int[] viaDoor = new int[names.length];
        int[] from = new int[names.length];
        Arrays.fill(from, -2);
        from[entrance] = -1;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(entrance);
        while (!queue.isEmpty() && from[blood] == -2) {
            int r = queue.poll();
            for (int[] e : adj.get(r)) {
                if (from[e[1]] == -2) {
                    from[e[1]] = r;
                    viaDoor[e[1]] = e[0];
                    queue.add(e[1]);
                }
            }
        }
        if (from[blood] == -2) {
            return out;
        }
        for (int r = blood; from[r] >= 0; r = from[r]) {
            int door = viaDoor[r];
            if (doorCells[door] == DungeonLayout.DOOR_NORMAL) {
                out[door] = true;
            }
        }
        return out;
    }
}

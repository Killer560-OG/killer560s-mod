package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What room is where, for a floor the sim built.
 *
 * <p>killer560 (2026-09-29): "Then the secret waypoints need to show in rooms."
 *
 * <p>Secret Waypoints asks {@code LiveMapFeature} which rooms have been identified and where each one's clay
 * corner is, and {@code LiveMapFeature} works that out by reading the dungeon map ITEM off the real server.
 * The sim has no map item, so that list was always empty and the waypoints had nothing to draw - not because
 * the feature was off, but because it was asking a question only Hypixel can answer.
 *
 * <p>The sim already knows the answer exactly - it placed the rooms - so it records them here in the shape
 * Secret Waypoints already consumes. No new transform: the clay corner is computed by the same code
 * {@link SimSecrets} uses to put the secrets there in the first place, so a waypoint cannot end up somewhere
 * the secret is not. That is the whole reason the sim shares the transform rather than owning a second one.
 */
public final class SimRoomIndex {

    /**
     * One placed room.
     *
     * @param clayX the corner the room database's relative coordinates are measured from, at this rotation
     * @param rotation the DATABASE rotation, not the rotation the room was pasted at - the paste rotation
     *                 decides the footprint, but everything downstream of this record is translating database
     *                 coordinates, and those two numbers differ by the capture's own turn
     *                 ({@link RoomCaptureRotation}) in 88 of his 122 identifiable rooms
     * @param cells the 11x11 grid cells this room covers, so "am I in it" is a lookup
     */
    public record Placed(String name, int clayX, int clayZ, int rotation, int[] cells) {
    }

    private static final List<Placed> ROOMS = new CopyOnWriteArrayList<>();

    private SimRoomIndex() {
    }

    /** Forgets the floor. Called before a build, so a new floor never shows the last one's waypoints. */
    public static void clear() {
        ROOMS.clear();
    }

    /**
     * Records a placed room.
     *
     * @param gridX the 11x11 grid coordinates of the room's top-left cell
     */
    public static void add(RoomLibrary.Room room, int gridX, int gridZ, int rotation) {
        if (room == null) {
            return;
        }
        // Must match what SimSecrets actually did, or the waypoints point at where the secrets are not -
        // and the waypoints are the thing being practised against.
        int dbRotation = Math.floorMod(rotation + RoomCaptureRotation.of(room), 360);
        int[] clay = SimSecrets.clayCorner(room, gridX, gridZ, rotation, dbRotation);
        int tilesX = (rotation == 90 || rotation == 270)
                ? tiles(room.sizeZ) : tiles(room.sizeX);
        int tilesZ = (rotation == 90 || rotation == 270)
                ? tiles(room.sizeX) : tiles(room.sizeZ);
        List<Integer> cells = new ArrayList<>();
        for (int a = 0; a < tilesX; a++) {
            for (int b = 0; b < tilesZ; b++) {
                int gx = gridX + a * 2;
                int gz = gridZ + b * 2;
                if (gx >= 0 && gz >= 0 && gx < DungeonLayout.GRID && gz < DungeonLayout.GRID) {
                    cells.add(gz * DungeonLayout.GRID + gx);
                }
            }
        }
        int[] cellArray = new int[cells.size()];
        for (int i = 0; i < cellArray.length; i++) {
            cellArray[i] = cells.get(i);
        }
        ROOMS.add(new Placed(room.name, clay[0], clay[1], dbRotation, cellArray));
    }

    private static int tiles(int size) {
        return Math.max(1, (size - 1) / (RoomLibrary.TILE + 1));
    }

    /** Every placed room, in the {@code {id, clayX, clayZ, rotation}} shape Secret Waypoints already reads. */
    public static List<int[]> identifiedRoomsWithRotation() {
        List<int[]> out = new ArrayList<>(ROOMS.size());
        for (int i = 0; i < ROOMS.size(); i++) {
            Placed p = ROOMS.get(i);
            out.add(new int[]{i, p.clayX(), p.clayZ(), p.rotation()});
        }
        return out;
    }

    /** The room database entry for a placed room, or null when that room is not in the database. */
    public static RoomEntry roomEntryAt(int id) {
        if (id < 0 || id >= ROOMS.size()) {
            return null;
        }
        return RoomDatabase.lookupByName(ROOMS.get(id).name());
    }

    /** Whether a placed room covers a grid cell. */
    public static boolean roomHasCell(int id, int cell) {
        if (id < 0 || id >= ROOMS.size()) {
            return false;
        }
        for (int c : ROOMS.get(id).cells()) {
            if (c == cell) {
                return true;
            }
        }
        return false;
    }

    /** The name of the room covering a grid cell, or null. */
    public static String nameAtCell(int cell) {
        for (Placed p : ROOMS) {
            for (int c : p.cells()) {
                if (c == cell) {
                    return p.name();
                }
            }
        }
        return null;
    }

    public static int size() {
        return ROOMS.size();
    }
}

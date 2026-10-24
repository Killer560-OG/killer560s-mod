package com.killer560.hub.roomsim;

import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.util.ModLog;
import org.slf4j.Logger;

/**
 * Which quarter of an L room's 2x2 capture is NOT the room - the one place that is decided.
 *
 * <p>An L is captured as its 2x2 bounding box, and the quarter it does not occupy holds whatever stood there the
 * day it was scanned: nothing, or a neighboring room. The single-room map, the room index, the paste and the spawn
 * all need the same answer, and they each used to get it from {@code SimBuilder.lMissingTile}'s "fewest blocks in
 * y 66..99" - which reads a PIT as missing. Lava Ravine's real quarter is a ravine, mostly air in that band, so
 * a capture with Entrance in its fourth quarter (killer560's, 2026-10-13) had the ravine dropped from the map and
 * Entrance kept: the map drew the L a quarter turn off what was built, he stood "outside" it, and the sidebar fell
 * back to "Room: Entrance". Market had the same fault with Chains in its fourth quarter.
 *
 * <p>The evidence, strongest first, all read off the capture:
 * <ol>
 *   <li>the room database's core hash of each tile's center column ({@link RoomDatabase#coreOfColumn}): a tile
 *       whose core is ANOTHER room's is a neighbor, and when three tiles carry this room's own cores the fourth
 *       is the missing one;</li>
 *   <li>a tile with no block at all in the whole capture band;</li>
 *   <li>the old rule, the emptiest tile in y 66..99, logged, for a capture neither of those settles.</li>
 * </ol>
 * Checked 2026-10-14 on all eleven bundled L captures and on the Mod Only Test copy of Lava Ravine: rule 1 or 2
 * settles every one.
 */
public final class RoomLShape {

    private static final Logger LOGGER = ModLog.get("killer560smod-roomsim");

    private RoomLShape() {
    }

    /** Tiles across a captured size - {@code RoomLibrary.footprint}'s inverse, as everywhere else. */
    static int tiles(int size) {
        return Math.max(1, (size - 1) / (RoomLibrary.TILE + 1));
    }

    /**
     * For an L room (2x2 capture, database shape "L"), its missing quarter as {@code {tx, tz}} in the CAPTURE's
     * tiles (paste rotation 0); null for any other room, or while the room database is not loaded.
     */
    public static int[] missingTile(RoomLibrary.Room room) {
        if (room == null || tiles(room.sizeX) != 2 || tiles(room.sizeZ) != 2) {
            return null;
        }
        RoomEntry entry = RoomDatabase.lookupByName(room.name);
        if (entry == null || entry.shape == null || !"L".equalsIgnoreCase(entry.shape.trim())) {
            return null;
        }
        int[] own = new int[4];      // 1 = this room's core, -1 = another room's, 0 = unknown
        java.util.List<String> foreign = new java.util.ArrayList<>();
        for (int t = 0; t < 4; t++) {
            int tx = t / 2;
            int tz = t % 2;
            int x = room.margin + RoomLibrary.TILE / 2 + tx * (RoomLibrary.TILE + 1);
            int z = room.margin + RoomLibrary.TILE / 2 + tz * (RoomLibrary.TILE + 1);
            int core = RoomDatabase.coreOfColumn(y -> {
                short id = room.at(x, y, z);
                return id >= 0 && id < room.palette.size() ? room.palette.get(id) : null;
            });
            RoomEntry hit = RoomDatabase.lookup(core);
            if (hit != null) {
                if (room.name.equals(hit.name)) {
                    own[t] = 1;
                } else {
                    own[t] = -1;
                    foreign.add(tx + "," + tz + "=" + hit.name);
                }
            }
        }
        int foreignTile = only(own, -1);
        if (foreignTile >= 0) {
            return new int[]{foreignTile / 2, foreignTile % 2};
        }
        if (count(own, 1) == 3) {
            int t = only(own, 0);
            if (t >= 0) {
                return new int[]{t / 2, t % 2};
            }
        }
        int empty = -1;
        int empties = 0;
        long bestSolid = Long.MAX_VALUE;
        int best = 0;
        for (int t = 0; t < 4; t++) {
            int x0 = room.margin + (t / 2) * (RoomLibrary.TILE + 1);
            int z0 = room.margin + (t % 2) * (RoomLibrary.TILE + 1);
            long all = 0;
            long band = 0;
            for (int y = room.minY; y <= room.maxY; y++) {
                for (int a = 0; a < RoomLibrary.TILE; a++) {
                    for (int b = 0; b < RoomLibrary.TILE; b++) {
                        short id = room.at(x0 + a, y, z0 + b);
                        if (id >= 0 && id < room.palette.size() && !room.palette.get(id).endsWith("air")) {
                            all++;
                            if (y >= 66 && y <= 99) {
                                band++;
                            }
                        }
                    }
                }
            }
            if (all == 0) {
                empty = t;
                empties++;
            }
            if (band < bestSolid) {
                bestSolid = band;
                best = t;
            }
        }
        if (empties == 1) {
            return new int[]{empty / 2, empty % 2};
        }
        LOGGER.warn("Sim: L room {} - no tile names itself missing (cores {} {} {} {}, foreign {}); using the emptiest,"
                        + " tile {},{} ({} blocks in y66..99)", room.name, own[0], own[1], own[2], own[3], foreign,
                best / 2, best % 2, bestSolid);
        return new int[]{best / 2, best % 2};
    }

    /**
     * The missing quarter turned to the paste rotation: which of the PLACED room's tiles ({@code {tx, tz}} from its
     * anchor cell, the footprint {@link RoomPlacer#rotateLocal} gives) is not the room. Null for a room that is not
     * an L.
     */
    public static int[] missingTilePasted(RoomLibrary.Room room, int rotation) {
        int[] m = missingTile(room);
        if (m == null) {
            return null;
        }
        // A 2x2 box turned about its own middle: the same mapping rotateLocal applies to blocks, on tiles.
        return switch (Math.floorMod(rotation, 360)) {
            case 90 -> new int[]{1 - m[1], m[0]};
            case 180 -> new int[]{1 - m[0], 1 - m[1]};
            case 270 -> new int[]{m[1], 1 - m[0]};
            default -> m;
        };
    }

    private static int only(int[] v, int want) {
        int at = -1;
        for (int i = 0; i < v.length; i++) {
            if (v[i] == want) {
                if (at >= 0) {
                    return -1;
                }
                at = i;
            }
        }
        return at;
    }

    private static int count(int[] v, int want) {
        int n = 0;
        for (int x : v) {
            if (x == want) {
                n++;
            }
        }
        return n;
    }
}

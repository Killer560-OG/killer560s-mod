package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModLog;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Catches a capture that holds another room's blocks, or none at all.
 *
 * <h2>What went wrong</h2>
 *
 * <p>killer560 (2026-09-30): "I am standing in Crypt but the room generated isnt crypt", "this is a rare room
 * i do not remember whcih but it isnt waterfall", "it feels like almost every room was generated wrong."
 *
 * <p>The builder was innocent. Reading the whole 16:28 floor back out of its region file and scoring all 36
 * cells against all 134 captures put every cell on the room the sim named, at 99.9-100% against a best rival
 * of 21-91%. What is wrong is the CAPTURES, and the cause is in {@code resolveFootprint}'s clamp.
 *
 * <p>An Ashfall practice floor lays every room out in a LINE with no gaps, so the live map merges a whole run
 * of neighbours into one "room". The capture box is anchored at the minimum cell of that merged run, and the
 * clamp then decides only HOW MANY tiles to read from it - so a four-tile room read four tiles of the line:
 * its neighbour, itself, the gap between two rooms, and the room after. That is exactly what Waterfall's
 * capture is: tile 0 is a tile of Catwalk, tile 2 is nothing but air, and tile 3 is the whole of Rare
 * Overgrown. Thirty-four tiles across the library are a tile of some other room, and twelve are empty.
 *
 * <p>The clamp was itself a fix, for footprints like Hall at 11x1, and it did fix the SIZE. It could not fix
 * the position, because nothing was checking the position. This is that check.
 *
 * <h2>How a corrupt capture is told from a correct one</h2>
 *
 * <p>Tiles are compared over y {@value #BAND_MIN}..{@value #BAND_MAX}, the dungeon's own floor-to-roof band,
 * because captures differ in how far below the floor they reach and comparing each room over its own band
 * hides the duplicates - measured both ways on 2026-09-30, the room's own band finds 5 and the common band
 * finds 34, and the 29 it misses are pairs that plainly are the same tile. Every rotation is tried, since two
 * captures of one tile can have been taken from different floors.
 *
 * <p>When two rooms share a tile, the one with MORE tiles is the corrupt one. A one-tile room's box cannot
 * span a run - it is one cell, anchored on the cell the map identified - so it is the multi-tile box that
 * wandered. Twenty-six of the thirty-four pairs are exactly that shape, a 1x1 room's whole capture against
 * one tile of a bigger one. When both are the same size neither can be acquitted, so both are flagged.
 *
 * <p>A flagged room is not deleted. It is made unusable, so the generator will not place it and
 * {@code MissingRoomsHud} will ask for it back, and one clean walk through it clears the flag by itself.
 */
public final class RoomTileAudit {

    private static final Logger LOGGER = ModLog.get("killer560smod-roomsim");

    /** The band every real room's floor and roof sit in, and the only band all captures share. */
    private static final int BAND_MIN = 66;
    private static final int BAND_MAX = 99;

    private RoomTileAudit() {
    }

    /**
     * Flags every corrupt capture in {@code rooms}, in place.
     *
     * <p>Called from {@link RoomLibrary#load} on the local map, before the lock is taken and the map is
     * swapped in - same reasoning as the rest of that method, this reads every capture's blocks and has no
     * business doing it on the render thread.
     *
     * @return how many rooms were flagged
     */
    public static int run(Map<String, RoomLibrary.Room> rooms) {
        long startedAt = System.currentTimeMillis();
        // Digest -> every (room, tile) that holds those blocks. One pass, so the cost is one read of each
        // capture rather than one per pair - a pairwise comparison of 180 tiles is 16,000 comparisons.
        Map<Long, List<String>> byTile = new HashMap<>();
        Map<String, List<String>> reasons = new LinkedHashMap<>();
        int skipped = 0;

        for (RoomLibrary.Room room : rooms.values()) {
            if (!room.currentFormat()) {
                continue;   // a room at an old footprint is already refused; tiling it would be meaningless
            }
            int tilesX = tiles(room.sizeX);
            int tilesZ = tiles(room.sizeZ);
            if (room.minY > BAND_MIN || room.maxY < BAND_MAX) {
                skipped++;
                continue;   // cannot be compared with the others; saying nothing beats guessing
            }
            for (int tx = 0; tx < tilesX; tx++) {
                for (int tz = 0; tz < tilesZ; tz++) {
                    long[] digest = new long[4];
                    boolean anySolid = hashTile(room, tx, tz, digest);
                    String where = room.name + " tile(" + tx + "," + tz + ")";
                    if (!anySolid) {
                        // Positively recorded air, not unread: the box ran off the end of the room, into the
                        // gap between two rooms in the line. An empty tile matches every other empty tile, so
                        // it is reported here and kept out of the duplicate index.
                        reasons.computeIfAbsent(room.name, k -> new ArrayList<>())
                                .add("tile(" + tx + "," + tz + ") is nothing but air");
                        continue;
                    }
                    long key = Math.min(Math.min(digest[0], digest[1]), Math.min(digest[2], digest[3]));
                    byTile.computeIfAbsent(key, k -> new ArrayList<>()).add(where);
                }
            }
        }

        for (List<String> holders : byTile.values()) {
            if (holders.size() < 2) {
                continue;
            }
            TreeSet<String> names = new TreeSet<>();
            for (String h : holders) {
                names.add(h.substring(0, h.indexOf(" tile(")));
            }
            if (names.size() < 2) {
                continue;   // one room whose own two tiles are identical - unusual but not another room's
            }
            // The bigger box is the one that wandered. See the class doc.
            int biggest = 0;
            for (String name : names) {
                RoomLibrary.Room r = rooms.get(name);
                if (r != null) {
                    biggest = Math.max(biggest, tiles(r.sizeX) * tiles(r.sizeZ));
                }
            }
            for (String name : names) {
                RoomLibrary.Room r = rooms.get(name);
                if (r == null || tiles(r.sizeX) * tiles(r.sizeZ) < biggest) {
                    continue;   // the smaller room is the one that was read; it is the victim, not the fault
                }
                TreeSet<String> others = new TreeSet<>(names);
                others.remove(name);
                reasons.computeIfAbsent(name, k -> new ArrayList<>())
                        .add("a tile of it is block-for-block " + String.join(" and ", others));
            }
        }

        for (Map.Entry<String, List<String>> e : reasons.entrySet()) {
            RoomLibrary.Room room = rooms.get(e.getKey());
            if (room != null) {
                room.corruptReason = String.join("; ", e.getValue());
            }
        }
        if (!reasons.isEmpty()) {
            LOGGER.warn("Room library: {} capture(s) hold blocks that are not theirs and will not be placed "
                    + "until they are walked again - {}", reasons.size(),
                    String.join(", ", reasons.keySet()));
            for (Map.Entry<String, List<String>> e : reasons.entrySet()) {
                LOGGER.warn("   {}: {}", e.getKey(), String.join("; ", e.getValue()));
            }
        }
        LOGGER.info("Room library: tile audit checked {} capture(s) in {} ms ({} could not be compared, "
                + "their captured band does not cover y {}..{})",
                rooms.size(), System.currentTimeMillis() - startedAt, skipped, BAND_MIN, BAND_MAX);
        return reasons.size();
    }

    /**
     * Hashes one tile at all four rotations.
     *
     * <p>Four rotations because two captures of the same physical tile can have been taken on floors that had
     * it turned differently, and then they are the same tile without being the same array.
     *
     * @return whether the tile holds anything but air
     */
    private static boolean hashTile(RoomLibrary.Room room, int tx, int tz, long[] out) {
        final int size = RoomLibrary.TILE;
        int x0 = room.margin + tx * (size + 1);
        int z0 = room.margin + tz * (size + 1);
        long[] h = new long[4];
        boolean solid = false;
        for (int y = BAND_MIN; y <= BAND_MAX; y++) {
            for (int a = 0; a < size; a++) {
                for (int b = 0; b < size; b++) {
                    // One read, four placements: the block is mixed into each rotation's hash at the
                    // position that rotation would move it to, so the tile is read once rather than four
                    // times. The four hashes are SUMMED and not chained, and that is the part that has to be
                    // right - addition is commutative, so each hash is independent of the order this loop
                    // happens to walk the tile in, which is what lets a turned copy of the tile reach the
                    // same number.
                    short id = room.at(x0 + a, y, z0 + b);
                    int token;
                    if (id < 0 || id >= room.palette.size()) {
                        token = 0;
                    } else {
                        String block = room.palette.get(id);
                        token = block.hashCode();
                        if (!block.endsWith("air")) {
                            solid = true;
                        }
                    }
                    int last = size - 1;
                    h[0] += mix(token, a, b, y);
                    h[1] += mix(token, b, last - a, y);
                    h[2] += mix(token, last - a, last - b, y);
                    h[3] += mix(token, last - b, a, y);
                }
            }
        }
        System.arraycopy(h, 0, out, 0, 4);
        return solid;
    }

    /** One cell's contribution, stirred enough that summing them is not a checksum. */
    private static long mix(int token, int a, int b, int y) {
        long v = (long) token * 0x9E3779B97F4A7C15L
                ^ ((((long) a * 31 + b) * 1021 + y) * 0xC2B2AE3D27D4EB4FL);
        v ^= v >>> 33;
        v *= 0xFF51AFD7ED558CCDL;
        v ^= v >>> 33;
        return v;
    }

    /** Tiles across, from a captured size - 33 is one tile, 65 two, 129 four. */
    private static int tiles(int size) {
        return Math.max(1, (size - 1) / (RoomLibrary.TILE + 1));
    }
}

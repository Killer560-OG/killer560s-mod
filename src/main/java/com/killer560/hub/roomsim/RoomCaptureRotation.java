package com.killer560.hub.roomsim;

import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.killer560.hub.util.ModLog;

/**
 * Which way round a captured room was when it was captured.
 *
 * <h2>The bug this exists for</h2>
 *
 * <p>A room's secret coordinates in the room database are relative to that room's CANONICAL orientation - the
 * one Hypixel's own data is written in. Live on Hypixel that is not a problem: {@code findRotationAndCorner}
 * reads the blue terracotta marker off the real roof, and {@code toRealCoord} turns the database's numbers by
 * exactly that much.
 *
 * <p>The sim had no equivalent. It pasted a captured room at whatever rotation the generator chose and handed
 * {@code toRealCoord} that rotation alone - as though every capture had been taken with the room already
 * canonical. They were not. A capture is taken from whatever instance he happened to walk through, and Hypixel
 * turns rooms freely, so each capture carries its own arbitrary quarter turn.
 *
 * <p>Measured across his 135 captures on 2026-09-29: only 34 are canonical. <b>88 are turned</b>, so in those
 * rooms every chest, bat, item and marker the sim placed was in the wrong corner. In a square room the wrong
 * position still lands inside the room, so nothing complained; only the long rooms ever produced the "secrets
 * skipped, outside the room's own box" warning, which is why this read as a rare edge case for weeks.
 *
 * <h2>How the answer is recovered</h2>
 *
 * <p>Without re-capturing all 135 rooms, from the capture itself:
 *
 * <ol>
 *   <li><b>The roof marker.</b> Every Catacombs room has a {@code blue_terracotta} block at exactly one of the
 *       four corners of its tile area, and which corner it is IS the rotation - that is the same fact
 *       {@link RoomDatabase#findRotationAndCorner} relies on live. The marker is in the captured blocks, so it
 *       can be read straight back out. This alone settles 119 of the 135.</li>
 *   <li><b>The secrets themselves.</b> Where several corners carry the marker or none does, the database's own
 *       chest and lever coordinates break the tie: rotate them each of the four ways and keep the one that
 *       lands every single one on a real chest or lever block in the capture. That takes it to 122.</li>
 * </ol>
 *
 * <p>The two methods were cross-checked against each other before either was trusted: on the 55 rooms where
 * both apply they agree on 53. The two that disagree, Deathmite and Raccoon, are rooms whose captures are the
 * wrong SIZE (Deathmite is two tiles where the database says three), which is a separate fault this cannot fix
 * and must not paper over.
 *
 * <p>The 13 that stay unresolved default to 0 and say so once. Eight of them have no secrets at all, so the
 * answer does not matter for them; the five that do are Andesite, Chambers, Drop, End and Supertall.
 */
public final class RoomCaptureRotation {

    private static final Logger LOGGER = ModLog.get("killer560smod-roomsim");

    /** Derived once per room and kept - it is a property of the capture file, which does not change. */
    private static final Map<String, Integer> CACHE = new ConcurrentHashMap<>();

    /** Rooms already reported as unresolved, so the warning is one line per room and not one per build. */
    private static final java.util.Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private RoomCaptureRotation() {
    }

    /**
     * @return the quarter turn between this capture and the database's canonical orientation, in degrees,
     *         to be ADDED to the rotation the room is pasted at before any database coordinate is translated
     */
    public static int of(RoomLibrary.Room room) {
        if (room == null) {
            return 0;
        }
        return CACHE.computeIfAbsent(room.name, name -> derive(room));
    }

    /** Forget everything - for a room library reload, and for tests that rewrite captures. */
    public static void clearCache() {
        CACHE.clear();
        WARNED.clear();
    }

    private static int derive(RoomLibrary.Room room) {
        List<Integer> candidates = fromRoofMarker(room);
        if (candidates.size() > 1) {
            List<Integer> narrowed = narrowBySecrets(room, candidates);
            if (narrowed.size() == 1) {
                candidates = narrowed;
            }
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (WARNED.add(room.name)) {
            LOGGER.warn("Capture rotation for \"{}\" could not be determined ({} candidate(s)); assuming 0. "
                    + "Its secrets may be placed in the wrong corner of the room.",
                    room.name, candidates.size());
        }
        return 0;
    }

    /**
     * The corners of the room's TILE area that carry the blue terracotta marker, at the roof line.
     *
     * <p>Checked at the roof and not anywhere in the column, because blue terracotta is also an ordinary
     * decorative block: scanning the whole height found two or more "markers" in nine rooms, and restricting
     * to the roof line - which is where the real marker is, and the only place {@code findRotationAndCorner}
     * ever looks - cut that to nine ambiguous out of 135 rather than leaving decoration to outvote it.
     *
     * <p>The corner order matches {@link RoomDatabase#findRotationAndCorner} exactly: north-west, north-east,
     * south-east, south-west, giving 0, 90, 180, 270. If those two ever disagree the secrets move, so they are
     * written the same way round deliberately.
     */
    private static List<Integer> fromRoofMarker(RoomLibrary.Room room) {
        int m = room.margin;
        int[][] corners = {
            {m, m},
            {room.sizeX - 1 - m, m},
            {room.sizeX - 1 - m, room.sizeZ - 1 - m},
            {m, room.sizeZ - 1 - m},
        };
        int roof = roofLine(room, corners);
        List<Integer> hits = new ArrayList<>(4);
        if (roof == Integer.MIN_VALUE) {
            return allFour();
        }
        for (int i = 0; i < 4; i++) {
            String block = blockAt(room, corners[i][0], roof, corners[i][1]);
            if (block != null && block.startsWith("minecraft:blue_terracotta")) {
                hits.add(i * 90);
            }
        }
        return hits.isEmpty() ? allFour() : hits;
    }

    /**
     * The highest y at which any of the four corner columns holds the MARKER.
     *
     * <p>This used to be the highest y holding anything other than air, on the reasoning that that is the roof.
     * It stopped being true when captures got taller: a capture now runs to whatever its content reaches, and
     * an Ashfall preset has terrain and structure above the rooms, so "the highest non-air block in a corner
     * column" can be a hundred blocks above the roof with no marker anywhere near it. Redstone Warrior was
     * exactly that - a corner column occupied at y114 and the marker down at the real roof.
     *
     * <p>Looking for the marker itself is immune to anything above the room, and still prefers the real roof
     * marker over decorative terracotta lower down because it takes the HIGHEST one. Measured across his 135
     * captures (2026-09-30) it settles one room the old rule could not and disagrees with it on none.
     */
    private static int roofLine(RoomLibrary.Room room, int[][] corners) {
        for (int y = room.maxY; y >= room.minY; y--) {
            for (int[] c : corners) {
                String block = blockAt(room, c[0], y, c[1]);
                if (block != null && block.startsWith("minecraft:blue_terracotta")) {
                    return y;
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Keeps only the rotations under which EVERY chest and lever secret the database lists lands on a real
     * chest or lever block in the capture.
     *
     * <p>Every one, not most: one coincidence is easy in a room with several chests, and a rule that accepted
     * a majority would quietly prefer a wrong answer in exactly the rooms that are hardest to check by hand.
     * A secret outside the captured y band (the capture starts at y {@value RoomLibrary#MIN_Y}, and 29 of his
     * 167 chest secrets sit below it) is skipped rather than counted as a miss - it is missing from the
     * capture, which says nothing about the rotation.
     */
    private static List<Integer> narrowBySecrets(RoomLibrary.Room room, List<Integer> candidates) {
        RoomEntry entry = RoomDatabase.lookupByName(room.name);
        if (entry == null || entry.secretCoords == null) {
            return candidates;
        }
        List<RoomEntry.Pos> chests = entry.secretCoords.chest;
        List<RoomEntry.Pos> levers = entry.secretCoords.redstoneKey;
        if ((chests == null || chests.isEmpty()) && (levers == null || levers.isEmpty())) {
            return candidates;
        }
        List<Integer> kept = new ArrayList<>(candidates.size());
        for (int degrees : candidates) {
            if (allLandOn(room, chests, degrees, "chest") && allLandOn(room, levers, degrees, "lever")) {
                kept.add(degrees);
            }
        }
        if (!kept.isEmpty()) {
            return kept;
        }
        // Nothing landed EVERY secret, so score them instead and take a clear winner.
        //
        // Requiring all of them is the right first test - one coincidence is easy - but "all or nothing" threw
        // away rotations that placed most of a room's secrets correctly and left those rooms defaulting to 0,
        // which is a guess. Measured over his 135 captures (2026-09-30) this settles Catwalk, Pedestal and
        // Slime, each of which had one rotation landing secrets and three landing none.
        //
        // A clear winner only: the best rotation must land at least one and beat the runner-up outright. A tie
        // is still ambiguous, and saying so is better than picking the first of two.
        int bestDegrees = -1;
        int best = 0;
        int runnerUp = 0;
        for (int degrees : candidates) {
            int score = countLandOn(room, chests, degrees, "chest") + countLandOn(room, levers, degrees, "lever");
            if (score > best) {
                runnerUp = best;
                best = score;
                bestDegrees = degrees;
            } else if (score > runnerUp) {
                runnerUp = score;
            }
        }
        if (bestDegrees >= 0 && best > runnerUp) {
            return new ArrayList<>(List.of(bestDegrees));
        }
        return candidates;
    }

    /** How many of these secrets land on the block they should, at this rotation. */
    private static int countLandOn(RoomLibrary.Room room, List<RoomEntry.Pos> list, int degrees, String want) {
        if (list == null) {
            return 0;
        }
        int frameX = (degrees == 90 || degrees == 270) ? room.sizeZ : room.sizeX;
        int frameZ = (degrees == 90 || degrees == 270) ? room.sizeX : room.sizeZ;
        int landed = 0;
        for (RoomEntry.Pos p : list) {
            if (p.y < room.minY || p.y > room.maxY) {
                continue;
            }
            int[] local = RoomPlacer.rotateLocal(p.x + room.margin, p.z + room.margin, frameX, frameZ, degrees);
            String block = blockAt(room, local[0], p.y, local[1]);
            if (block != null && block.contains(want)) {
                landed++;
            }
        }
        return landed;
    }

    private static boolean allLandOn(RoomLibrary.Room room, List<RoomEntry.Pos> list, int degrees, String want) {
        if (list == null) {
            return true;
        }
        // The database's frame is the capture's own dimensions, un-rotated: a quarter turn swaps them.
        int frameX = (degrees == 90 || degrees == 270) ? room.sizeZ : room.sizeX;
        int frameZ = (degrees == 90 || degrees == 270) ? room.sizeX : room.sizeZ;
        for (RoomEntry.Pos p : list) {
            if (p.y < room.minY || p.y > room.maxY) {
                continue;
            }
            // The database measures from the tile corner; the capture window starts one wall outside it.
            int[] local = RoomPlacer.rotateLocal(p.x + room.margin, p.z + room.margin, frameX, frameZ, degrees);
            String block = blockAt(room, local[0], p.y, local[1]);
            if (block == null || !block.contains(want)) {
                return false;
            }
        }
        return true;
    }

    /** The captured block id at these room-local coordinates, or null where nothing was ever read. */
    private static String blockAt(RoomLibrary.Room room, int x, int y, int z) {
        if (x < 0 || z < 0 || x >= room.sizeX || z >= room.sizeZ
                || y < room.minY || y > room.maxY) {
            return null;
        }
        short id = room.blocks[room.index(x, y, z)];
        if (id < 0 || id >= room.palette.size()) {
            return null;
        }
        return room.palette.get(id);
    }

    private static List<Integer> allFour() {
        return new ArrayList<>(List.of(0, 90, 180, 270));
    }
}

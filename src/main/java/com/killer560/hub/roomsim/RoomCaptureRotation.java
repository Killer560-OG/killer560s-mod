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
 * rooms every chest, bat, item and marker the sim placed was in the wrong corner.
 *
 * <h2>How Hypixel does it, and why the sim cannot just copy it</h2>
 *
 * <p>Live, there is exactly one mechanism: {@code LiveMapFeature.findRoomRotation} reads the blue terracotta
 * roof marker through {@link RoomDatabase#findRotationAndCorner}. There is no secret matching, no door check
 * and no per-room table. A room whose marker is not found yet ("No blue-terracotta corner marker ...") is
 * simply left without a rotation - no waypoints, no solver - and retried once a second until the roof chunk
 * and roof height read right, at which point the waypoints appear. The single exception is Fairy, which has
 * no marker by design and is fixed at rotation 0 by its room TYPE. So a real Hypixel room always ends up
 * answered by its marker, because a real Hypixel room always has one.
 *
 * <p>A capture is not guaranteed to. Some were taken with the roof corner missing, and some - see below - are
 * not even the right shape. So the capture side weighs every piece of evidence it has instead of one.
 *
 * <h2>The evidence, all read from the capture</h2>
 *
 * <ol>
 *   <li><b>The roof marker</b> ({@value #MARKER_VOTES} votes). Blue terracotta at one corner of the tile area at
 *       the roof line, in the same corner order as {@link RoomDatabase#findRotationAndCorner}. Two marked
 *       corners give one vote each; three or four (blue terracotta used as roof MATERIAL) give nothing.</li>
 *   <li><b>The lapis corner</b> ({@value #LAPIS_VOTES} vote). The roof corners of a Catacombs room carry
 *       redstone blocks, and where a lapis block appears it is DIAGONALLY OPPOSITE the blue terracotta - in
 *       every capture where both are present (measured 2026-10-04 by {@code tools/layoutsim/rotation.sh};
 *       see docs/SIM.md). So a lapis corner names the marker's corner even when the marker itself was not
 *       captured. Worth less than the marker because it is an observation about the captures, not a rule any
 *       Hypixel-side code relies on.</li>
 *   <li><b>The database's secrets</b> (one vote per secret that lands). Every chest secret is turned each of the
 *       four ways and checked for a chest or trapped chest in the capture, every wither essence and redstone
 *       key for a player head. Bats and items are left out: neither is a block. A secret below or above the
 *       captured band says nothing and is skipped.</li>
 *   <li><b>The shape veto.</b> The database's own secret coordinates bound the room's canonical size, so on a
 *       long room a quarter turn that would lay them across the short side is impossible whatever the other
 *       evidence says. Only the rotations that survive this are voted on.</li>
 * </ol>
 *
 * <p>The rotation with the most votes wins outright, or there is no winner and 0 is used.
 *
 * <h2>Uncertain</h2>
 *
 * <p>A room is {@link #isUncertain uncertain} when there is no outright winner, when the winner rests on fewer
 * than two votes, when any source prefers a different rotation, or when the marker or the secrets point ONLY
 * at a rotation the shape veto forbids. That last one is not a tie to be broken: it is a capture laid along the
 * wrong axis or anchored a tile off, holding one tile of this room and a tile of its neighbour - the same fault
 * {@link RoomTileAudit} refuses when the neighbour happens to be captured too. No rotation is right for such a
 * capture, and a route recorded in it cannot match Hypixel until it is captured again.
 *
 * <p>Live on Hypixel none of this runs: the live map reads the real roof, and this class is only consulted for a
 * captured room in the sim.
 */
public final class RoomCaptureRotation {

    private static final Logger LOGGER = ModLog.get("killer560smod-roomsim");

    /** Votes for a single marked roof corner. Two marked corners get half each. */
    static final int MARKER_VOTES = 2;

    /** Votes for a single lapis roof corner, read as the marker's diagonal. */
    static final int LAPIS_VOTES = 1;

    /** Derived once per room and kept - it is a property of the capture file, which does not change. */
    private static final Map<String, Verdict> CACHE = new ConcurrentHashMap<>();

    private RoomCaptureRotation() {
    }

    /**
     * Everything that was weighed for one capture, and the answer.
     *
     * @param degrees    the capture's turn, added to the paste rotation before any database coordinate is
     *                   translated - what {@link #of} returns
     * @param uncertain  whether that answer should be doubted; see the class doc
     * @param reason     why it is uncertain, or how it was settled when it is not
     * @param marker     rotations named by blue terracotta at the roof line (raw, before the shape veto)
     * @param lapis      rotations named by a lapis corner's diagonal (raw)
     * @param allowed    rotations the database's secrets can fit the capture at
     * @param secretHits per rotation (index {@code degrees / 90}), how many secrets landed on their block
     * @param secretsTested how many database secrets sit inside the captured y band and could be checked
     * @param votes      per rotation, the weighed total; 0 for a rotation outside {@code allowed}
     */
    public record Verdict(String room, int degrees, boolean uncertain, String reason, List<Integer> marker,
                          List<Integer> lapis, List<Integer> allowed, int[] secretHits, int secretsTested,
                          int[] votes) {

        /** {@code "0:a 90:b 180:c 270:d"} for the secret hits. */
        public String secretScores() {
            return perRotation(secretHits);
        }

        /** {@code "0:a 90:b 180:c 270:d"} for the weighed votes. */
        public String voteScores() {
            return perRotation(votes);
        }

        private static String perRotation(int[] values) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(i == 0 ? "" : " ").append(i * 90).append(':').append(values[i]);
            }
            return sb.toString();
        }
    }

    /**
     * @return the quarter turn between this capture and the database's canonical orientation, in degrees,
     *         to be ADDED to the rotation the room is pasted at before any database coordinate is translated
     */
    public static int of(RoomLibrary.Room room) {
        Verdict v = verdict(room);
        return v == null ? 0 : v.degrees();
    }

    /** The full verdict for a capture, worked out once per library load. Null for a null room. */
    public static Verdict verdict(RoomLibrary.Room room) {
        if (room == null) {
            return null;
        }
        return CACHE.computeIfAbsent(room.name, name -> derive(room));
    }

    /**
     * The full verdict for a room by name, or null when no capture of that name is loaded.
     *
     * <p>Same cache as {@link #of}, so this can never disagree with the rotation the sim published.
     */
    public static Verdict verdict(String roomName) {
        if (roomName == null) {
            return null;
        }
        Verdict cached = CACHE.get(roomName);
        if (cached != null) {
            return cached;
        }
        return verdict(RoomLibrary.get(roomName));
    }

    /**
     * Whether this room's capture rotation is still in doubt after every piece of evidence was weighed - for
     * Auto Routes to warn before a route is recorded there, since a route recorded at a wrong capture rotation
     * replays turned on Hypixel.
     *
     * <p><b>Sim only.</b> On Hypixel the rotation is read live off the real roof and this says nothing about it.
     * {@code false} for a room with no capture loaded (nothing to doubt), and for Fairy, whose rotation is 0 by
     * the same rule the live map uses. {@link #verdict(String)}{@code .reason()} says why when it is true.
     */
    public static boolean isUncertain(String roomName) {
        Verdict v = verdict(roomName);
        return v != null && v.uncertain();
    }

    /** Forget everything - for a room library reload, and for tests that rewrite captures. */
    public static void clearCache() {
        CACHE.clear();
    }

    private static Verdict derive(RoomLibrary.Room room) {
        RoomEntry entry = RoomDatabase.lookupByName(room.name);
        List<Integer> marker = markerCorners(room);
        List<Integer> lapis = lapisCorners(room);
        int[] hits = new int[4];
        int tested = secretHits(room, entry, hits);

        boolean measurable = secretSpan(entry) != null;
        List<Integer> allowed = new ArrayList<>(4);
        for (int degrees = 0; degrees < 360; degrees += 90) {
            if (!measurable || secretsFitTheCapture(room, entry, degrees)) {
                allowed.add(degrees);
            }
        }
        boolean fitsNothing = allowed.isEmpty();
        if (fitsNothing) {
            allowed = allFour();
        }

        // Fairy has no marker by design, and the live map fixes it at 0 by room TYPE
        // (LiveMapFeature.findRoomRotation). Answering the same way is what makes a route recorded there agree
        // with Hypixel, so this is not a guess to be doubted.
        if (entry != null && "FAIRY".equalsIgnoreCase(entry.type) && marker.isEmpty()) {
            return log(new Verdict(room.name, 0, false, "Fairy: no marker by design, rotation 0 as on Hypixel",
                    marker, lapis, allowed, hits, tested, new int[4]));
        }

        List<Integer> markerIn = intersect(marker, allowed);
        List<Integer> lapisIn = intersect(lapis, allowed);
        int[] votes = new int[4];
        if (markerIn.size() == 1) {
            votes[markerIn.get(0) / 90] += MARKER_VOTES;
        } else if (markerIn.size() == 2) {
            for (int d : markerIn) {
                votes[d / 90] += MARKER_VOTES / 2;
            }
        }
        if (lapisIn.size() == 1) {
            votes[lapisIn.get(0) / 90] += LAPIS_VOTES;
        }
        for (int d : allowed) {
            votes[d / 90] += hits[d / 90];
        }

        Integer winner = uniqueMax(votes, allowed);
        Integer markerPref = markerIn.size() == 1 ? markerIn.get(0) : null;
        Integer lapisPref = lapisIn.size() == 1 ? lapisIn.get(0) : null;
        Integer secretsPref = uniqueMax(hits, allowed);

        int hitsIn = 0;
        int hitsOut = 0;
        for (int d = 0; d < 360; d += 90) {
            if (allowed.contains(d)) {
                hitsIn = Math.max(hitsIn, hits[d / 90]);
            } else {
                hitsOut = Math.max(hitsOut, hits[d / 90]);
            }
        }
        boolean markerImpossible = !marker.isEmpty() && markerIn.isEmpty();
        boolean secretsImpossible = hitsOut > hitsIn;

        List<String> doubts = new ArrayList<>();
        if (fitsNothing) {
            doubts.add("the database's secrets fit this capture at no rotation, so it is the wrong size");
        }
        if (markerImpossible || secretsImpossible) {
            doubts.add((markerImpossible ? "the roof marker " + marker : "")
                    + (markerImpossible && secretsImpossible ? " and " : "")
                    + (secretsImpossible ? "the secrets (" + perRotation(hits) + ")" : "")
                    + (markerImpossible && secretsImpossible ? " point" : " points") + " at a turn this " + tiles(room.sizeX) + "x" + tiles(room.sizeZ)
                    + " capture cannot have - it looks laid along the wrong axis or a tile off, holding one tile"
                    + " of this room and one of a neighbour; re-capture it");
        }
        if (hitsIn == 0 && hitsOut == 0 && tested > 0 && !markerImpossible) {
            String shifted = shiftedLanding(room, entry);
            if (shifted != null) {
                doubts.add(shifted);
            }
        }
        if (winner == null) {
            doubts.add("nothing separates " + allowed + " (votes " + perRotation(votes) + ")");
        } else {
            if (votes[winner / 90] < 2) {
                doubts.add("only " + votes[winner / 90] + " vote for " + winner);
            }
            if (markerPref != null && !markerPref.equals(winner)) {
                doubts.add("the roof marker says " + markerPref);
            }
            if (lapisPref != null && !lapisPref.equals(winner)) {
                doubts.add("the lapis corner says " + lapisPref);
            }
            if (secretsPref != null && !secretsPref.equals(winner)) {
                doubts.add("the secrets say " + secretsPref);
            }
        }

        int degrees = winner == null ? 0 : winner;
        String reason;
        if (doubts.isEmpty()) {
            List<String> agree = new ArrayList<>(3);
            if (winner.equals(markerPref)) {
                agree.add("marker");
            }
            if (winner.equals(lapisPref)) {
                agree.add("lapis");
            }
            if (hits[winner / 90] > 0) {
                agree.add(hits[winner / 90] + " secret(s)");
            }
            reason = String.join(" + ", agree);
        } else {
            reason = String.join("; ", doubts);
        }
        Verdict v = new Verdict(room.name, degrees, !doubts.isEmpty(), reason, marker, lapis, allowed, hits,
                tested, votes);
        if (markerPref != null && secretsPref != null && !markerPref.equals(secretsPref)) {
            LOGGER.warn("Capture rotation for \"{}\": the roof marker says {} but the database's secrets say {} "
                    + "({}); using {}, the one with more evidence ({}).", room.name, markerPref, secretsPref,
                    perRotation(hits), degrees, perRotation(votes));
        }
        return log(v);
    }

    /** One INFO line per room per library load, and a WARN as well when the answer is in doubt. */
    private static Verdict log(Verdict v) {
        LOGGER.info("Capture rotation \"{}\": {} - marker {} lapis {} allowed {} secrets {} ({} checked) "
                + "votes {} - {}", v.room(), v.degrees(), v.marker(), v.lapis(), v.allowed(), v.secretScores(),
                v.secretsTested(), v.voteScores(), v.reason());
        if (v.uncertain()) {
            LOGGER.warn("Capture rotation for \"{}\" is UNCERTAIN, using {}: {}. Waypoints, puzzles and routes "
                    + "in this room may come out turned relative to Hypixel.", v.room(), v.degrees(), v.reason());
        }
        return v;
    }

    /** The rotation with strictly the highest positive score among {@code among}, or null on a tie or all 0. */
    private static Integer uniqueMax(int[] score, List<Integer> among) {
        Integer best = null;
        int top = 0;
        int second = 0;
        for (int d : among) {
            int s = score[d / 90];
            if (s > top) {
                second = top;
                top = s;
                best = d;
            } else if (s > second) {
                second = s;
            }
        }
        return best != null && top > second ? best : null;
    }

    private static List<Integer> intersect(List<Integer> a, List<Integer> b) {
        List<Integer> out = new ArrayList<>(a.size());
        for (int d : a) {
            if (b.contains(d)) {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * How many of the database's chest, wither essence and redstone key secrets land on their block at each
     * rotation, written into {@code hits} (index {@code degrees / 90}).
     *
     * <p>A chest secret must be a chest or trapped chest; a wither essence or a redstone key is a player head
     * (checked in the captures: every redstone key position in Golden Oasis, Redstone Crypt and Redstone Key
     * is a {@code player_head} at the answer the marker gives, never a lever, which is what this used to look
     * for). Skeleton skulls are decoration and do not count.
     *
     * @return how many secrets sit in the captured y band and so could be checked at all
     */
    private static int secretHits(RoomLibrary.Room room, RoomEntry entry, int[] hits) {
        if (entry == null || entry.secretCoords == null) {
            return 0;
        }
        int tested = 0;
        tested += score(room, entry.secretCoords.chest, false, hits);
        tested += score(room, entry.secretCoords.wither, true, hits);
        tested += score(room, entry.secretCoords.redstoneKey, true, hits);
        return tested;
    }

    private static int score(RoomLibrary.Room room, List<RoomEntry.Pos> list, boolean head, int[] hits) {
        if (list == null) {
            return 0;
        }
        int tested = 0;
        for (RoomEntry.Pos p : list) {
            if (p.y < room.minY || p.y > room.maxY) {
                continue;   // below the captured band: missing from the capture, which says nothing
            }
            tested++;
            for (int degrees = 0; degrees < 360; degrees += 90) {
                // The database's frame is the capture's own dimensions, un-rotated: a quarter turn swaps them.
                // The database measures from the tile corner; the capture window starts one wall outside it.
                int frameX = (degrees == 90 || degrees == 270) ? room.sizeZ : room.sizeX;
                int frameZ = (degrees == 90 || degrees == 270) ? room.sizeX : room.sizeZ;
                int[] local = RoomPlacer.rotateLocal(p.x + room.margin, p.z + room.margin, frameX, frameZ,
                        degrees);
                String block = blockAt(room, local[0], p.y, local[1]);
                if (block != null && (head ? isHead(block) : block.contains("chest"))) {
                    hits[degrees / 90]++;
                }
            }
        }
        return tested;
    }

    /**
     * A diagnosis, never an answer: when no secret lands at any rotation, whether they land with the room moved
     * one tile in some direction - the signature of a capture box anchored a tile off, so that it holds one tile
     * of the room and one of its neighbour. Balcony does exactly this (both chests land at 180 one tile west).
     *
     * @return a sentence for the log, or null when no shift lands anything either
     */
    private static String shiftedLanding(RoomLibrary.Room room, RoomEntry entry) {
        int step = RoomLibrary.TILE + 1;
        int best = 0;
        String where = null;
        for (int degrees = 0; degrees < 360; degrees += 90) {
            for (int sx = -1; sx <= 1; sx++) {
                for (int sz = -1; sz <= 1; sz++) {
                    if (sx == 0 && sz == 0) {
                        continue;
                    }
                    int landed = 0;
                    for (int pass = 0; pass < 3; pass++) {
                        List<RoomEntry.Pos> list = pass == 0 ? entry.secretCoords.chest
                                : pass == 1 ? entry.secretCoords.wither : entry.secretCoords.redstoneKey;
                        if (list == null) {
                            continue;
                        }
                        for (RoomEntry.Pos p : list) {
                            int frameX = (degrees == 90 || degrees == 270) ? room.sizeZ : room.sizeX;
                            int frameZ = (degrees == 90 || degrees == 270) ? room.sizeX : room.sizeZ;
                            int[] local = RoomPlacer.rotateLocal(p.x + room.margin, p.z + room.margin, frameX,
                                    frameZ, degrees);
                            String block = blockAt(room, local[0] + sx * step, p.y, local[1] + sz * step);
                            if (block != null && (pass == 0 ? block.contains("chest") : isHead(block))) {
                                landed++;
                            }
                        }
                    }
                    if (landed > best) {
                        best = landed;
                        where = "at " + degrees + " moved " + (sx * step) + "," + (sz * step);
                    }
                }
            }
        }
        if (where == null) {
            return null;
        }
        return "no secret lands at any rotation, but " + best + " land " + where + " - the capture looks anchored a "
                + "tile off, holding one tile of this room and one of a neighbour; re-capture it";
    }

    private static boolean isHead(String block) {
        return block.startsWith("minecraft:player_head") || block.startsWith("minecraft:player_wall_head");
    }

    /**
     * The corners of the room's TILE area that carry the blue terracotta marker, at the roof line, as
     * rotations; empty when there is none.
     *
     * <p>Checked at the roof and not anywhere in the column, because blue terracotta is also an ordinary
     * decorative block: scanning the whole height found two or more "markers" in nine rooms.
     *
     * <p>The corner order matches {@link RoomDatabase#findRotationAndCorner} exactly: north-west, north-east,
     * south-east, south-west, giving 0, 90, 180, 270. If those two ever disagree the secrets move, so they are
     * written the same way round deliberately.
     */
    private static List<Integer> markerCorners(RoomLibrary.Room room) {
        int[][] corners = corners(room);
        int roof = roofLine(room, corners);
        List<Integer> hits = new ArrayList<>(4);
        if (roof == Integer.MIN_VALUE) {
            return hits;
        }
        for (int i = 0; i < 4; i++) {
            String block = blockAt(room, corners[i][0], roof, corners[i][1]);
            if (block != null && block.startsWith("minecraft:blue_terracotta")) {
                hits.add(i * 90);
            }
        }
        return hits;
    }

    /**
     * The rotations named by a lapis roof corner: the corner diagonally opposite a lapis block is where the
     * marker belongs. Each corner column is read down from the top to its first redstone block, lapis block or
     * blue terracotta - the three blocks a room's roof corners are made of - so terrain above the room does not
     * count and the corner's own roof block does.
     */
    private static List<Integer> lapisCorners(RoomLibrary.Room room) {
        int[][] corners = corners(room);
        List<Integer> out = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            for (int y = room.maxY; y >= room.minY; y--) {
                String block = blockAt(room, corners[i][0], y, corners[i][1]);
                if (block == null) {
                    continue;
                }
                if (block.startsWith("minecraft:lapis_block")) {
                    out.add((i * 90 + 180) % 360);
                    break;
                }
                if (block.startsWith("minecraft:redstone_block") || block.startsWith("minecraft:blue_terracotta")) {
                    break;
                }
            }
        }
        return out;
    }

    private static int[][] corners(RoomLibrary.Room room) {
        int m = room.margin;
        return new int[][]{
            {m, m},
            {room.sizeX - 1 - m, m},
            {room.sizeX - 1 - m, room.sizeZ - 1 - m},
            {m, room.sizeZ - 1 - m},
        };
    }

    /**
     * The highest y at which any of the four corner columns holds the MARKER.
     *
     * <p>This used to be the highest y holding anything other than air, on the reasoning that that is the roof.
     * It stopped being true when captures got taller: an Ashfall preset has terrain and structure above the
     * rooms, so "the highest non-air block in a corner column" can be a hundred blocks above the roof with no
     * marker anywhere near it. Redstone Warrior was exactly that - a corner column occupied at y114 and the
     * marker down at the real roof. Taking the HIGHEST marker still prefers the roof over decoration lower down.
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

    private static String perRotation(int[] values) {
        return Verdict.perRotation(values);
    }

    /** Tiles across, from a captured size - 33 is one tile, 65 two, 129 four. */
    private static int tiles(int size) {
        return (size - 1) / (RoomLibrary.TILE + 1);
    }

    /**
     * How far the database's secrets reach from the room's corner, as {@code {maxX, maxZ}}, or null.
     *
     * <p>Every secret list counts, not just chests: the question is how big the room is in the database's own
     * frame, and a bat or an item marks that out as well as a chest does. Y is irrelevant here.
     */
    private static int[] secretSpan(RoomEntry entry) {
        if (entry == null || entry.secretCoords == null) {
            return null;
        }
        int maxX = -1;
        int maxZ = -1;
        for (List<RoomEntry.Pos> list : List.of(
                nullToEmpty(entry.secretCoords.chest), nullToEmpty(entry.secretCoords.bat),
                nullToEmpty(entry.secretCoords.item), nullToEmpty(entry.secretCoords.wither),
                nullToEmpty(entry.secretCoords.redstoneKey))) {
            for (RoomEntry.Pos p : list) {
                maxX = Math.max(maxX, p.x);
                maxZ = Math.max(maxZ, p.z);
            }
        }
        return maxX < 0 ? null : new int[]{maxX, maxZ};
    }

    private static List<RoomEntry.Pos> nullToEmpty(List<RoomEntry.Pos> list) {
        return list == null ? List.of() : list;
    }

    /**
     * Whether the database's secrets still land inside the capture once turned by {@code degrees}.
     *
     * <p>A bound, not a placement: it asks only whether the room is the right shape for them. That is enough
     * to rule out a quarter turn on a long room - the roof marker named one for Waterfall, Skull and Purple
     * Flags on his 2026-09-30 floor and the secrets fell out of the room - and it rules out nothing on a square
     * one.
     */
    private static boolean secretsFitTheCapture(RoomLibrary.Room room, RoomEntry entry, int degrees) {
        int[] span = secretSpan(entry);
        if (span == null) {
            return true;   // nothing to measure against is not a reason to reject a rotation
        }
        boolean quarter = degrees == 90 || degrees == 270;
        int alongX = quarter ? span[1] : span[0];
        int alongZ = quarter ? span[0] : span[1];
        // The capture takes in one column of wall on each side, so its own size is the tile area plus the
        // margins; a secret embedded in a wall is allowed for by the same tolerance SimSecrets uses.
        return alongX < room.sizeX + OUTSIDE_TOLERANCE && alongZ < room.sizeZ + OUTSIDE_TOLERANCE;
    }

    /** The same tolerance {@code SimSecrets} places with - a few real secrets sit inside a room's wall. */
    private static final int OUTSIDE_TOLERANCE = 2;

}

package com.killer560.hub.roomsim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Lays a floor out by fitting rooms' real doorways together.
 *
 * <p>killer560 (2026-09-29): "it still isnt generating a true map. It should read which rooms have doors where
 * and make sure that each room is reachable via going through doors into rooms. Some rooms only have one door
 * so they have to be at the end of a split like puzzles and trap and some other rooms. [...] But the rooms need
 * to be connected by doors."
 *
 * <p>The old generator grew a blob of cells and then knocked a hole through whatever wall lay between two of
 * them. Every room was reachable, but nothing about the floor came from the rooms: a puzzle ended up in the
 * middle with four holes in it, a room's own doorways opened onto solid rock, and the shape was a blob rather
 * than a dungeon. This grows the floor the way the doors allow instead - start at the entrance, take an unused
 * doorway, and find a room and a rotation whose own doorway faces back through it.
 *
 * <p><b>Connectivity is by construction.</b> A room is only ever placed by attaching it to a doorway of a room
 * already placed, so there is no such thing as an unreachable wing and no pass afterwards to check for one.
 * A one-door room takes a doorway and offers none, which is what makes puzzles and traps come out at the end
 * of a branch without anything having to say so.
 *
 * <p><b>What is chosen, and why.</b> At each step every legal (room, rotation, position) is scored: a doorway
 * that meets a neighbour's doorway is worth most (those are the loops a real floor has), a doorway onto a free
 * cell is worth something (that is where the floor grows next), and a doorway that would end up facing a
 * neighbour's blank wall costs, because it has to be bricked up afterwards. Size is traded against doorway
 * count - the first version preferred whichever room had the most doors, which is always a 2x2, and filled the
 * grid with six enormous rooms; preferring small rooms alone is worse still, because small rooms have few
 * doors and the floor stops after four of them.
 *
 * <p>Measured over 200 floors against his 135 captured rooms: 21 of 21 rooms placed every time, every floor
 * fully connected, no room overlapping another, no one-door room with more than one connection, about 28 doors
 * and 3 rooms bigger than 1x1 per floor, and about five doorways left to brick up. Thirteen milliseconds a
 * floor, so the caller can afford to lay several out and keep the best.
 */
public final class SimFloorLayout {

    /** The room grid inside {@link com.killer560.hub.livemap.DungeonLayout}'s 11x11. */
    public static final int GRID = (com.killer560.hub.livemap.DungeonLayout.GRID + 1) / 2;

    /** One room, placed. {@code rotation} is degrees clockwise, as {@link RoomPlacer#paste} takes it. */
    public record Placement(String name, int rotation, int originX, int originZ,
                            int cellsX, int cellsZ, int depth, String type) {
    }

    /** A doorway between two room cells. */
    public record Link(int aX, int aZ, int bX, int bZ) {
    }

    /** A laid-out floor. {@code openDoors} are doorways with nothing on the other side. */
    public record Floor(List<Placement> rooms, List<Link> links, List<int[]> openDoors, int bloodDepth) {
    }

    /** A room the layout may use: its name, type, and its doorways at all four rotations. */
    private record Candidate(String name, String type, RoomDoors.Mask[] byRotation) {

        int doorCount() {
            return byRotation[0].edges().size();
        }

        int area(int rotationIndex) {
            return byRotation[rotationIndex].tilesX() * byRotation[rotationIndex].tilesZ();
        }
    }

    /** A doorway of a placed room that nothing has been attached to yet. */
    private static final class Stub {
        final int cellX;
        final int cellZ;
        final int side;
        final int depth;
        final int owner;

        Stub(int cellX, int cellZ, int side, int depth, int owner) {
            this.cellX = cellX;
            this.cellZ = cellZ;
            this.side = side;
            this.depth = depth;
            this.owner = owner;
        }
    }

    /**
     * How many floors to lay out before picking one.
     *
     * <p>A single attempt reaches the room count it was asked for about four times in five; the rest stall
     * early because the entrance happened to land in a corner facing a wall. Laying out ten and keeping the
     * best takes about an eighth of a second and turns "usually a full floor" into "always one".
     */
    private static final int ATTEMPTS = 10;

    /**
     * Rooms of which a generated floor may hold at most ONE between them.
     *
     * <p>killer560 (2026-09-29): "unless it is a hand designed map make sure it can at max only have one
     * instance of higher or lower." Higher Blaze and Lower Blaze are the two ends of the same blaze room, so
     * a floor with both is a floor with the same puzzle on it twice. Repeats of a single room were already
     * impossible - a name is struck off once it is used - but the pair needed saying.
     *
     * <p>A drawn map is NOT filtered: the editor builds exactly what he put on the grid, which is the whole
     * point of it, and this class is only used by the random generator.
     */
    private static final java.util.List<java.util.Set<String>> EXCLUSIVE_GROUPS = java.util.List.of(
            java.util.Set.of("higher blaze", "lower blaze"));

    private SimFloorLayout() {
    }

    /** Whether this room is shut out by one already on the floor - see {@link #EXCLUSIVE_GROUPS}. */
    private static boolean excluded(String name, Set<String> used) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (java.util.Set<String> group : EXCLUSIVE_GROUPS) {
            if (!group.contains(lower)) {
                continue;
            }
            for (String taken : used) {
                if (group.contains(taken.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Lays out the best of {@link #ATTEMPTS} floors.
     *
     * @param wantRooms  how many rooms the floor should have
     * @param puzzles    how many puzzle rooms to try to include
     * @param bloodDepth how many doorways from the entrance the blood room should be
     * @return the floor, or null when there is not even an entrance room captured
     */
    public static Floor generate(Map<String, RoomLibrary.Room> usable, int wantRooms, int puzzles,
                                 int bloodDepth, Random rng) {
        List<Candidate> pool = candidates(usable);
        if (pool.isEmpty()) {
            return null;
        }
        Floor best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            Floor floor = growOnce(pool, wantRooms, puzzles, bloodDepth, rng);
            if (floor == null) {
                continue;
            }
            // Rooms first, then having a blood room at all, then fewest doorways left to brick up.
            int score = floor.rooms().size() * 1000
                    + (floor.bloodDepth() >= 0 ? 500 : 0)
                    - floor.openDoors().size();
            if (score > bestScore) {
                bestScore = score;
                best = floor;
            }
        }
        return best;
    }

    /** Every usable room, with its doorways pre-rotated so the inner loop never measures anything. */
    private static List<Candidate> candidates(Map<String, RoomLibrary.Room> usable) {
        List<Candidate> out = new ArrayList<>();
        for (Map.Entry<String, RoomLibrary.Room> e : usable.entrySet()) {
            RoomDoors.Mask mask = RoomDoors.of(e.getKey());
            if (mask == null) {
                continue;
            }
            String type = SimFloorGen.typeOf(e.getKey());
            // L-SHAPED ROOMS ARE LEFT OUT of a generated floor.
            //
            // An L room is captured as its 2x2 bounding box, and the quarter it does not occupy is whatever
            // stood there on the day it was scanned. Checked across the eleven L rooms in his library: only
            // Altar and Well have that quarter empty; the other nine are 45-65% full of a NEIGHBOURING room's
            // geometry, which a paste drops into the middle of the floor. It also makes the dungeon map draw
            // them as two rooms, because the map refuses to merge more tiles than the room's shape has.
            //
            // Fixing it properly means recording which quadrant is real at capture time. Until then a floor
            // without them is better than a floor with a quarter of someone else's room in it. A hand-drawn
            // map is not filtered - the editor builds exactly what he put on the grid.
            if ("L".equalsIgnoreCase(SimFloorGen.shapeOf(e.getKey()))) {
                continue;
            }
            RoomDoors.Mask[] byRotation = new RoomDoors.Mask[4];
            for (int r = 0; r < 4; r++) {
                byRotation[r] = RoomDoors.rotate(mask, r * 90);
            }
            out.add(new Candidate(e.getKey(), type, byRotation));
        }
        return out;
    }

    private static Floor growOnce(List<Candidate> pool, int wantRooms, int puzzles, int bloodDepth,
                                  Random rng) {
        Map<String, Candidate> byName = new HashMap<>();
        List<Candidate> normal = new ArrayList<>();
        List<Candidate> puzzleRooms = new ArrayList<>();
        for (Candidate c : pool) {
            byName.put(c.name().toLowerCase(Locale.ROOT), c);
            switch (c.type().toUpperCase(Locale.ROOT)) {
                case "PUZZLE" -> puzzleRooms.add(c);
                case "BLOOD", "ENTRANCE", "FAIRY" -> {
                    // Given rooms are placed deliberately, never picked at random.
                }
                default -> normal.add(c);
            }
        }
        Candidate entrance = byName.get("entrance");
        if (entrance == null || normal.isEmpty()) {
            return null;
        }

        int[] occupied = new int[GRID * GRID];
        java.util.Arrays.fill(occupied, -1);
        List<Placement> placed = new ArrayList<>();
        List<Link> links = new ArrayList<>();
        List<Stub> stubs = new ArrayList<>();
        Set<String> used = new HashSet<>();

        // The entrance starts on an edge of the grid, the way a real floor's does.
        int entranceRotation = rng.nextInt(4);
        RoomDoors.Mask em = entrance.byRotation()[entranceRotation];
        int ex = rng.nextBoolean() ? 0 : GRID - em.tilesX();
        int ez = rng.nextInt(Math.max(1, GRID - em.tilesZ() + 1));
        if (rng.nextBoolean()) {
            int swap = ex;
            ex = rng.nextInt(Math.max(1, GRID - em.tilesX() + 1));
            ez = swap == 0 ? 0 : GRID - em.tilesZ();
        }
        commit(entrance, entranceRotation, ex, ez, 0, occupied, placed, stubs, used);

        int puzzlesLeft = puzzles;
        int bloodPlacedDepth = -1;
        // An array, not a local, because it is read inside the lambda-free loop below and reassigned there -
        // and because the fairy room is optional, a plain boolean would be just as correct but harder to see.
        boolean[] fairyPlaced = {false};
        // Cells the fairy room covers, so blood is never put next door to it.
        //
        // killer560 (2026-09-29): "make sure one room must generate between green and fairy and fairy and
        // blood for any given blood rush path. So the 2 room minimum is to fulfill this rule." The first half
        // is already true - the fairy is never attached to a doorway of the entrance itself, so there is
        // always a room in between - and this is the second half.
        java.util.Set<Long> fairyCells = new HashSet<>();
        List<Stub> failed = new ArrayList<>();

        for (int pass = 0; pass < 2; pass++) {
            boolean relaxed = pass == 1;
            if (relaxed) {
                stubs.addAll(failed);
                failed.clear();
            }
            int guard = 0;
            while (placed.size() < wantRooms && !stubs.isEmpty() && guard++ < 4000) {
                int si = rng.nextInt(stubs.size());
                Stub stub = stubs.get(si);
                int tx = stub.cellX + RoomDoors.DX[stub.side];
                int tz = stub.cellZ + RoomDoors.DZ[stub.side];
                if (tx < 0 || tz < 0 || tx >= GRID || tz >= GRID || occupied[tz * GRID + tx] >= 0) {
                    stubs.remove(si);
                    continue;
                }

                List<Candidate> wanted;
                // Blood at the depth he asked for, or at the last chance the floor gives.
                //
                // The depth is a request, not a promise: an eleven-room Entrance floor asked for blood nine
                // doorways in simply never got one, because no branch ran that deep. A floor with no blood
                // room is not a floor, so once there are only a couple of rooms left to place it goes in
                // wherever the next stub is.
                boolean wantBlood = bloodPlacedDepth < 0 && byName.containsKey("blood")
                        && (stub.depth + 1 >= bloodDepth || placed.size() >= wantRooms - 2)
                        && !touchesFairy(tx, tz, fairyCells);
                boolean wantFairy = !fairyPlaced[0] && !wantBlood && stub.depth >= 1
                        && byName.containsKey("fairy") && rng.nextDouble() < 0.25;
                if (wantBlood) {
                    wanted = List.of(byName.get("blood"));
                } else if (wantFairy) {
                    wanted = List.of(byName.get("fairy"));
                } else if (puzzlesLeft > 0 && !puzzleRooms.isEmpty() && rng.nextDouble() < 0.35) {
                    wanted = puzzleRooms;
                } else {
                    wanted = normal;
                }
                Best best = choose(wanted, used, stub, tx, tz, occupied, stubs, wantRooms - placed.size(),
                        wantBlood || wantFairy || wanted == puzzleRooms, rng);
                if (best == null && wanted != normal) {
                    best = choose(normal, used, stub, tx, tz, occupied, stubs,
                            wantRooms - placed.size(), false, rng);
                }
                if (best == null) {
                    failed.add(stubs.remove(si));
                    continue;
                }

                int idx = commit(best.candidate, best.rotation, best.originX, best.originZ, stub.depth + 1,
                        occupied, placed, stubs, used);
                links.add(new Link(stub.cellX, stub.cellZ, tx, tz));
                stubs.remove(si);
                consume(stubs, tx, tz, (stub.side + 2) % 4, idx);
                // Any of the new room's other doorways that meets a doorway facing back is a door too - this
                // is what puts loops in the floor instead of a tree.
                // DEGREES divided down to an index. Best.rotation is degrees, like everything else that
                // crosses into RoomPlacer, and indexing a four-element array with 180 is what it sounds like.
                RoomDoors.Mask mask = best.candidate.byRotation()[best.rotation / 90];
                for (int[] door : RoomDoors.doorCells(mask, best.originX, best.originZ)) {
                    int nx = door[0] + RoomDoors.DX[door[2]];
                    int nz = door[1] + RoomDoors.DZ[door[2]];
                    if (nx < 0 || nz < 0 || nx >= GRID || nz >= GRID) {
                        continue;
                    }
                    int neighbour = occupied[nz * GRID + nx];
                    if (neighbour < 0 || neighbour == idx) {
                        continue;
                    }
                    if (consume(stubs, nx, nz, (door[2] + 2) % 4, neighbour)) {
                        links.add(new Link(door[0], door[1], nx, nz));
                        consume(stubs, door[0], door[1], door[2], idx);
                    }
                }
                if ("PUZZLE".equalsIgnoreCase(best.candidate.type())) {
                    puzzlesLeft--;
                }
                if (wantFairy) {
                    fairyPlaced[0] = true;
                    RoomDoors.Mask fm = best.candidate.byRotation()[best.rotation / 90];
                    for (int a = 0; a < fm.tilesX(); a++) {
                        for (int c = 0; c < fm.tilesZ(); c++) {
                            fairyCells.add(cellKey(best.originX + a, best.originZ + c));
                        }
                    }
                }
                if (wantBlood) {
                    bloodPlacedDepth = stub.depth + 1;
                    // Blood is the end of the run: nothing is attached beyond it.
                    stubs.removeIf(s -> s.owner == idx);
                }
            }
            if (placed.size() >= wantRooms) {
                break;
            }
        }

        // Doorways with nothing on the other side. They are real holes in real walls, so the builder bricks
        // them up rather than leaving the floor open to the void.
        List<int[]> open = new ArrayList<>();
        for (Stub s : stubs) {
            open.add(new int[]{s.cellX, s.cellZ, s.side});
        }
        for (Stub s : failed) {
            open.add(new int[]{s.cellX, s.cellZ, s.side});
        }
        return new Floor(placed, links, open, bloodPlacedDepth);
    }

    private record Best(Candidate candidate, int rotation, int originX, int originZ) {
    }

    private static long cellKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** Whether a cell is next door to the fairy room - blood may not be, so a room always sits between. */
    private static boolean touchesFairy(int x, int z, java.util.Set<Long> fairyCells) {
        if (fairyCells.isEmpty()) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if (fairyCells.contains(cellKey(x + RoomDoors.DX[i], z + RoomDoors.DZ[i]))) {
                return true;
            }
        }
        return fairyCells.contains(cellKey(x, z));
    }

    /**
     * The best room, rotation and position for one doorway, or null when nothing fits.
     *
     * <p>{@code allowDeadEnd} lets the caller ask for a one-door room deliberately (a puzzle, or blood). The
     * rest of the time a one-door room is refused while the floor still needs several more, because taking one
     * spends a doorway and offers none back - a floor built out of them stops after four rooms.
     */
    private static Best choose(List<Candidate> pool, Set<String> used, Stub stub, int tx, int tz,
                               int[] occupied, List<Stub> stubs, int remaining, boolean allowDeadEnd,
                               Random rng) {
        int need = (stub.side + 2) % 4;
        List<Candidate> shortlist = new ArrayList<>();
        for (Candidate c : pool) {
            if (used.contains(c.name()) || excluded(c.name(), used)) {
                continue;   // a floor does not repeat a room, or a room's other half
            }
            if (!allowDeadEnd && remaining > 2 && c.doorCount() <= 1) {
                continue;
            }
            shortlist.add(c);
        }
        if (shortlist.isEmpty()) {
            // Nothing left that has not already been used. Returning null - which sends the caller to the
            // normal pool, or drops the stub - is right; refilling from the pool put a SECOND copy of the
            // same room on the floor, and when the pool was the single-element blood list that meant two
            // blood rooms with a door between them.
            return null;
        }
        java.util.Collections.shuffle(shortlist, rng);
        // Small rooms, but not at the cost of the floor stopping: each doorway is worth a little under one
        // cell of size. Measured - at this weighting a floor comes out as 21 rooms over about 29 cells with
        // three of them bigger than 1x1, which is the mix a real Catacombs floor has.
        //
        // The jitter is drawn ONCE PER ROOM and stored, not called from inside the comparator. A comparator
        // that rolls a die gives different answers for the same pair, TimSort notices, and the whole sort
        // throws "Comparison method violates its general contract!" - which is what scenario 73 hit on
        // 2026-09-29 and is why the generator has to be tested against his real 135-room library rather than
        // a handful of shapes.
        Map<String, Double> key = new HashMap<>();
        for (Candidate c : shortlist) {
            key.put(c.name(), c.area(0) * 0.45 - c.doorCount() * 0.9 + rng.nextDouble());
        }
        shortlist.sort(Comparator.comparingDouble(c -> key.get(c.name())));

        Best best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int examined = 0;
        for (Candidate c : shortlist) {
            if (examined++ > 60) {
                break;
            }
            for (int rotation = 0; rotation < 4; rotation++) {
                RoomDoors.Mask mask = c.byRotation()[rotation];
                for (int packed : mask.edges()) {
                    if (RoomDoors.sideOf(packed) != need) {
                        continue;
                    }
                    int index = RoomDoors.indexOf(packed);
                    int originX;
                    int originZ;
                    switch (need) {
                        case RoomDoors.NORTH -> {
                            originX = tx - index;
                            originZ = tz;
                        }
                        case RoomDoors.SOUTH -> {
                            originX = tx - index;
                            originZ = tz - mask.tilesZ() + 1;
                        }
                        case RoomDoors.WEST -> {
                            originX = tx;
                            originZ = tz - index;
                        }
                        default -> {
                            originX = tx - mask.tilesX() + 1;
                            originZ = tz - index;
                        }
                    }
                    if (!fits(occupied, originX, originZ, mask.tilesX(), mask.tilesZ())) {
                        continue;
                    }
                    double score = score(mask, originX, originZ, tx, tz, need, occupied, stubs, rng);
                    if (score > bestScore) {
                        bestScore = score;
                        best = new Best(c, rotation * 90, originX, originZ);
                    }
                }
            }
            if (best != null && bestScore >= 2.0) {
                break;
            }
        }
        return best;
    }

    private static double score(RoomDoors.Mask mask, int originX, int originZ, int tx, int tz, int need,
                                int[] occupied, List<Stub> stubs, Random rng) {
        int matched = 0;
        int growable = 0;
        int seal = 0;
        for (int[] door : RoomDoors.doorCells(mask, originX, originZ)) {
            if (door[0] == tx && door[1] == tz && door[2] == need) {
                continue;   // the doorway being attached through
            }
            int nx = door[0] + RoomDoors.DX[door[2]];
            int nz = door[1] + RoomDoors.DZ[door[2]];
            if (nx < 0 || nz < 0 || nx >= GRID || nz >= GRID) {
                seal++;
            } else if (occupied[nz * GRID + nx] >= 0) {
                if (hasStub(stubs, nx, nz, (door[2] + 2) % 4)) {
                    matched++;
                } else {
                    seal++;
                }
            } else {
                growable++;
            }
        }
        return matched * 2.0 + growable - seal * 3.0 - rng.nextDouble() * 0.5;
    }

    private static boolean fits(int[] occupied, int originX, int originZ, int w, int h) {
        if (originX < 0 || originZ < 0 || originX + w > GRID || originZ + h > GRID) {
            return false;
        }
        for (int a = 0; a < w; a++) {
            for (int b = 0; b < h; b++) {
                if (occupied[(originZ + b) * GRID + originX + a] >= 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasStub(List<Stub> stubs, int cellX, int cellZ, int side) {
        for (Stub s : stubs) {
            if (s.cellX == cellX && s.cellZ == cellZ && s.side == side) {
                return true;
            }
        }
        return false;
    }

    private static boolean consume(List<Stub> stubs, int cellX, int cellZ, int side, int owner) {
        for (int i = 0; i < stubs.size(); i++) {
            Stub s = stubs.get(i);
            if (s.cellX == cellX && s.cellZ == cellZ && s.side == side && (owner < 0 || s.owner == owner)) {
                stubs.remove(i);
                return true;
            }
        }
        return false;
    }

    private static int commit(Candidate c, int rotationDegrees, int originX, int originZ, int depth,
                              int[] occupied, List<Placement> placed, List<Stub> stubs, Set<String> used) {
        int rotationIndex = ((rotationDegrees / 90) % 4 + 4) % 4;
        RoomDoors.Mask mask = c.byRotation()[rotationIndex];
        int idx = placed.size();
        for (int a = 0; a < mask.tilesX(); a++) {
            for (int b = 0; b < mask.tilesZ(); b++) {
                occupied[(originZ + b) * GRID + originX + a] = idx;
            }
        }
        placed.add(new Placement(c.name(), rotationIndex * 90, originX, originZ,
                mask.tilesX(), mask.tilesZ(), depth, c.type()));
        used.add(c.name());
        for (int[] door : RoomDoors.doorCells(mask, originX, originZ)) {
            stubs.add(new Stub(door[0], door[1], door[2], depth, idx));
        }
        return idx;
    }
}

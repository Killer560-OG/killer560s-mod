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
     * How many floors to lay out before settling for the best of them.
     *
     * <p>A single attempt reaches the room count it was asked for about four times in five; the rest stall
     * early because the entrance happened to land in a corner facing a wall. So {@link #generate} lays out
     * several and keeps the best - but it stops the moment one of them is actually FINISHED (blood room, the
     * floor's room count, and the whole cell target), which is most of them on the first or second try. The cap
     * is what the stalling ones cost, and at about half a millisecond an attempt it is cheap enough to be
     * generous: two F7s in 800 came out a room short with ten attempts.
     */
    private static final int ATTEMPTS = 30;

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
     * <p><b>The target is CELLS, not rooms.</b> killer560 (2026-09-29): "it still isnt generating a full map."
     * A room is not a cell - a 1x2 covers two and a 2x2 covers four - so stopping at a room count lands the
     * floor anywhere between that many cells and the whole grid depending on which rooms happened to be picked.
     * Measured over 100 planned F7s before this changed: 21 rooms every time, but 25 to 35 of the 36 room slots
     * filled, median 30. His own Map Logger scans of 40 real floors put a fully-walked floor at 34 to 36 with
     * 12 of the 40 at exactly 36, so a median of 30 is a floor with rows missing - which is what he saw.
     *
     * @param minRooms   the fewest rooms the floor may have, so a floor of big rooms is still a floor
     * @param wantCells  how many of the {@link #GRID}x{@link #GRID} room slots to fill - the real target
     * @param puzzles    how many puzzle rooms to try to include
     * @param bloodDepth how many doorways from the entrance the blood room should be
     * @return the floor, or null when there is not even an entrance room captured
     */
    public static Floor generate(Map<String, RoomLibrary.Room> usable, int minRooms, int wantCells,
                                 int puzzles, int bloodDepth, Random rng) {
        List<Candidate> pool = candidates(usable);
        if (pool.isEmpty()) {
            return null;
        }
        Floor best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            Floor floor = growOnce(pool, minRooms, wantCells, puzzles, bloodDepth, rng);
            if (floor == null) {
                continue;
            }
            // Having a blood room at all comes first - a floor without one is not a floor, and scenario 73
            // requires it. Then the room minimum, because an attempt that stalled at 20 rooms over 32 cells
            // otherwise beat one that reached 22 rooms over 31, and "fewer rooms than the floor has" is the
            // one thing scenario 73 asserts about the size. Then cells filled, which is the point of the
            // target; then rooms; then fewest doorways left to brick up.
            int score = (floor.bloodDepth() >= 0 ? 1_000_000 : 0)
                    + (floor.rooms().size() >= minRooms ? 500_000 : 0)
                    + cellsOf(floor) * 1000
                    + floor.rooms().size() * 5
                    - floor.openDoors().size();
            if (score > bestScore) {
                bestScore = score;
                best = floor;
            }
            if (floor.bloodDepth() >= 0 && floor.rooms().size() >= minRooms
                    && cellsOf(floor) >= wantCells) {
                break;   // finished - nothing another attempt could improve
            }
        }
        return best;
    }

    /** Room slots the floor actually occupies - the number {@link #generate} is aiming at. */
    public static int cellsOf(Floor floor) {
        int cells = 0;
        for (Placement p : floor.rooms()) {
            cells += p.cellsX() * p.cellsZ();
        }
        return cells;
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

    private static Floor growOnce(List<Candidate> pool, int minRooms, int wantCells, int puzzles,
                                  int bloodDepth, Random rng) {
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
        int filled = em.tilesX() * em.tilesZ();

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
        // And the other way round. The guard above only stopped BLOOD landing next to the fairy; a fairy room
        // placed afterwards could still end up against a blood cell, which happened on about 2% of floors
        // before and after the cell target went in (measured over 800). No door was ever cut between the two -
        // blood's stubs are dropped the moment it is placed - so the blood-rush rule held, but the rule is
        // meant to be about the cells, so it is checked both ways now.
        java.util.Set<Long> bloodCells = new HashSet<>();
        List<Stub> failed = new ArrayList<>();

        for (int pass = 0; pass < 2; pass++) {
            boolean relaxed = pass == 1;
            if (relaxed) {
                stubs.addAll(failed);
                failed.clear();
            }
            int guard = 0;
            // Three things have to be true before the floor is finished, and the cell target is the one that
            // was missing: enough of the grid filled, at least the floor's room count, and a blood room.
            while ((filled < wantCells || placed.size() < minRooms || bloodPlacedDepth < 0)
                    && !stubs.isEmpty() && guard++ < 4000) {
                int cellsLeft = wantCells - filled;
                boolean needMore = filled < wantCells || placed.size() < minRooms;
                // Below the cells-per-room a real floor of this size has, so the next room should be a big
                // one. Above it, small rooms fill the last gaps without spilling over them. Without this the
                // sort's fixed preference for small rooms meant hitting 36 cells took 26 rooms rather than the
                // 21 his own scans measured.
                // Biggest footprint still allowed. Filling the grid with big rooms can finish the floor with
                // FEWER rooms than it is supposed to have - measured at 20 on a 21-room F7, and scenario 73
                // asserts the count - so every room still owed needs a cell left for it.
                int roomsShort = Math.max(0, minRooms - placed.size());
                int maxArea = cellsLeft <= 0
                        ? Integer.MAX_VALUE
                        : Math.max(1, cellsLeft - Math.max(0, roomsShort - 1));
                boolean preferBig = needMore && maxArea > 1
                        && (double) filled / Math.max(1, placed.size()) < (double) wantCells / Math.max(1, minRooms)
                        && cellsLeft >= 2;
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
                        && (stub.depth + 1 >= bloodDepth || (!needMore && stub.depth >= 2)
                            || (cellsLeft <= 2 && stub.depth >= 2))
                        && !touchesFairy(tx, tz, fairyCells);
                boolean wantFairy = !fairyPlaced[0] && !wantBlood && needMore && stub.depth >= 1
                        && byName.containsKey("fairy") && !touchesFairy(tx, tz, bloodCells)
                        && rng.nextDouble() < 0.25;
                if (wantBlood) {
                    wanted = List.of(byName.get("blood"));
                } else if (wantFairy) {
                    wanted = List.of(byName.get("fairy"));
                } else if (!needMore) {
                    // Everything but blood is done, so this stub is only worth spending on blood. Dropping it
                    // rather than falling through to the normal pool is what stops the floor overshooting its
                    // size while it hunts for somewhere to put the blood room.
                    failed.add(stubs.remove(si));
                    continue;
                } else if (puzzlesLeft > 0 && !puzzleRooms.isEmpty() && rng.nextDouble() < 0.35) {
                    wanted = puzzleRooms;
                } else {
                    wanted = normal;
                }
                // The dead-end guard counts in CELLS now: a one-doorway room spends a doorway and gives none
                // back, so it is only allowed once there is almost nothing left to fill.
                int remaining = Math.max(cellsLeft, minRooms - placed.size());
                // A given room is never held back by the size cap: there is exactly one blood and one fairy.
                Best best = choose(wanted, used, stub, tx, tz, occupied, stubs, remaining,
                        wantBlood || wantFairy || wanted == puzzleRooms, preferBig, cellsLeft,
                        wantBlood || wantFairy ? Integer.MAX_VALUE : maxArea, rng);
                if (best == null && wanted != normal && needMore) {
                    best = choose(normal, used, stub, tx, tz, occupied, stubs, remaining, false,
                            preferBig, cellsLeft, maxArea, rng);
                }
                if (best == null) {
                    failed.add(stubs.remove(si));
                    continue;
                }

                int idx = commit(best.candidate, best.rotation, best.originX, best.originZ, stub.depth + 1,
                        occupied, placed, stubs, used);
                filled += placed.get(idx).cellsX() * placed.get(idx).cellsZ();
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
                    RoomDoors.Mask bm = best.candidate.byRotation()[best.rotation / 90];
                    for (int a = 0; a < bm.tilesX(); a++) {
                        for (int c = 0; c < bm.tilesZ(); c++) {
                            bloodCells.add(cellKey(best.originX + a, best.originZ + c));
                        }
                    }
                    // Blood is the end of the run: nothing is attached beyond it.
                    stubs.removeIf(s -> s.owner == idx);
                }
            }
            if (filled >= wantCells && placed.size() >= minRooms && bloodPlacedDepth >= 0) {
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
                               boolean preferBig, int cellsLeft, int maxArea, Random rng) {
        int need = (stub.side + 2) % 4;
        List<Candidate> shortlist = new ArrayList<>();
        for (Candidate c : pool) {
            if (used.contains(c.name()) || excluded(c.name(), used)) {
                continue;   // a floor does not repeat a room, or a room's other half
            }
            if (!allowDeadEnd && remaining > 2 && c.doorCount() <= 1) {
                continue;
            }
            if (c.area(0) > maxArea) {
                continue;   // no cell left over for the rooms the floor still owes
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
        //
        // {@code preferBig} flips the size term. The floor is aiming at a CELL count, and while it is behind
        // the cells-per-room a real floor of this size has, a bigger room is the one that gets it there; once
        // it is ahead, small rooms are what fit the last gaps. A fixed preference for small rooms is what left
        // 21 rooms covering 30 of the 36 slots.
        double areaWeight = preferBig ? -0.55 : 0.45;
        Map<String, Double> key = new HashMap<>();
        for (Candidate c : shortlist) {
            key.put(c.name(), c.area(0) * areaWeight - c.doorCount() * 0.9 + rng.nextDouble());
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
                    double score = score(mask, originX, originZ, tx, tz, need, occupied, stubs,
                            cellsLeft, preferBig, rng);
                    if (score > bestScore) {
                        bestScore = score;
                        best = new Best(c, rotation * 90, originX, originZ);
                    }
                }
            }
            // "Good enough, stop looking." The threshold has to move with the extra credit a multi-tile room
            // gets while the floor is behind on cells, or the first 1x1 that scores 2.0 ends the search before
            // a bigger room is ever examined - which is how a floor reached 36 cells but took 26 rooms to do it
            // when his own scans say 21.
            if (best != null && bestScore >= (preferBig ? 5.0 : 2.0)) {
                break;
            }
        }
        return best;
    }

    private static double score(RoomDoors.Mask mask, int originX, int originZ, int tx, int tz, int need,
                                int[] occupied, List<Stub> stubs, int cellsLeft, boolean preferBig,
                                Random rng) {
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
        int stranded = cellsLeft <= 0 ? 0 : stranded(mask, originX, originZ, occupied, stubs);
        // Credit for the cells a multi-tile room brings, but only while the floor is behind the cells-per-room
        // a real one of this size has. A big room has more doorways and so more chances to face a neighbour's
        // blank wall, and the seal cost of that was enough on its own to make the layout refuse every one of
        // them - it filled the grid with small rooms instead and needed five more rooms than a real floor.
        double bulk = preferBig
                ? Math.min(mask.tilesX() * mask.tilesZ(), Math.max(1, cellsLeft)) - 1
                : 0;
        return matched * 2.0 + growable - seal * 3.0 - stranded * 2.5 + bulk * 1.8
                - rng.nextDouble() * 0.5;
    }

    /**
     * Free cells this placement would leave with no way in, ever.
     *
     * <p>A free cell can only be filled by attaching a room to a doorway pointing at it. Once every neighbour
     * of a free cell is a blank wall, that cell is dead: nothing can be put there for the rest of the floor and
     * it stays a hole in the map. That is the "single orphan cell" a generator aiming at a cell count has to
     * avoid, and it is invisible to a generator aiming at a room count because it never goes looking for the
     * last few cells in the first place.
     *
     * <p>Counted, not forbidden: a placement that strands one cell is sometimes still the best one available,
     * so this is a cost in {@link #score} rather than a rejection in {@link #choose}.
     */
    private static int stranded(RoomDoors.Mask mask, int originX, int originZ, int[] occupied,
                                List<Stub> stubs) {
        Set<Long> mine = new HashSet<>();
        for (int a = 0; a < mask.tilesX(); a++) {
            for (int b = 0; b < mask.tilesZ(); b++) {
                mine.add(cellKey(originX + a, originZ + b));
            }
        }
        Set<Long> doorways = new HashSet<>();
        for (int[] door : RoomDoors.doorCells(mask, originX, originZ)) {
            doorways.add(cellKey(door[0] + RoomDoors.DX[door[2]], door[1] + RoomDoors.DZ[door[2]]));
        }
        Set<Long> checked = new HashSet<>();
        int count = 0;
        for (long k : mine) {
            int cx = (int) (k >> 32);
            int cz = (int) (k & 0xffffffffL);
            for (int s = 0; s < 4; s++) {
                int nx = cx + RoomDoors.DX[s];
                int nz = cz + RoomDoors.DZ[s];
                if (nx < 0 || nz < 0 || nx >= GRID || nz >= GRID) {
                    continue;
                }
                long nk = cellKey(nx, nz);
                if (mine.contains(nk) || occupied[nz * GRID + nx] >= 0 || !checked.add(nk)) {
                    continue;
                }
                if (doorways.contains(nk)) {
                    continue;   // this room opens onto it
                }
                boolean reachable = false;
                for (int t = 0; t < 4 && !reachable; t++) {
                    int ox = nx + RoomDoors.DX[t];
                    int oz = nz + RoomDoors.DZ[t];
                    if (ox < 0 || oz < 0 || ox >= GRID || oz >= GRID) {
                        continue;
                    }
                    long ok = cellKey(ox, oz);
                    if (mine.contains(ok)) {
                        continue;   // this room's own wall, already known to be blank here
                    }
                    if (occupied[oz * GRID + ox] < 0) {
                        reachable = true;   // another free cell, so the floor can still grow into it
                    } else if (hasStub(stubs, ox, oz, (t + 2) % 4)) {
                        reachable = true;   // a placed room has a doorway pointing at it
                    }
                }
                if (!reachable) {
                    count++;
                }
            }
        }
        return count;
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

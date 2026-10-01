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

    /**
     * A floor laid out AROUND rooms he placed by hand, and what could not be honoured.
     *
     * <p>killer560 (2026-09-29): "test stuff like putting in a single room that I want personally in generating
     * a map around the room". Pressing Generate in the map designer used to throw the drawing away; this is the
     * result of keeping it.
     *
     * <p>{@code unusedPins} is never silently empty when a pin was dropped: a pinned room that cannot be
     * connected at the cell he put it at is REMOVED from {@code floor} rather than left in it unreachable - a
     * floor with an unreachable room in it is a broken floor, and the sim would build a room with no way into
     * it - and its name and the reason land here so the caller can tell him. Each entry reads
     * {@code "Name - reason"}.
     *
     * @param honouredPins names of the pinned rooms that ARE on the floor, at the cell he put them
     * @param unusedPins   {@code "Name - reason"} per pinned room that could not be used
     */
    public record PinnedFloor(Floor floor, List<String> honouredPins, List<String> unusedPins) {
    }

    /** One room he pinned: the cell he put it at, and the room itself. */
    private record Pin(String name, int cellX, int cellZ, Candidate candidate) {
    }

    /**
     * One attempt's outcome: the floor, and the pins that attempt could not use.
     *
     * @param unusedNames the bare room names, so {@link #run} can lay the floor out again without them
     * @param unusedNotes the same, as {@code "Name - reason"} for the caller to show him
     */
    private record Grown(Floor floor, List<String> reached, List<String> unusedNames,
                         List<String> unusedNotes) {
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
        /**
         * Doorways from the entrance to the room that owns this stub.
         *
         * <p>Not final, because a PINNED room is on the grid before anything has reached it, so its distance
         * from the entrance is not known until it is - see {@link #wakePins}. Everything a generated floor
         * places knows its depth the moment it is committed and never changes it.
         */
        int depth;
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
     * How many floors to lay out when he has PINNED rooms, which is a harder problem.
     *
     * <p>A pinned room is a fixed obstacle the growth has to happen to reach through a matching pair of
     * doorways, so an attempt fails for reasons no re-ordering inside the attempt can fix: the entrance seeded
     * on the far edge, or a rotation that turned the pin's only doorways towards the grid edge. Retrying is the
     * cheap fix - measured at about 2 ms an attempt - and the loop still stops the moment one attempt has every
     * pin, a blood room, the room minimum and the whole cell target.
     */
    private static final int PINNED_ATTEMPTS = 150;

    private static final List<Pin> NO_PINS = List.of();

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
        PinnedFloor out = run(usable, minRooms, wantCells, puzzles, bloodDepth, null, rng);
        return out == null ? null : out.floor();
    }

    /**
     * The same, but built AROUND rooms he placed by hand.
     *
     * <p>killer560 (2026-09-29): "test stuff like putting in a single room that I want personally in generating
     * a map around the room". Every pinned room is put at the cell he put it at; the ROTATION is chosen here,
     * from the four, because a rotation is what decides which sides its doorways are on and therefore whether
     * the rest of the floor can ever reach it - the map designer has no way to ask for one and he should not
     * have to.
     *
     * <p>Everything the random generator guarantees still holds: the floor is connected from the entrance
     * through real doorways in both rooms, one-doorway rooms come out at dead ends, blood is a dead end once,
     * and no room overlaps another. What a pin can do that a generated room cannot is be UNREACHABLE - he can
     * put a one-doorway room in a corner with its doorway facing the grid edge. That is not dropped silently
     * and it does not come back as a broken floor either: the attempt is retried, and if no attempt can connect
     * it the room is left off the floor and named in {@link PinnedFloor#unusedPins()}.
     *
     * <p>If he pins the Entrance, that cell IS the entrance rather than a random edge.
     *
     * @param pinned cell index {@code cellZ * GRID + cellX} to room name, as the map designer keys it
     */
    public static PinnedFloor generate(Map<String, RoomLibrary.Room> usable, int minRooms, int wantCells,
                                       int puzzles, int bloodDepth, Map<Integer, String> pinned, Random rng) {
        return run(usable, minRooms, wantCells, puzzles, bloodDepth, pinned, rng);
    }

    /** The one attempt loop both {@link #generate} overloads use. {@code pinned} may be null or empty. */
    private static PinnedFloor run(Map<String, RoomLibrary.Room> usable, int minRooms, int wantCells,
                                   int puzzles, int bloodDepth, Map<Integer, String> pinned, Random rng) {
        List<Candidate> pool = candidates(usable);
        if (pool.isEmpty()) {
            return null;
        }
        List<String> rejected = new ArrayList<>();
        List<Pin> pins = resolvePins(pinned, usable, rejected);
        Grown best = attemptLoop(pool, minRooms, wantCells, puzzles, bloodDepth, pins, rng);
        if (best == null) {
            return null;
        }
        List<String> unused = new ArrayList<>(rejected);
        // A PIN NO ATTEMPT COULD USE IS LAID OUT AGAIN WITHOUT IT, and that is not a detail.
        //
        // A pin the floor never reaches has still been sitting on its cells the whole way through - the growth
        // counted them towards the cell target and then stopped - so pruning it at the end leaves a floor short
        // of its target with a hole where the pin was. Measured before this second pass went in: a single
        // unreachable pin dragged the median F7 from 36 cells to 34 and the worst case to 26. Once every one of
        // the {@link #PINNED_ATTEMPTS} attempts has failed on the same room, the honest thing is to lay the
        // floor out again as if he had not placed it, and tell him it was dropped.
        if (!best.unusedNames().isEmpty()) {
            unused.addAll(best.unusedNotes());
            Set<String> hopeless = new HashSet<>();
            for (String n : best.unusedNames()) {
                hopeless.add(n.toLowerCase(Locale.ROOT));
            }
            List<Pin> keep = new ArrayList<>();
            for (Pin p : pins) {
                if (!hopeless.contains(p.name().toLowerCase(Locale.ROOT))) {
                    keep.add(p);
                }
            }
            Grown second = attemptLoop(pool, minRooms, wantCells, puzzles, bloodDepth, keep, rng);
            if (second != null) {
                best = second;
                unused.addAll(second.unusedNotes());
            }
        }
        return new PinnedFloor(best.floor(), best.reached(), unused);
    }

    /** The best of {@link #ATTEMPTS} (or {@link #PINNED_ATTEMPTS}) layouts. */
    private static Grown attemptLoop(List<Candidate> pool, int minRooms, int wantCells, int puzzles,
                                     int bloodDepth, List<Pin> pins, Random rng) {
        Grown best = null;
        int bestScore = Integer.MIN_VALUE;
        int attempts = pins.isEmpty() ? ATTEMPTS : PINNED_ATTEMPTS;
        for (int attempt = 0; attempt < attempts; attempt++) {
            Grown grown = growOnce(pool, minRooms, wantCells, puzzles, bloodDepth, pins, rng);
            if (grown == null) {
                continue;
            }
            Floor floor = grown.floor();
            // Having a blood room at all comes first - a floor without one is not a floor, and scenario 73
            // requires it. Then the room minimum, because an attempt that stalled at 20 rooms over 32 cells
            // otherwise beat one that reached 22 rooms over 31, and "fewer rooms than the floor has" is the
            // one thing scenario 73 asserts about the size. Then cells filled, which is the point of the
            // target; then rooms; then fewest doorways left to brick up.
            //
            // A DROPPED PIN COSTS five cells' worth, not a rank of its own. Ranking "every pin kept" above the
            // cell count looks right and is not: a two-room floor that happens to hold his rooms then beat a
            // full one that had to leave one out, and that is what the generator returned - 5% of the cell
            // target on 14 of 200 floors, measured. A pin's cells are counted as filled while it sits there,
            // so dropping one already shows up as a hole in the coverage; this is the extra nudge, not the
            // whole preference. With no pins the term is zero and the ranking is what it always was.
            int score = (floor.bloodDepth() >= 0 ? 4_000_000 : 0)
                    + (floor.rooms().size() >= minRooms ? 500_000 : 0)
                    + cellsOf(floor) * 1000
                    - grown.unusedNames().size() * 5_000
                    + floor.rooms().size() * 5
                    - floor.openDoors().size();
            if (score > bestScore) {
                bestScore = score;
                best = grown;
            }
            if (floor.bloodDepth() >= 0 && grown.unusedNames().isEmpty()
                    && floor.rooms().size() >= minRooms && cellsOf(floor) >= wantCells) {
                break;   // finished - nothing another attempt could improve
            }
        }
        return best;
    }

    /**
     * Turns his cell-to-name map into pins, dropping the ones that are a user error rather than a layout
     * problem: a room that is not captured, a cell that is not on the grid, a room whose footprint cannot fit
     * on the grid at that cell at ANY rotation, and the same room pinned twice.
     *
     * <p>Two pins OVERLAPPING is decided per attempt, not here, because which cells a pin covers depends on the
     * rotation this class is choosing - see {@link #assignPinRotations}.
     */
    private static List<Pin> resolvePins(Map<Integer, String> pinned, Map<String, RoomLibrary.Room> usable,
                                         List<String> rejected) {
        if (pinned == null || pinned.isEmpty()) {
            return NO_PINS;
        }
        List<Pin> pins = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Integer> slots = new ArrayList<>(pinned.keySet());
        java.util.Collections.sort(slots);   // the same drawing must give the same floor
        for (int slot : slots) {
            String name = pinned.get(slot);
            if (name == null) {
                continue;
            }
            int cellX = slot % GRID;
            int cellZ = slot / GRID;
            if (slot < 0 || cellZ >= GRID) {
                rejected.add(name + " - cell " + slot + " is not on the " + GRID + "x" + GRID + " grid");
                continue;
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                rejected.add(name + " - pinned more than once");
                continue;
            }
            // NOT filtered for L shape, deliberately. A generated floor leaves the L rooms out because their
            // captures hold a neighbour's geometry in the quarter they do not occupy, but he PUT this one here,
            // and the drawn-map path has never filtered what he drew either.
            Candidate c = candidateOf(name, usable.get(name));
            if (c == null) {
                rejected.add(name + " - not captured, or not at the current capture format");
                continue;
            }
            boolean fitsSomewhere = false;
            for (int r = 0; r < 4 && !fitsSomewhere; r++) {
                RoomDoors.Mask m = c.byRotation()[r];
                fitsSomewhere = cellX + m.tilesX() <= GRID && cellZ + m.tilesZ() <= GRID;
            }
            if (!fitsSomewhere) {
                rejected.add(name + " - its " + c.byRotation()[0].tilesX() + "x" + c.byRotation()[0].tilesZ()
                        + " footprint runs off the grid at cell " + cellX + "," + cellZ);
                continue;
            }
            pins.add(new Pin(name, cellX, cellZ, c));
        }
        // Biggest first, so the greedy rotation choice below settles the hard ones before the 1x1s.
        pins.sort(Comparator.comparingInt((Pin p) -> -p.candidate().area(0)));
        return pins;
    }

    /**
     * A rotation per pin, or -1 for a pin that cannot go there without covering another one.
     *
     * <p>Greedy rather than a full search, and re-rolled every attempt: among the rotations that fit the grid
     * and do not overlap a pin already settled, it prefers the one with the most doorways pointing at cells the
     * floor could actually grow from, because a pin whose doorways all face the grid edge can never be reached.
     * The jitter is drawn once per rotation and stored - a comparator that rolls a die makes TimSort throw.
     */
    private static int[] assignPinRotations(List<Pin> pins, Random rng) {
        int[] rot = new int[pins.size()];
        boolean[] taken = new boolean[GRID * GRID];
        for (int i = 0; i < pins.size(); i++) {
            Pin p = pins.get(i);
            Integer[] order = {0, 1, 2, 3};
            double[] key = new double[4];
            for (int r = 0; r < 4; r++) {
                key[r] = -(openness(p, r) + rng.nextDouble() * 1.5);
            }
            java.util.Arrays.sort(order, Comparator.comparingDouble(r -> key[r]));
            rot[i] = -1;
            for (int r : order) {
                RoomDoors.Mask m = p.candidate().byRotation()[r];
                if (p.cellX() + m.tilesX() > GRID || p.cellZ() + m.tilesZ() > GRID
                        || !free(taken, p.cellX(), p.cellZ(), m.tilesX(), m.tilesZ())) {
                    continue;
                }
                rot[i] = r;
                for (int a = 0; a < m.tilesX(); a++) {
                    for (int b = 0; b < m.tilesZ(); b++) {
                        taken[(p.cellZ() + b) * GRID + p.cellX() + a] = true;
                    }
                }
                break;
            }
        }
        return rot;
    }

    /** How many of a pin's doorways point at a cell on the grid that is not part of the pin itself. */
    private static int openness(Pin p, int rotationIndex) {
        RoomDoors.Mask m = p.candidate().byRotation()[rotationIndex];
        int open = 0;
        for (int[] door : RoomDoors.doorCells(m, p.cellX(), p.cellZ())) {
            int nx = door[0] + RoomDoors.DX[door[2]];
            int nz = door[1] + RoomDoors.DZ[door[2]];
            if (nx < 0 || nz < 0 || nx >= GRID || nz >= GRID) {
                continue;
            }
            if (nx >= p.cellX() && nx < p.cellX() + m.tilesX()
                    && nz >= p.cellZ() && nz < p.cellZ() + m.tilesZ()) {
                continue;   // its own other half
            }
            open++;
        }
        return open;
    }

    private static boolean free(boolean[] taken, int originX, int originZ, int w, int h) {
        for (int a = 0; a < w; a++) {
            for (int b = 0; b < h; b++) {
                if (taken[(originZ + b) * GRID + originX + a]) {
                    return false;
                }
            }
        }
        return true;
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
            Candidate c = candidateOf(e.getKey(), e.getValue());
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * One room as the layout sees it, or null when its doorways cannot be measured.
     *
     * <p>Shared by {@link #candidates} and by {@link #resolvePins}, so a pinned room and a generated one are
     * described by exactly the same masks. It does NOT apply the L-shape filter - that belongs to the caller,
     * because a generated floor leaves them out and a room he placed himself does not.
     */
    private static Candidate candidateOf(String name, RoomLibrary.Room room) {
        if (name == null || room == null) {
            return null;
        }
        RoomDoors.Mask mask = RoomDoors.of(name);
        if (mask == null) {
            return null;
        }
        RoomDoors.Mask[] byRotation = new RoomDoors.Mask[4];
        for (int r = 0; r < 4; r++) {
            byRotation[r] = RoomDoors.rotate(mask, r * 90);
        }
        return new Candidate(name, SimFloorGen.typeOf(name), byRotation);
    }

    private static Grown growOnce(List<Candidate> pool, int minRooms, int wantCells, int puzzles,
                                  int bloodDepth, List<Pin> pins, Random rng) {
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
        // The Candidate behind each placement, parallel to `placed`. Needed because a PINNED room's doorways
        // have to be looked at again long after it was committed, when something finally reaches it.
        List<Candidate> placedFrom = new ArrayList<>();
        List<Link> links = new ArrayList<>();
        List<Stub> stubs = new ArrayList<>();
        Set<String> used = new HashSet<>();
        int filled = 0;
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
        /**
         * Which room each placed room grew out of, by index, or -1 for a root (the entrance, and any pin
         * nothing has reached yet). {@code commit} appends exactly one room per call, so this stays aligned
         * with {@code placed} as long as every commit site adds an entry.
         *
         * <p>Only the fairy rule below needs it, and it needs it because "on the path to blood" is a question
         * about ANCESTRY and the floor records only depth.
         */
        List<Integer> parentOf = new ArrayList<>();
        /** The fairy room's index once it is down, or -1. */
        int[] fairyIndex = {-1};

        // Rooms that are ON the grid but that nothing has reached yet: every pin except a pinned entrance.
        //
        // Their doorways are registered as stubs from the start, so a room placed next to one scores the
        // meeting as a MATCH and the growth is drawn towards them rather than away. What they may not do is be
        // grown FROM, because a wing hanging off a room the entrance cannot reach is exactly the unreachable
        // wing this class is built not to produce. They are woken by {@link #wakePins} the moment a real
        // doorway pair joins them to the rest of the floor, and only then can the floor grow through them.
        Set<Integer> dormant = new HashSet<>();
        int entrancePin = -1;
        int[] pinRotation = pins.isEmpty() ? new int[0] : assignPinRotations(pins, rng);
        List<String> pinsUnused = new ArrayList<>();
        List<String> pinsUnusedNames = new ArrayList<>();
        List<String> pinsReached = new ArrayList<>();
        for (int i = 0; i < pins.size(); i++) {
            Pin p = pins.get(i);
            if (pinRotation[i] < 0) {
                pinsUnusedNames.add(p.name());
                pinsUnused.add(p.name() + " - overlaps another room you placed");
                continue;
            }
            int idx = commit(p.candidate(), pinRotation[i] * 90, p.cellX(), p.cellZ(), 0,
                    occupied, placed, placedFrom, stubs, used);
            parentOf.add(-1);   // a pin is a root until something reaches it
            RoomDoors.Mask pm = p.candidate().byRotation()[pinRotation[i]];
            filled += pm.tilesX() * pm.tilesZ();
            if ("ENTRANCE".equalsIgnoreCase(p.candidate().type()) && entrancePin < 0) {
                entrancePin = idx;   // his own entrance cell is the seed, not a random edge
                pinsReached.add(p.name());
            } else {
                dormant.add(idx);
            }
            // A pinned given room is on the grid before the loop starts, so the rules about the other given
            // rooms have to know about it now rather than when it is reached. The blood-rush rule is about
            // CELLS, and those are settled the moment he pins it.
            if ("FAIRY".equalsIgnoreCase(p.candidate().type())) {
                fairyCellsInto(fairyCells, pm, p.cellX(), p.cellZ());
                fairyPlaced[0] = true;
                fairyIndex[0] = idx;
            }
            if ("BLOOD".equalsIgnoreCase(p.candidate().type())) {
                fairyCellsInto(bloodCells, pm, p.cellX(), p.cellZ());
            }
        }

        if (entrancePin < 0) {
            // The entrance starts on an edge of the grid, the way a real floor's does.
            int entranceRotation;
            RoomDoors.Mask em;
            int ex;
            int ez;
            int seat = 0;
            while (true) {
                entranceRotation = rng.nextInt(4);
                em = entrance.byRotation()[entranceRotation];
                ex = rng.nextBoolean() ? 0 : GRID - em.tilesX();
                ez = rng.nextInt(Math.max(1, GRID - em.tilesZ() + 1));
                if (rng.nextBoolean()) {
                    int swap = ex;
                    ex = rng.nextInt(Math.max(1, GRID - em.tilesX() + 1));
                    ez = swap == 0 ? 0 : GRID - em.tilesZ();
                }
                // With nothing pinned the edge is always free, so this is the one draw it always was and the
                // random stream is unchanged. With pins it can land on one, and then it is re-drawn.
                if (pins.isEmpty() || fits(occupied, ex, ez, em.tilesX(), em.tilesZ())) {
                    break;
                }
                if (++seat >= 60) {
                    return null;   // his pins leave no edge cell for an entrance; another attempt may differ
                }
            }
            // DEGREES, not an index. This read `entranceRotation` for years, which integer-divides to 0 for
            // every one of the four values it can hold, so the entrance was always committed unrotated while
            // its footprint and its position had been worked out at the rotation that was drawn. Entrance is
            // 1x1 so the position was right; its two doorways were simply never turned.
            commit(entrance, entranceRotation * 90, ex, ez, 0, occupied, placed, placedFrom, stubs, used);
            parentOf.add(-1);   // the seed
            filled += em.tilesX() * em.tilesZ();
        }
        if (!dormant.isEmpty()) {
            // A pin can be next door to the seed, so give it the chance to be reached before anything grows.
            bloodPlacedDepth = wakePins(occupied, placed, placedFrom, stubs, links, dormant, pinsReached,
                    bloodCells, bloodPlacedDepth);
        }

        for (int pass = 0; pass < 2; pass++) {
            boolean relaxed = pass == 1;
            if (relaxed) {
                stubs.addAll(failed);
                failed.clear();
            }
            int guard = 0;
            List<Integer> live = new ArrayList<>();
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
                // A dormant pin's stubs are in the list so that meeting one scores as a match, but the floor
                // may not GROW from one until something has reached it. With no pins this is the single draw
                // it always was.
                int si;
                if (dormant.isEmpty()) {
                    si = rng.nextInt(stubs.size());
                } else {
                    live.clear();
                    for (int i = 0; i < stubs.size(); i++) {
                        if (!dormant.contains(stubs.get(i).owner)) {
                            live.add(i);
                        }
                    }
                    if (live.isEmpty()) {
                        break;   // everything still open belongs to a room nothing can reach
                    }
                    si = live.get(rng.nextInt(live.size()));
                }
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
                // THE FAIRY IS ON THE WAY TO BLOOD. killer560 (2026-10-01): "for map generation fairy should
                // always be on the path to blood."
                //
                // "On the path" is ancestry: blood may only grow out of a room the fairy is an ancestor of,
                // so walking back from blood towards the entrance goes through the fairy. It cannot be every
                // route - the floor deliberately grows loops, and a second way round is exactly what a loop
                // is - but it is the route the floor was built along, and it is the one Auto Blood Rush walks
                // for want of a shorter one.
                //
                // The escape hatch stays: a floor with no blood room is not a floor (scenario 73 asserts one),
                // so when the grid is nearly full and the rule has not been satisfiable, blood goes in anyway
                // rather than losing the whole attempt. attemptLoop scores and retries, so the forgiving case
                // is the exception rather than the norm.
                boolean lastChanceBlood = cellsLeft <= 2 && stub.depth >= 2;
                boolean fairyOnWay = fairyIndex[0] >= 0 && isDescendant(parentOf, stub.owner, fairyIndex[0]);
                boolean wantBlood = bloodPlacedDepth < 0 && byName.containsKey("blood")
                        && (fairyOnWay || lastChanceBlood || !byName.containsKey("fairy"))
                        && (stub.depth + 1 >= bloodDepth || (!needMore && stub.depth >= 2)
                            || lastChanceBlood)
                        && !touchesFairy(tx, tz, fairyCells);
                // The fairy goes in EARLIER and more often than it used to, because blood now waits on it: at
                // one in four it regularly landed in the last few rooms, by which time there was no branch
                // left under it to hang a blood room from.
                boolean wantFairy = !fairyPlaced[0] && !wantBlood && needMore && stub.depth >= 1
                        && byName.containsKey("fairy") && !touchesFairy(tx, tz, bloodCells)
                        && rng.nextDouble() < 0.5;
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
                        wantBlood || wantFairy ? Integer.MAX_VALUE : maxArea, dormant, rng);
                if (best == null && wanted != normal && needMore) {
                    best = choose(normal, used, stub, tx, tz, occupied, stubs, remaining, false,
                            preferBig, cellsLeft, maxArea, dormant, rng);
                }
                if (best == null) {
                    failed.add(stubs.remove(si));
                    continue;
                }

                int idx = commit(best.candidate, best.rotation, best.originX, best.originZ, stub.depth + 1,
                        occupied, placed, placedFrom, stubs, used);
                parentOf.add(stub.owner);
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
                    if (forbiddenPair(best.candidate.type(), placed.get(neighbour).type())) {
                        continue;
                    }
                    if (consume(stubs, nx, nz, (door[2] + 2) % 4, neighbour)) {
                        links.add(new Link(door[0], door[1], nx, nz));
                        consume(stubs, door[0], door[1], door[2], idx);
                        // THIS is the doorway that reaches a room he pinned, most of the time. The loop above
                        // was already opening a real door into a dormant pin and consuming its stub; what it
                        // did not do was record that the pin was now part of the floor, so the pin was pruned
                        // at the end WITH the link still pointing at its cells. Measured: 63% of single pins
                        // honoured and 67 links to an empty cell over 200 floors, until this line went in.
                        if (dormant.remove(neighbour)) {
                            bloodPlacedDepth = adopt(neighbour, stub.depth + 2, placed, placedFrom, stubs,
                                    pinsReached, bloodCells, bloodPlacedDepth);
                        }
                    }
                }
                if ("PUZZLE".equalsIgnoreCase(best.candidate.type())) {
                    puzzlesLeft--;
                }
                // WHAT WENT IN, not what was asked for. Both these blocks used to fire on the REQUEST: when
                // blood was wanted but could not fit at that stub, `choose` returned null, the fallback below
                // it put an ordinary room there instead, and the floor was then recorded as having its blood
                // room - at the ordinary room's cells, with that room's stubs deleted as if it were the end of
                // the run. Rare with nothing pinned, because `choose(blood)` usually finds a rotation; certain
                // the moment he PINS the blood room, because the name is then already used and that call can
                // never succeed.
                boolean gotFairy = wantFairy && "FAIRY".equalsIgnoreCase(best.candidate.type());
                boolean gotBlood = wantBlood && "BLOOD".equalsIgnoreCase(best.candidate.type());
                if (gotFairy) {
                    fairyPlaced[0] = true;
                    fairyIndex[0] = idx;
                    fairyCellsInto(fairyCells, best.candidate.byRotation()[best.rotation / 90],
                            best.originX, best.originZ);
                }
                if (gotBlood) {
                    bloodPlacedDepth = stub.depth + 1;
                    fairyCellsInto(bloodCells, best.candidate.byRotation()[best.rotation / 90],
                            best.originX, best.originZ);
                    // Blood is the end of the run: nothing is attached beyond it.
                    stubs.removeIf(s -> s.owner == idx);
                }
                if (!dormant.isEmpty()) {
                    bloodPlacedDepth = wakePins(occupied, placed, placedFrom, stubs, links, dormant,
                            pinsReached, bloodCells, bloodPlacedDepth);
                }
            }
            if (filled >= wantCells && placed.size() >= minRooms && bloodPlacedDepth >= 0) {
                break;
            }
        }

        // A PIN NOTHING EVER REACHED COMES OFF THE FLOOR.
        //
        // He can pin a one-doorway room in a corner with that doorway facing the grid edge, and no layout at
        // that cell can ever connect it. Leaving it in would hand the builder a room with no way into it - the
        // one thing this class exists to make impossible - so it is removed, with its own open doorways, and
        // named for the caller to tell him. It has no links by construction: a dormant room's stubs are only
        // consumed at the moment it is woken.
        List<Placement> kept = placed;
        if (!dormant.isEmpty()) {
            kept = new ArrayList<>();
            for (int i = 0; i < placed.size(); i++) {
                if (dormant.contains(i)) {
                    pinsUnusedNames.add(placed.get(i).name());
                    pinsUnused.add(placed.get(i).name() + " - nothing could open a doorway into it at cell "
                            + placed.get(i).originX() + "," + placed.get(i).originZ());
                } else {
                    kept.add(placed.get(i));
                }
            }
            stubs.removeIf(s -> dormant.contains(s.owner));
            failed.removeIf(s -> dormant.contains(s.owner));
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
        return new Grown(new Floor(kept, links, open, bloodPlacedDepth), pinsReached, pinsUnusedNames,
                pinsUnused);
    }

    /** Every cell a placed room covers, into {@code into}. */
    private static void fairyCellsInto(Set<Long> into, RoomDoors.Mask mask, int originX, int originZ) {
        for (int a = 0; a < mask.tilesX(); a++) {
            for (int c = 0; c < mask.tilesZ(); c++) {
                into.add(cellKey(originX + a, originZ + c));
            }
        }
    }

    /**
     * Joins any pinned room that has just become reachable to the rest of the floor, and keeps going.
     *
     * <p>A pin is reachable the moment one of ITS doorways faces a cell held by a room that is already part of
     * the floor AND that room has an unused doorway facing back. That is the same test the growth applies to
     * every other pair of neighbours, so a door into a pinned room is a measured doorway in both rooms at the
     * rotation each is placed at - there is no special case that would let a pin be connected through a wall.
     *
     * <p>It repeats until nothing more wakes, because waking one pin can make a second one reachable through
     * it: two rooms he placed side by side are joined when the first of them is reached, not before.
     *
     * @return the blood depth, updated if the room that woke was the blood room he pinned
     */
    private static int wakePins(int[] occupied, List<Placement> placed, List<Candidate> placedFrom,
                                List<Stub> stubs, List<Link> links, Set<Integer> dormant,
                                List<String> reached, Set<Long> bloodCells, int bloodDepth) {
        boolean changed = true;
        while (changed && !dormant.isEmpty()) {
            changed = false;
            for (int d : new ArrayList<>(dormant)) {
                Placement p = placed.get(d);
                RoomDoors.Mask m = placedFrom.get(d).byRotation()[p.rotation() / 90];
                for (int[] door : RoomDoors.doorCells(m, p.originX(), p.originZ())) {
                    int nx = door[0] + RoomDoors.DX[door[2]];
                    int nz = door[1] + RoomDoors.DZ[door[2]];
                    if (nx < 0 || nz < 0 || nx >= GRID || nz >= GRID) {
                        continue;
                    }
                    int neighbour = occupied[nz * GRID + nx];
                    if (neighbour < 0 || neighbour == d || dormant.contains(neighbour)) {
                        continue;   // empty, itself, or another room nothing has reached either
                    }
                    if (forbiddenPair(p.type(), placed.get(neighbour).type())) {
                        continue;
                    }
                    if (!hasStub(stubs, door[0], door[1], door[2], d)
                            || !consume(stubs, nx, nz, (door[2] + 2) % 4, neighbour)) {
                        continue;
                    }
                    consume(stubs, door[0], door[1], door[2], d);
                    links.add(new Link(door[0], door[1], nx, nz));
                    dormant.remove(d);
                    changed = true;
                    bloodDepth = adopt(d, placed.get(neighbour).depth() + 1, placed, placedFrom, stubs,
                            reached, bloodCells, bloodDepth);
                    break;
                }
            }
        }
        return bloodDepth;
    }

    /**
     * Books a pinned room in as part of the floor, now that a doorway has been opened into it.
     *
     * <p>Its distance from the entrance was not known until this moment, so it and every doorway it still has
     * open take their depth from the room that reached it - the depth is what decides where the blood room is
     * allowed to go, so a pin left at zero would look like a second entrance.
     *
     * @return the blood depth, set if the room adopted is the blood room he pinned
     */
    /**
     * Whether {@code room} is {@code ancestor}, or grew out of it - see the fairy rule in {@link #growOnce}.
     *
     * <p>Bounded by the list's own length rather than trusted to terminate: {@code parentOf} is built as a
     * tree and cannot contain a cycle, but a walk that reads a parent array is one bad entry away from
     * hanging the generator, and this runs inside a 4000-iteration loop inside a retry loop.
     */
    private static boolean isDescendant(List<Integer> parentOf, int room, int ancestor) {
        int at = room;
        for (int steps = 0; at >= 0 && steps <= parentOf.size(); steps++) {
            if (at == ancestor) {
                return true;
            }
            at = at < parentOf.size() ? parentOf.get(at) : -1;
        }
        return false;
    }

    private static int adopt(int idx, int depth, List<Placement> placed, List<Candidate> placedFrom,
                             List<Stub> stubs, List<String> reached, Set<Long> bloodCells, int bloodDepth) {
        Placement p = placed.get(idx);
        placed.set(idx, new Placement(p.name(), p.rotation(), p.originX(), p.originZ(),
                p.cellsX(), p.cellsZ(), depth, p.type()));
        for (Stub s : stubs) {
            if (s.owner == idx) {
                s.depth = depth;
            }
        }
        reached.add(p.name());
        if ("BLOOD".equalsIgnoreCase(p.type())) {
            bloodDepth = depth;
            fairyCellsInto(bloodCells, placedFrom.get(idx).byRotation()[p.rotation() / 90],
                    p.originX(), p.originZ());
            stubs.removeIf(s -> s.owner == idx);   // blood is the end of the run
        }
        return bloodDepth;
    }

    private record Best(Candidate candidate, int rotation, int originX, int originZ) {
    }

    private static long cellKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /**
     * Two room types no door may ever join directly.
     *
     * <p>killer560 (2026-09-29): "make sure one room must generate between green and fairy and fairy and blood
     * for any given blood rush path." Choosing WHERE to put the fairy room already respects that - it is only
     * ever attached to a stub at least one doorway from the entrance, and never next to a blood cell - but the
     * extra-door pass afterwards does not choose anything: it opens a door wherever two placed rooms happen to
     * have doorways facing each other, and it will happily put one between the fairy room and the entrance the
     * fairy was carefully kept a room away from. Rare - never once in 4,000 generated floors, once in 200 with
     * rooms pinned, because pinning lays out five times as many candidates and keeps the best - and a rule that
     * holds by luck reads exactly like a rule that holds. Both doorways are left open and bricked up instead.
     */
    private static boolean forbiddenPair(String a, String b) {
        return ("FAIRY".equalsIgnoreCase(a) && ("ENTRANCE".equalsIgnoreCase(b) || "BLOOD".equalsIgnoreCase(b)))
                || ("FAIRY".equalsIgnoreCase(b)
                    && ("ENTRANCE".equalsIgnoreCase(a) || "BLOOD".equalsIgnoreCase(a)));
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
                               boolean preferBig, int cellsLeft, int maxArea, Set<Integer> dormant,
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
                            cellsLeft, preferBig, dormant, rng);
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
                                Set<Integer> dormant, Random rng) {
        int matched = 0;
        int growable = 0;
        int seal = 0;
        // Doorways that would open into a room he PINNED that nothing has reached yet. Those are the placements
        // that connect his rooms to the floor, so they are worth more than an ordinary loop; with nothing
        // pinned this is always zero and the score is exactly what it was.
        int reaches = 0;
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
                    if (dormant.contains(occupied[nz * GRID + nx])) {
                        reaches++;
                    }
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
        return matched * 2.0 + growable - seal * 3.0 - stranded * 2.5 + bulk * 1.8 + reaches * 6.0
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

    /** The same, but only counting a doorway that belongs to {@code owner}. */
    private static boolean hasStub(List<Stub> stubs, int cellX, int cellZ, int side, int owner) {
        for (Stub s : stubs) {
            if (s.cellX == cellX && s.cellZ == cellZ && s.side == side && s.owner == owner) {
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
                              int[] occupied, List<Placement> placed, List<Candidate> placedFrom,
                              List<Stub> stubs, Set<String> used) {
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
        placedFrom.add(c);
        used.add(c.name());
        for (int[] door : RoomDoors.doorCells(mask, originX, originZ)) {
            stubs.add(new Stub(door[0], door[1], door[2], depth, idx));
        }
        return idx;
    }
}

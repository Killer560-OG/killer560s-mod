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

    private static final org.slf4j.Logger LOGGER =
            com.killer560.hub.util.ModLog.get("killer560smod-roomsim");

    /** The room grid inside {@link com.killer560.hub.livemap.DungeonLayout}'s 11x11. */
    public static final int GRID = (com.killer560.hub.livemap.DungeonLayout.GRID + 1) / 2;

    /** One room, placed. {@code rotation} is degrees clockwise, as {@link RoomPlacer#paste} takes it. */
    public record Placement(String name, int rotation, int originX, int originZ,
                            int cellsX, int cellsZ, int depth, String type) {
    }

    /** A doorway between two room cells. */
    public record Link(int aX, int aZ, int bX, int bZ) {
    }

    /**
     * A laid-out floor. {@code openDoors} are doorways with nothing on the other side.
     *
     * @param spine the links of the Entrance-to-Blood path, in order from the entrance - see {@link #doorLinks}.
     *              Empty when the floor was not laid out along a planned path.
     */
    public record Floor(List<Placement> rooms, List<Link> links, List<int[]> openDoors, int bloodDepth,
                        List<Link> spine) {
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
     * @param puzzles    how many puzzle rooms the floor gets (the "Puzzles" slider; see {@link #ensurePuzzles})
     * @param roomsToBlood the "Rooms to blood" slider: rooms on the Entrance-to-Blood path NOT counting the
     *                     Entrance, the Fairy or Blood. The path is exactly Entrance, these rooms with the Fairy
     *                     among them, Blood - see {@link #planSpine}
     * @return the floor, or null when there is not even an entrance room captured
     */
    public static Floor generate(Map<String, RoomLibrary.Room> usable, int minRooms, int wantCells,
                                 int puzzles, int roomsToBlood, Random rng) {
        PinnedFloor out = run(usable, minRooms, wantCells, puzzles, roomsToBlood, null, rng);
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
                                       int puzzles, int roomsToBlood, Map<Integer, String> pinned, Random rng) {
        return run(usable, minRooms, wantCells, puzzles, roomsToBlood, pinned, rng);
    }

    /** The one attempt loop both {@link #generate} overloads use. {@code pinned} may be null or empty. */
    private static PinnedFloor run(Map<String, RoomLibrary.Room> usable, int minRooms, int wantCells,
                                   int puzzles, int roomsToBlood, Map<Integer, String> pinned, Random rng) {
        List<Candidate> pool = candidates(usable);
        if (pool.isEmpty()) {
            return null;
        }
        List<String> rejected = new ArrayList<>();
        List<Pin> pins = resolvePins(pinned, usable, rejected);
        Grown best = attemptLoop(pool, minRooms, wantCells, puzzles, roomsToBlood, pins, rng);
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
            Grown second = attemptLoop(pool, minRooms, wantCells, puzzles, roomsToBlood, keep, rng);
            if (second != null) {
                best = second;
                unused.addAll(second.unusedNotes());
            }
        }
        Floor floor = ensureTrap(best.floor(), pool, best.reached(), rng);
        floor = fillGaps(floor, pool, wantCells, minRooms, puzzles, best.reached(), rng);
        floor = ensureBlood(floor, pool, best.reached());
        floor = ensurePuzzles(floor, pool, puzzles, best.reached(), rng);
        if (!hasTrap(floor)) {
            // ensureTrap found no ordinary 1x1 to swap: put one in an empty cell instead, past the cell target if
            // need be (an Entrance floor whose long path is all big rooms reaches its 19 cells with no 1x1 left).
            Filler f = new Filler(floor, pool, 0, best.reached(), rng);
            f.only = c -> isTrap(c.name(), c.type());
            if (f.strictOnce() || f.carveOnce() || f.carveOnce(false)) {
                LOGGER.info("Sim floor: the trap went into an empty cell");
                floor = new Floor(f.rooms, f.links, f.open, floor.bloodDepth(), floor.spine());
            } else {
                LOGGER.warn("Sim floor: no trap room could be fitted anywhere on this floor");
            }
        }
        remember(floor);
        return new PinnedFloor(floor, best.reached(), unused);
    }

    /**
     * Whether a room is one of the two trap rooms.
     *
     * <p>The room database's {@code TRAP} type is the authority; the two names are the fallback for the window
     * before the database has loaded, when {@link SimFloorGen#typeOf} answers NORMAL for everything. Arrow Trap
     * is deliberately NOT one - it is an ordinary room with "trap" in its name, and killer560 asked for "new or
     * old trap".
     */
    static boolean isTrap(String name, String type) {
        if (type != null && type.equalsIgnoreCase("TRAP")) {
            return true;
        }
        return name != null && (name.equalsIgnoreCase("Old Trap") || name.equalsIgnoreCase("New Trap"));
    }

    static boolean hasTrap(Floor floor) {
        for (Placement p : floor.rooms()) {
            if (isTrap(p.name(), p.type())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The last resort for "every floor has a trap": swap one in for an ordinary 1x1 room the floor already has.
     *
     * <p>The growth asks for a trap and the attempt scoring prefers floors that got one, so this almost never
     * runs. When it does, it only replaces a room it can replace WITHOUT touching the rest of the floor: a 1x1
     * ordinary room nobody pinned, and a trap rotation whose doorways cover every link that room already has,
     * so every door on the floor is still a measured doorway in both rooms. Any extra doorway the trap brings
     * is added to {@code openDoors}, which the builder bricks up exactly as it does any other.
     */
    private static Floor ensureTrap(Floor floor, List<Candidate> pool, List<String> pinnedNames, Random rng) {
        List<Candidate> traps = new ArrayList<>();
        for (Candidate c : pool) {
            if (isTrap(c.name(), c.type()) && c.area(0) == 1) {
                traps.add(c);
            }
        }
        if (traps.isEmpty() || hasTrap(floor)) {
            return floor;
        }
        java.util.Collections.shuffle(traps, rng);
        Set<String> pinnedLower = new HashSet<>();
        for (String n : pinnedNames) {
            pinnedLower.add(n.toLowerCase(Locale.ROOT));
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < floor.rooms().size(); i++) {
            order.add(i);
        }
        java.util.Collections.shuffle(order, rng);
        Set<Long> onPath = spineCells(floor);
        // A room off the path to blood first; only if none can take the trap, a room on it (a trap counts as
        // one of the slider's rooms, so the path's length is unchanged) - measured with tools/layoutsim, that
        // second pass is what an Entrance floor with a long path needs.
        for (int pass = 0; pass < 2; pass++) {
            for (int i : order) {
                Placement p = floor.rooms().get(i);
                if (p.cellsX() != 1 || p.cellsZ() != 1 || !"NORMAL".equalsIgnoreCase(p.type())
                        || pinnedLower.contains(p.name().toLowerCase(Locale.ROOT))
                        || (pass == 0) == onPath.contains(cellKey(p.originX(), p.originZ()))) {
                    continue;
                }
                Set<Integer> linked = new HashSet<>();
                for (Link l : floor.links()) {
                    if (l.aX() == p.originX() && l.aZ() == p.originZ()) {
                        linked.add(sideTowards(l.bX() - l.aX(), l.bZ() - l.aZ()));
                    } else if (l.bX() == p.originX() && l.bZ() == p.originZ()) {
                        linked.add(sideTowards(l.aX() - l.bX(), l.aZ() - l.bZ()));
                    }
                }
                for (Candidate trap : traps) {
                    for (int r = 0; r < 4; r++) {
                        RoomDoors.Mask m = trap.byRotation()[r];
                        Set<Integer> sides = new HashSet<>();
                        for (int[] door : RoomDoors.doorCells(m, p.originX(), p.originZ())) {
                            sides.add(door[2]);
                        }
                        if (!sides.containsAll(linked)) {
                            continue;
                        }
                        List<Placement> rooms = new ArrayList<>(floor.rooms());
                        rooms.set(i, new Placement(trap.name(), r * 90, p.originX(), p.originZ(), 1, 1,
                                p.depth(), trap.type()));
                        List<int[]> open = new ArrayList<>();
                        for (int[] o : floor.openDoors()) {
                            if (o[0] != p.originX() || o[1] != p.originZ()) {
                                open.add(o);
                            }
                        }
                        for (int side : sides) {
                            if (!linked.contains(side)) {
                                open.add(new int[]{p.originX(), p.originZ(), side});
                            }
                        }
                        LOGGER.info("Sim floor: no attempt placed a trap, so {} replaces {} at cell {},{}",
                                trap.name(), p.name(), p.originX(), p.originZ());
                        return new Floor(rooms, floor.links(), open, floor.bloodDepth(), floor.spine());
                    }
                }
            }
        }
        LOGGER.info("Sim floor: no ordinary 1x1 room could be swapped for a trap; trying an empty cell later");
        return floor;
    }

    /**
     * The last pass: puts a room into every cell the growth left empty, until the floor reaches its cell target.
     *
     * <p>killer560 (2026-10-04): "I just generated a map that has one of the spots blank" and "the more maps I
     * generate the more it seems to not put rooms in." The growth is greedy and can strand a cell - every
     * neighbour shows it a blank wall - or run out of stubs while a cell is still free. Measured offline with
     * {@code tools/layoutsim} over his own 106-room library: 15% of F7s had a hole with no recency at all, and
     * 63% with his saved recency and the 2026-10-04 weights. Three steps, repeated until nothing changes:
     *
     * <ol>
     *   <li><b>Strict.</b> An unused room (least recent first, ordinary before puzzle, never a given room or a
     *       second trap) at any rotation and position covering the cell, with a doorway meeting an open doorway
     *       of a room already on the floor - the both-sides rule the growth uses.</li>
     *   <li><b>Rewire a neighbour.</b> A 1x1 ordinary neighbour nobody pinned is turned, or swapped for an unused
     *       1x1, so that its doorways still cover every door it already has AND one faces the empty cell - the
     *       same test {@link #ensureTrap} uses - and step 1 then fills the cell. Every door stays a measured
     *       doorway in both rooms.</li>
     *   <li><b>Carve.</b> Only when neither works: a 1x1 whose own doorway faces a neighbour, with the door cut
     *       through the neighbour's wall (the carve lays its own floor). Never into blood; into the green room
     *       only when nothing else borders the cell.</li>
     * </ol>
     *
     * <p>One WARN per floor that needed it, and a second if a cell is somehow still empty.
     */
    private static Floor fillGaps(Floor floor, List<Candidate> pool, int wantCells, int minRooms, int puzzles,
                                  List<String> pinnedNames,
                                  Random rng) {
        int target = Math.min(wantCells, GRID * GRID);
        int before = cellsOf(floor);
        if (before >= target && floor.rooms().size() >= minRooms) {
            return floor;
        }
        Filler f = new Filler(floor, pool, puzzles, pinnedNames, rng);
        int strict = 0;
        int rewired = 0;
        int carved = 0;
        // Past the cell target too while the floor is short of its room count: too many big rooms can cover the
        // target with a room or two fewer than the floor has, and the grid usually has a cell spare.
        int guard = 0;
        while ((f.filled < target || f.rooms.size() < minRooms) && guard++ < 200) {
            if (f.strictOnce()) {
                strict++;
            } else if (f.rewireOnce()) {
                rewired++;
            } else if (f.carveOnce()) {
                carved++;
            } else if (f.rewireOnce(true)) {
                rewired++;
            } else if (f.carveOnce(false)) {
                carved++;
            } else {
                break;
            }
        }
        LOGGER.warn("Sim floor: the growth left {} cell(s) empty and {} room(s) short; the fill pass put {} room(s)"
                        + " in ({} after turning or swapping a neighbour, {} through a carved wall){}",
                Math.max(0, target - before), Math.max(0, minRooms - floor.rooms().size()),
                strict + carved, rewired, carved, carved > 0 ? ": " + String.join(", ", f.carvedNotes) : "");
        if (f.filled < target) {
            LOGGER.warn("Sim floor: {} of the {} cell(s) this floor should cover are still EMPTY after the fill "
                    + "pass - no unused room fits them", target - f.filled, target);
        }
        return new Floor(f.rooms, f.links, f.open, floor.bloodDepth(), floor.spine());
    }

    /** The mutable floor {@link #fillGaps} works on. */
    private static final class Filler {
        final List<Placement> rooms;
        final List<Link> links;
        final List<int[]> open = new ArrayList<>();
        final int[] occupied = new int[GRID * GRID];
        final Set<String> used = new HashSet<>();
        final Set<String> pinned = new HashSet<>();
        final Map<String, Candidate> byName = new HashMap<>();
        final List<Candidate> order = new ArrayList<>();
        final List<String> carvedNotes = new ArrayList<>();
        boolean trapOnFloor;
        /** When set, only rooms it accepts may go in - the last steps for puzzles and the trap, past the target. */
        java.util.function.Predicate<Candidate> only;
        int puzzlesOwed;
        int filled;

        Filler(Floor floor, List<Candidate> pool, int puzzles, List<String> pinnedNames, Random rng) {
            puzzlesOwed = puzzles;
            rooms = new ArrayList<>(floor.rooms());
            links = new ArrayList<>(floor.links());
            for (int[] o : floor.openDoors()) {
                open.add(o.clone());
            }
            java.util.Arrays.fill(occupied, -1);
            for (int i = 0; i < rooms.size(); i++) {
                Placement p = rooms.get(i);
                used.add(p.name());
                mark(p, i);
            }
            for (String n : pinnedNames) {
                pinned.add(n.toLowerCase(Locale.ROOT));
            }
            trapOnFloor = hasTrap(floor);
            for (Placement p : floor.rooms()) {
                puzzlesOwed -= "PUZZLE".equalsIgnoreCase(p.type()) ? 1 : 0;
            }
            filled = cellsOf(floor);
            // Least recent first, ordinary rooms before puzzles and the trap. The jitter is drawn once and
            // stored (a comparator must not call the RNG). Recency only ORDERS this list - every room is tried.
            Map<String, Double> key = new HashMap<>();
            for (Candidate c : pool) {
                byName.put(c.name(), c);
                String t = c.type().toUpperCase(Locale.ROOT);
                if (t.equals("BLOOD") || t.equals("ENTRANCE") || t.equals("FAIRY")) {
                    continue;
                }
                order.add(c);
                // A puzzle still owed goes FIRST: a hole the growth left is usually a dead end, which is
                // exactly what a puzzle is. Once none is owed, puzzles are not used at all (see available).
                key.put(c.name(), recency(c.name()) + rng.nextDouble() + (t.equals("PUZZLE") ? -100 : 0)
                        + (isTrap(c.name(), c.type()) ? 50 : 0));
            }
            order.sort(Comparator.comparingDouble(c -> key.get(c.name())));
        }

        private void mark(Placement p, int idx) {
            for (int a = 0; a < p.cellsX(); a++) {
                for (int b = 0; b < p.cellsZ(); b++) {
                    occupied[(p.originZ() + b) * GRID + p.originX() + a] = idx;
                }
            }
        }

        private boolean available(Candidate c) {
            return !used.contains(c.name()) && !excluded(c.name(), used)
                    && !(puzzlesOwed <= 0 && "PUZZLE".equalsIgnoreCase(c.type()))
                    && (only == null || only.test(c))
                    && !(trapOnFloor && isTrap(c.name(), c.type()));
        }

        private Placement at(int x, int z) {
            if (x < 0 || z < 0 || x >= GRID || z >= GRID || occupied[z * GRID + x] < 0) {
                return null;
            }
            return rooms.get(occupied[z * GRID + x]);
        }

        /** Step 1, for the first empty cell that takes a room. */
        boolean strictOnce() {
            for (int cell = 0; cell < GRID * GRID; cell++) {
                if (occupied[cell] >= 0) {
                    continue;
                }
                int cx = cell % GRID;
                int cz = cell / GRID;
                for (Candidate c : order) {
                    if (!available(c)) {
                        continue;
                    }
                    for (int r = 0; r < 4; r++) {
                        RoomDoors.Mask m = c.byRotation()[r];
                        for (int ox = cx - m.tilesX() + 1; ox <= cx; ox++) {
                            for (int oz = cz - m.tilesZ() + 1; oz <= cz; oz++) {
                                if (fits(occupied, ox, oz, m.tilesX(), m.tilesZ())
                                        && meets(m, ox, oz, c.type(), occupied, rooms, open) > 0) {
                                    commitFill(c, r, ox, oz, -1);
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
            return false;
        }

        /** Step 2: give a 1x1 neighbour of an empty cell a doorway facing it, keeping all its doors. */
        boolean rewireOnce() {
            return rewireOnce(false);
        }

        /** @param puzzles whether a puzzle neighbour may be swapped out too - the last step, after the carve */
        boolean rewireOnce(boolean puzzles) {
            for (int cell = 0; cell < GRID * GRID; cell++) {
                if (occupied[cell] >= 0) {
                    continue;
                }
                int cx = cell % GRID;
                int cz = cell / GRID;
                for (int side = 0; side < 4; side++) {
                    int nx = cx + RoomDoors.DX[side];
                    int nz = cz + RoomDoors.DZ[side];
                    Placement nb = at(nx, nz);
                    // A PUZZLE neighbour too: it cannot take a second door (isOneDoor), so it is swapped for an
                    // ordinary room that can, and owed again - the fill pass or ensurePuzzles puts it in a dead end.
                    // Without this, an empty cell whose only neighbours were puzzles stayed empty: 2% of floors
                    // (F5/F7 sizes) once puzzles stopped taking extra doors.
                    boolean puzzleNb = puzzles && nb != null && isOneDoor(nb.type());
                    if (nb == null || nb.cellsX() != 1 || nb.cellsZ() != 1
                            || !("NORMAL".equalsIgnoreCase(nb.type()) || puzzleNb)
                            || pinned.contains(nb.name().toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    int idx = occupied[nz * GRID + nx];
                    Set<Integer> linked = new HashSet<>();
                    for (Link l : links) {
                        if (l.aX() == nx && l.aZ() == nz) {
                            linked.add(sideTowards(l.bX() - l.aX(), l.bZ() - l.aZ()));
                        } else if (l.bX() == nx && l.bZ() == nz) {
                            linked.add(sideTowards(l.aX() - l.bX(), l.aZ() - l.bZ()));
                        }
                    }
                    Set<Integer> need = new HashSet<>(linked);
                    need.add((side + 2) % 4);
                    // Its own room at another turn first, then any unused 1x1 ordinary room.
                    List<Candidate> tries = new ArrayList<>();
                    Candidate self = byName.get(nb.name());
                    if (self != null && !puzzleNb) {
                        tries.add(self);
                    }
                    for (Candidate c : order) {
                        if (c.area(0) == 1 && "NORMAL".equalsIgnoreCase(c.type()) && available(c)) {
                            tries.add(c);
                        }
                    }
                    for (Candidate c : tries) {
                        for (int r = 0; r < 4; r++) {
                            Set<Integer> sides = new HashSet<>();
                            for (int[] door : RoomDoors.doorCells(c.byRotation()[r], nx, nz)) {
                                sides.add(door[2]);
                            }
                            if (!sides.containsAll(need)) {
                                continue;
                            }
                            open.removeIf(o -> o[0] == nx && o[1] == nz);
                            for (int s : sides) {
                                if (!linked.contains(s)) {
                                    open.add(new int[]{nx, nz, s});
                                }
                            }
                            used.remove(nb.name());
                            used.add(c.name());
                            rooms.set(idx, new Placement(c.name(), r * 90, nx, nz, 1, 1, nb.depth(), c.type()));
                            if (puzzleNb) {
                                puzzlesOwed++;
                            }
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /**
         * Step 3: a 1x1 whose own doorway faces a neighbour, the door carved through that neighbour's wall - only
         * where the cut lands on floor he can walk (RoomDoors.carvable). {@link #carveOnce(boolean)} with false is
         * the last resort that carves anyway, so the cell, trap and puzzle guarantees hold as they did.
         */
        boolean carveOnce() {
            return carveOnce(true);
        }

        /** How many doors were carved without a walkable floor behind them (the unchecked last resort). */
        int carvedBlind;

        boolean carveOnce(boolean checked) {
            for (int cell = 0; cell < GRID * GRID; cell++) {
                if (occupied[cell] >= 0) {
                    continue;
                }
                int cx = cell % GRID;
                int cz = cell / GRID;
                for (int tryNo = 0; tryNo < 8; tryNo++) {
                    int side = tryNo % 4;
                    Placement nb = at(cx + RoomDoors.DX[side], cz + RoomDoors.DZ[side]);
                    if (nb == null || "BLOOD".equalsIgnoreCase(nb.type()) || isOneDoor(nb.type())
                            || (tryNo < 4 && "ENTRANCE".equalsIgnoreCase(nb.type()))) {
                        continue;   // blood and a puzzle have exactly one way in; the green room only as a last resort
                    }
                    if (checked && !carvableFrom(nb, cx, cz, side)) {
                        continue;   // the cut would open onto a wall, a platform or a drop - RoomDoors.carvable
                    }
                    for (Candidate c : order) {
                        if (c.area(0) != 1 || !available(c) || forbiddenPair(c.type(), nb.type())) {
                            continue;
                        }
                        for (int r = 0; r < 4; r++) {
                            if (c.byRotation()[r].edges().contains(RoomDoors.edge(side, 0))) {
                                commitFill(c, r, cx, cz, side);
                                carvedNotes.add(c.name() + " at " + cx + "," + cz + " through " + nb.name()
                                        + (checked ? "" : " (NOT a walkable cut - nothing else fitted)"));
                                carvedBlind += checked ? 0 : 1;
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        }

        /**
         * Whether a door carved from the empty cell {@code (cx, cz)} into its neighbour {@code nb} on {@code side}
         * lands somewhere he can walk in and back out of: {@link RoomDoors#carvable} at nb's placed rotation, on
         * the wall facing the empty cell. A room it cannot measure is refused.
         */
        private boolean carvableFrom(Placement nb, int cx, int cz, int side) {
            RoomDoors.Mask m = RoomDoors.carvable(nb.name());
            if (m == null) {
                return false;
            }
            m = RoomDoors.rotate(m, nb.rotation());
            int wall = (side + 2) % 4;
            int nx = cx + RoomDoors.DX[side];
            int nz = cz + RoomDoors.DZ[side];
            int index = wall == RoomDoors.NORTH || wall == RoomDoors.SOUTH ? nx - nb.originX() : nz - nb.originZ();
            return m.has(wall, index);
        }

        private void commitFill(Candidate c, int r, int ox, int oz, int forcedSide) {
            place(c, r, ox, oz, occupied, rooms, links, open, forcedSide);
            used.add(c.name());
            trapOnFloor |= isTrap(c.name(), c.type());
            puzzlesOwed -= "PUZZLE".equalsIgnoreCase(c.type()) ? 1 : 0;
            filled += c.area(r);
        }
    }

    /**
     * The last resort for "every floor has a blood room": the deepest ordinary 1x1 dead end becomes blood.
     *
     * <p>When none of the attempts managed blood, {@link #attemptLoop} keeps the best bloodless one - measured
     * with {@code tools/layoutsim}, 1.4% of F7s with no recency and 2.5% with it, every one otherwise a full
     * floor. Blood is one doorway and rotates freely, so any 1x1 room with exactly one door on the floor can be
     * swapped for it at the rotation that faces that door; its other doorways are bricked up. Not next to the
     * fairy room, and never a room he pinned.
     */
    private static Floor ensureBlood(Floor floor, List<Candidate> pool, List<String> pinnedNames) {
        if (floor.bloodDepth() >= 0) {
            return floor;
        }
        Candidate blood = null;
        for (Candidate c : pool) {
            if ("BLOOD".equalsIgnoreCase(c.type())) {
                blood = c;
            }
        }
        if (blood == null) {
            return floor;
        }
        Set<String> pinnedLower = new HashSet<>();
        for (String n : pinnedNames) {
            pinnedLower.add(n.toLowerCase(Locale.ROOT));
        }
        Set<Long> fairyCells = new HashSet<>();
        for (Placement p : floor.rooms()) {
            if ("FAIRY".equalsIgnoreCase(p.type())) {
                for (int a = 0; a < p.cellsX(); a++) {
                    for (int b = 0; b < p.cellsZ(); b++) {
                        fairyCells.add(cellKey(p.originX() + a, p.originZ() + b));
                    }
                }
            }
        }
        int pick = -1;
        int pickSide = -1;
        for (int i = 0; i < floor.rooms().size(); i++) {
            Placement p = floor.rooms().get(i);
            if (p.cellsX() != 1 || p.cellsZ() != 1 || !"NORMAL".equalsIgnoreCase(p.type()) || p.depth() < 2
                    || pinnedLower.contains(p.name().toLowerCase(Locale.ROOT))
                    || touchesFairy(p.originX(), p.originZ(), fairyCells)
                    || (pick >= 0 && p.depth() <= floor.rooms().get(pick).depth())) {
                continue;
            }
            int linkSide = -1;
            int count = 0;
            for (Link l : floor.links()) {
                if (l.aX() == p.originX() && l.aZ() == p.originZ()) {
                    linkSide = sideTowards(l.bX() - l.aX(), l.bZ() - l.aZ());
                    count++;
                } else if (l.bX() == p.originX() && l.bZ() == p.originZ()) {
                    linkSide = sideTowards(l.aX() - l.bX(), l.aZ() - l.bZ());
                    count++;
                }
            }
            if (count == 1) {
                pick = i;
                pickSide = linkSide;
            }
        }
        if (pick < 0) {
            LOGGER.warn("Sim floor: no attempt placed the blood room and no dead end could take it");
            return floor;
        }
        Placement p = floor.rooms().get(pick);
        for (int r = 0; r < 4; r++) {
            if (!blood.byRotation()[r].edges().contains(RoomDoors.edge(pickSide, 0))) {
                continue;
            }
            List<Placement> rooms = new ArrayList<>(floor.rooms());
            rooms.set(pick, new Placement(blood.name(), r * 90, p.originX(), p.originZ(), 1, 1, p.depth(),
                    blood.type()));
            List<int[]> open = new ArrayList<>();
            for (int[] o : floor.openDoors()) {
                if (o[0] != p.originX() || o[1] != p.originZ()) {
                    open.add(o);
                }
            }
            LOGGER.info("Sim floor: no attempt placed the blood room, so it replaces {} at cell {},{} (depth {})",
                    p.name(), p.originX(), p.originZ(), p.depth());
            return new Floor(rooms, floor.links(), open, p.depth(), floor.spine());
        }
        return floor;
    }

    /**
     * Makes the puzzle count what the "Puzzles" slider asked for: each puzzle still owed replaces an ordinary 1x1
     * dead end (one link, not on the path to blood, not pinned) at a rotation whose doorways cover that link;
     * any extra doorway the puzzle brings is bricked up. Floors were short of puzzles before the path was laid
     * first (40% of floors over every slider combination, measured with tools/layoutsim) and more often after,
     * because the path spends doorways the growth used to hang puzzles from. A puzzle is a dead end on Hypixel,
     * which is exactly the slot this takes. Never adds more than were asked for; logs when it cannot reach it.
     */
    private static Floor ensurePuzzles(Floor floor, List<Candidate> pool, int wanted, List<String> pinnedNames,
                                       Random rng) {
        int have = 0;
        Set<String> onFloor = new HashSet<>();
        for (Placement p : floor.rooms()) {
            have += "PUZZLE".equalsIgnoreCase(p.type()) ? 1 : 0;
            onFloor.add(p.name());
        }
        if (have >= wanted) {
            return floor;
        }
        List<Candidate> puzzles = new ArrayList<>();
        Map<String, Double> key = new HashMap<>();
        for (Candidate c : pool) {
            if ("PUZZLE".equalsIgnoreCase(c.type()) && c.area(0) == 1 && !onFloor.contains(c.name())) {
                puzzles.add(c);
                key.put(c.name(), recency(c.name()) * RECENCY_ORDER_WEIGHT + rng.nextDouble() * 2.0);
            }
        }
        puzzles.sort(Comparator.comparingDouble(c -> key.get(c.name())));
        Set<String> pinnedLower = new HashSet<>();
        for (String n : pinnedNames) {
            pinnedLower.add(n.toLowerCase(Locale.ROOT));
        }
        Set<Long> onPath = spineCells(floor);
        List<Placement> rooms = new ArrayList<>(floor.rooms());
        List<int[]> open = new ArrayList<>(floor.openDoors());
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < rooms.size(); i++) {
            order.add(i);
        }
        java.util.Collections.shuffle(order, rng);
        int swapped = 0;
        for (int i : order) {
            if (have >= wanted) {
                break;
            }
            Placement p = rooms.get(i);
            if (p.cellsX() != 1 || p.cellsZ() != 1 || !"NORMAL".equalsIgnoreCase(p.type())
                    || pinnedLower.contains(p.name().toLowerCase(Locale.ROOT))
                    || onPath.contains(cellKey(p.originX(), p.originZ()))) {
                continue;
            }
            int linkSide = -1;
            int count = 0;
            for (Link l : floor.links()) {
                if (l.aX() == p.originX() && l.aZ() == p.originZ()) {
                    linkSide = sideTowards(l.bX() - l.aX(), l.bZ() - l.aZ());
                    count++;
                } else if (l.bX() == p.originX() && l.bZ() == p.originZ()) {
                    linkSide = sideTowards(l.aX() - l.bX(), l.aZ() - l.bZ());
                    count++;
                }
            }
            if (count != 1) {
                continue;
            }
            Set<String> used = new HashSet<>();
            for (Placement q : rooms) {
                used.add(q.name());
            }
            boolean done = false;
            for (Candidate puzzle : puzzles) {
                if (used.contains(puzzle.name()) || excluded(puzzle.name(), used)) {
                    continue;
                }
                for (int r = 0; r < 4 && !done; r++) {
                    Set<Integer> sides = new HashSet<>();
                    for (int[] door : RoomDoors.doorCells(puzzle.byRotation()[r], p.originX(), p.originZ())) {
                        sides.add(door[2]);
                    }
                    if (!sides.contains(linkSide)) {
                        continue;
                    }
                    rooms.set(i, new Placement(puzzle.name(), r * 90, p.originX(), p.originZ(), 1, 1, p.depth(),
                            puzzle.type()));
                    open.removeIf(o -> o[0] == p.originX() && o[1] == p.originZ());
                    for (int side : sides) {
                        if (side != linkSide) {
                            open.add(new int[]{p.originX(), p.originZ(), side});
                        }
                    }
                    done = true;
                }
                if (done) {
                    break;
                }
            }
            if (done) {
                have++;
                swapped++;
            }
        }
        if (swapped > 0) {
            LOGGER.info("Sim floor: {} puzzle(s) swapped in for ordinary dead ends to reach the {} asked for",
                    swapped, wanted);
        }
        Floor out = new Floor(rooms, floor.links(), open, floor.bloodDepth(), floor.spine());
        if (have < wanted) {
            // Last: a puzzle in an EMPTY cell, past the cell target if need be - the small floors' targets are
            // inferred, not measured (SimFloorGen.Floor), and a floor one cell over is better than one short of
            // the puzzles he asked for. Through a doorway that meets one first, carved only if none does.
            Filler f = new Filler(out, pool, wanted, pinnedNames, rng);
            f.only = c -> "PUZZLE".equalsIgnoreCase(c.type());
            int added = 0;
            while (f.puzzlesOwed > 0 && (f.strictOnce() || f.carveOnce() || f.carveOnce(false))) {
                added++;
            }
            have += added;
            out = new Floor(f.rooms, f.links, f.open, floor.bloodDepth(), floor.spine());
            if (added > 0) {
                LOGGER.info("Sim floor: {} puzzle(s) added in empty cells to reach the {} asked for", added, wanted);
            }
        }
        if (have < wanted) {
            LOGGER.warn("Sim floor: {} puzzle(s) of the {} asked for - no dead end or empty cell could take more",
                    have, wanted);
        }
        return out;
    }

    /** How many of this placement's doorways meet an open doorway facing back from a room on the floor. */
    private static int meets(RoomDoors.Mask m, int ox, int oz, String type, int[] occupied,
                             List<Placement> rooms, List<int[]> open) {
        int n = 0;
        for (int[] door : RoomDoors.doorCells(m, ox, oz)) {
            int nx = door[0] + RoomDoors.DX[door[2]];
            int nz = door[1] + RoomDoors.DZ[door[2]];
            if (nx < 0 || nz < 0 || nx >= GRID || nz >= GRID || occupied[nz * GRID + nx] < 0) {
                continue;
            }
            Placement nb = rooms.get(occupied[nz * GRID + nx]);
            if ("BLOOD".equalsIgnoreCase(nb.type()) || forbiddenPair(type, nb.type()) || isOneDoor(nb.type())) {
                continue;
            }
            if (openIndex(open, nx, nz, (door[2] + 2) % 4) >= 0) {
                n++;
            }
        }
        return n;
    }

    private static int openIndex(List<int[]> open, int x, int z, int side) {
        for (int i = 0; i < open.size(); i++) {
            int[] o = open.get(i);
            if (o[0] == x && o[1] == z && o[2] == side) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Commits one fill-pass room: every doorway that meets an open doorway facing back becomes a link (and both
     * stop being open), the rest join the open list to be bricked up or met later. {@code forcedSide} >= 0 also
     * links that side of a 1x1 to its neighbour though the neighbour has no doorway there.
     */
    private static void place(Candidate c, int r, int ox, int oz, int[] occupied, List<Placement> rooms,
                              List<Link> links, List<int[]> open, int forcedSide) {
        RoomDoors.Mask m = c.byRotation()[r];
        int idx = rooms.size();
        int depth = Integer.MAX_VALUE;
        List<int[]> mine = new ArrayList<>();
        for (int[] door : RoomDoors.doorCells(m, ox, oz)) {
            int nx = door[0] + RoomDoors.DX[door[2]];
            int nz = door[1] + RoomDoors.DZ[door[2]];
            boolean inGrid = nx >= 0 && nz >= 0 && nx < GRID && nz < GRID;
            Placement nb = inGrid && occupied[nz * GRID + nx] >= 0 ? rooms.get(occupied[nz * GRID + nx]) : null;
            // A puzzle being placed takes the first door it meets and no other; nothing links into a puzzle.
            boolean puzzleHasDoor = isOneDoor(c.type()) && depth != Integer.MAX_VALUE;
            int back = nb == null || "BLOOD".equalsIgnoreCase(nb.type()) || forbiddenPair(c.type(), nb.type())
                    || isOneDoor(nb.type()) || puzzleHasDoor
                    ? -1 : openIndex(open, nx, nz, (door[2] + 2) % 4);
            if (back >= 0 || (door[2] == forcedSide && nb != null && !isOneDoor(nb.type()) && !puzzleHasDoor)) {
                if (back >= 0) {
                    open.remove(back);
                }
                links.add(new Link(nx, nz, door[0], door[1]));
                depth = Math.min(depth, nb.depth() + 1);
            } else {
                mine.add(new int[]{door[0], door[1], door[2]});
            }
        }
        open.addAll(mine);
        for (int a = 0; a < m.tilesX(); a++) {
            for (int b = 0; b < m.tilesZ(); b++) {
                occupied[(oz + b) * GRID + ox + a] = idx;
            }
        }
        rooms.add(new Placement(c.name(), r * 90, ox, oz, m.tilesX(), m.tilesZ(),
                depth == Integer.MAX_VALUE ? 1 : depth, c.type()));
    }

    /** The {@link RoomDoors} side index pointing one cell along (dx, dz). */
    private static int sideTowards(int dx, int dz) {
        for (int s = 0; s < 4; s++) {
            if (RoomDoors.DX[s] == Integer.signum(dx) && RoomDoors.DZ[s] == Integer.signum(dz)) {
                return s;
            }
        }
        return -1;
    }

    /**
     * How recently each room has been on a generated floor: +1 for every floor it was on, halved each floor.
     *
     * <p>killer560 (2026-10-01): "it feels like the map uses just about the same set of rooms each time. It almost
     * always has mines museum and flags and other large rooms." It did. {@link #choose} orders candidates by size
     * and doorway count with a 0..1 die roll on top, so whenever the floor wanted a big room the same few big,
     * many-doored ones led the list every time. Not seeded - floors are never regenerated from a seed, a drawn
     * floor is kept as a map code - so remembering across floors changes nothing anyone relies on.
     */
    private static final Map<String, Double> RECENT = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * How much of a room's recency survives each new floor.
     *
     * <p>killer560 (2026-10-04): "It still feels like just about the exact same rooms every run." Three things
     * were found. The RNG is NOT the cause - {@code SimFloorGen.RNG} is an unseeded {@code new Random()}, so it
     * differs every launch and every floor. The memory was: {@link #RECENT} lived only in memory, so every game
     * launch started with no recency at all and the first floors of each session fell back to the same
     * favourites - and he restarts often. And the penalty was too weak to beat the deterministic part of
     * {@link #choose}'s ordering: doorways at 0.9 each and size at up to 2.2 against a 0..2.5 die meant the
     * many-doored big rooms still led the shortlist, and {@code choose} stops at the first placement that
     * scores "good enough", so the head of that list is what goes on the floor. Now the recency is persisted
     * by {@code SimRecencyStore}, decays more slowly (0.6, so a room on every floor settles at 2.5 rather
     * than 2), and weighs more in the ordering, with a wider die. (It was in the placement score too, which
     * left cells empty - see {@link #RECENCY_ORDER_WEIGHT}.)
     */
    private static final double RECENCY_DECAY = 0.6;

    /**
     * How much a room's recency pushes it down {@link #choose}'s shortlist. ORDER ONLY - it is no longer in the
     * placement score, and the shortlist cap no longer ends a search that has found nothing.
     *
     * <p>killer560 (2026-10-04, same day as the change above): "the more maps I generate the more it seems to
     * not put rooms in." Measured with {@code tools/layoutsim} on his 106-room library starting from his saved
     * recency file, 600 F7s each: the 3.0 ordering weight plus a 2.0 score penalty left 63% of floors with an
     * empty cell (15% with no recency at all) - the score penalty is as big as two stranded cells, so a fresh
     * room that walled a cell in beat a recent one that fitted. With the penalty gone, weight against holes the
     * growth leaves (all of which {@link #fillGaps} now fills) and against variety (rooms a floor shares with the
     * one before it; about 3.5 is pure chance at 21 of 106): 3.0 -> 42% / 3.5, 2.0 -> 35% / 4.3,
     * 1.5 -> 29% / 4.9, 1.0 -> 26% / 5.5, none -> 19% / 6.8.
     */
    private static final double RECENCY_ORDER_WEIGHT = 1.5;

    /** The recency map, for saving across restarts. A copy. */
    public static Map<String, Double> recencySnapshot() {
        return new HashMap<>(RECENT);
    }

    /** Puts back a saved recency map. Called once, before the first floor of a session. */
    public static void restoreRecency(Map<String, Double> saved) {
        if (saved == null) {
            return;
        }
        for (Map.Entry<String, Double> e : saved.entrySet()) {
            if (e.getKey() != null && e.getValue() != null && e.getValue() > 0 && e.getValue() < 100) {
                RECENT.put(e.getKey(), e.getValue());
            }
        }
    }

    private static void remember(Floor floor) {
        RECENT.replaceAll((k, v) -> v * RECENCY_DECAY);
        RECENT.values().removeIf(v -> v < 0.05);
        for (Placement p : floor.rooms()) {
            RECENT.merge(p.name(), 1.0, Double::sum);
        }
    }

    private static double recency(String name) {
        return RECENT.getOrDefault(name, 0.0);
    }

    /** The best of {@link #ATTEMPTS} (or {@link #PINNED_ATTEMPTS}) layouts. */
    private static Grown attemptLoop(List<Candidate> pool, int minRooms, int wantCells, int puzzles,
                                     int roomsToBlood, List<Pin> pins, Random rng) {
        Grown best = null;
        int bestScore = Integer.MIN_VALUE;
        int attempts = pins.isEmpty() ? ATTEMPTS : PINNED_ATTEMPTS;
        boolean trapAvailable = false;
        for (Candidate c : pool) {
            trapAvailable |= isTrap(c.name(), c.type());
        }
        for (int attempt = 0; attempt < attempts * 2; attempt++) {
            // The path first: every attempt lays the Entrance-to-Blood path before anything else. Only if none of
            // them could does the floor fall back to growing and dropping blood in at the first stub deep enough,
            // which only approximates the length and may miss the fairy - said in the log when it happens.
            boolean spine = attempt < attempts;
            if (!spine && best != null) {
                break;
            }
            if (attempt == attempts) {
                LOGGER.warn("Sim floor: no attempt could lay a path of {} room(s) to blood with the fairy on it;"
                        + " falling back to an approximate one", roomsToBlood);
            }
            Grown grown = growOnce(pool, minRooms, wantCells, puzzles, roomsToBlood, spine, pins, rng);
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
            // A TRAP ranks just under blood. killer560 (2026-10-04): "It should always have new or old trap on
            // every map generated." A floor without one is only kept when no attempt managed one, and then
            // ensureTrap below swaps one in after the fact.
            boolean trapOk = !trapAvailable || hasTrap(floor);
            int score = (floor.bloodDepth() >= 0 ? 4_000_000 : 0)
                    + (trapOk ? 2_000_000 : 0)
                    + (floor.rooms().size() >= minRooms ? 500_000 : 0)
                    + cellsOf(floor) * 1000
                    - grown.unusedNames().size() * 5_000
                    + floor.rooms().size() * 5
                    - floor.openDoors().size();
            if (score > bestScore) {
                bestScore = score;
                best = grown;
            }
            if (floor.bloodDepth() >= 0 && grown.unusedNames().isEmpty() && trapOk
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

    /**
     * The links that become DOORS, in the order they are decided: a spanning tree of the rooms, so the floor has
     * no loops and the Entrance-to-Blood path through its doors is unique.
     *
     * <p>killer560 (2026-09-30): "there should only be 1 way to enter a room for the first time". {@link #links}
     * is every adjacency the layout found, which is not a tree; each extra link is one of his loops. A link that
     * would join two rooms already joined is refused (both doorways stay walled), so WHICH links survive depends
     * entirely on the order they are offered in. Three passes:
     * <ol>
     *   <li>the {@link Floor#spine} - the planned Entrance-to-Blood path. Going first is what makes it THE path:
     *       every other link between two of its rooms is then a second way round and is refused. Before
     *       2026-10-04 there was no such pass, and a loop link out of the entrance (pass 2 here) was taken
     *       before the growth's own links, so the path through the doors could differ from the one the layout
     *       built and counted;</li>
     *   <li>links touching the blood room or the entrance, so neither end of the run moves;</li>
     *   <li>everything else, in layout order.</li>
     * </ol>
     * A link between two cells of one multi-tile room is not an edge between rooms; it is kept and never counts
     * against the tree. SimFloorGen writes a door for each link returned here and nothing else, and
     * {@link SimWitherDoors} walks those doors - so this is the door graph the build uses.
     */
    public static List<Link> doorLinks(Floor floor) {
        int[] owner = new int[GRID * GRID];
        java.util.Arrays.fill(owner, -1);
        int entrance = -1;
        int blood = -1;
        for (int i = 0; i < floor.rooms().size(); i++) {
            Placement p = floor.rooms().get(i);
            if (entrance < 0 && "ENTRANCE".equalsIgnoreCase(p.type())) {
                entrance = i;
            }
            if (blood < 0 && "BLOOD".equalsIgnoreCase(p.type())) {
                blood = i;
            }
            for (int a = 0; a < p.cellsX(); a++) {
                for (int b = 0; b < p.cellsZ(); b++) {
                    owner[(p.originZ() + b) * GRID + p.originX() + a] = i;
                }
            }
        }
        int[] parent = new int[floor.rooms().size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        Set<Long> doorAt = new HashSet<>();
        List<Link> out = new ArrayList<>();
        List<Link> spine = floor.spine() == null ? List.of() : floor.spine();
        // A puzzle has one door (isOneDoor): its links are left to a last pass, after every other room is joined,
        // and the first that reaches the tree is its door - so a puzzle is always a leaf and never the only way
        // to anything. A room reachable ONLY through a puzzle would come out unreachable, which tools/layoutsim
        // reports; the layout no longer builds one.
        boolean[] oneDoor = new boolean[floor.rooms().size()];
        for (int i = 0; i < oneDoor.length; i++) {
            oneDoor[i] = isOneDoor(floor.rooms().get(i).type());
        }
        boolean[] hasDoor = new boolean[floor.rooms().size()];
        for (int pass = 0; pass < 4; pass++) {
            for (Link link : pass == 0 ? spine : floor.links()) {
                int a = owner[link.aZ() * GRID + link.aX()];
                int b = owner[link.bZ() * GRID + link.bX()];
                boolean puzzleLink = (a >= 0 && oneDoor[a]) || (b >= 0 && oneDoor[b]);
                if (pass > 0 && puzzleLink != (pass == 3)) {
                    continue;
                }
                if (pass == 3 && ((a >= 0 && oneDoor[a] && (hasDoor[a] || (b >= 0 && oneDoor[b])))
                        || (b >= 0 && oneDoor[b] && hasDoor[b]) || a == blood || b == blood)) {
                    continue;   // this puzzle has its door, the link is puzzle to puzzle, or it is into blood
                }
                if (pass == 1 || pass == 2) {
                    boolean special = a == blood || b == blood || a == entrance || b == entrance;
                    if (special != (pass == 1)) {
                        continue;
                    }
                    if (!spine.isEmpty() && (a == blood || b == blood)) {
                        continue;   // blood has one way in, and the path already has it
                    }
                }
                int ca = link.aZ() * GRID + link.aX();
                int cb = link.bZ() * GRID + link.bX();
                if (!doorAt.add((long) Math.min(ca, cb) * 1000 + Math.max(ca, cb))) {
                    continue;   // this doorway pair already has its door
                }
                if (a < 0 || b < 0 || a != b) {
                    int ra = a < 0 ? -1 : root(parent, a);
                    int rb = b < 0 ? -1 : root(parent, b);
                    if (ra >= 0 && ra == rb) {
                        doorAt.remove((long) Math.min(ca, cb) * 1000 + Math.max(ca, cb));
                        continue;   // already reachable: a door here would be a second way in
                    }
                    if (ra >= 0 && rb >= 0) {
                        parent[ra] = rb;
                    }
                }
                out.add(link);
                if (a >= 0) {
                    hasDoor[a] = true;
                }
                if (b >= 0) {
                    hasDoor[b] = true;
                }
            }
        }
        return out;
    }

    /**
     * The Entrance-to-Blood path through the doors {@link #doorLinks} keeps: {@code {rooms, fairy}} where rooms is
     * how many rooms are on it NOT counting the Entrance, the Fairy or Blood (the "Rooms to blood" slider's
     * count) and fairy is 1 when the Fairy is on it. {@code {-1, 0}} when there is no blood or no way to it.
     */
    public static int[] pathToBlood(Floor floor) {
        int[] owner = new int[GRID * GRID];
        java.util.Arrays.fill(owner, -1);
        int entrance = -1;
        int blood = -1;
        int n = floor.rooms().size();
        for (int i = 0; i < n; i++) {
            Placement p = floor.rooms().get(i);
            if (entrance < 0 && "ENTRANCE".equalsIgnoreCase(p.type())) {
                entrance = i;
            }
            if (blood < 0 && "BLOOD".equalsIgnoreCase(p.type())) {
                blood = i;
            }
            for (int a = 0; a < p.cellsX(); a++) {
                for (int b = 0; b < p.cellsZ(); b++) {
                    owner[(p.originZ() + b) * GRID + p.originX() + a] = i;
                }
            }
        }
        if (entrance < 0 || blood < 0) {
            return new int[]{-1, 0};
        }
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<>());
        }
        for (Link l : doorLinks(floor)) {
            int a = owner[l.aZ() * GRID + l.aX()];
            int b = owner[l.bZ() * GRID + l.bX()];
            if (a >= 0 && b >= 0 && a != b) {
                adj.get(a).add(b);
                adj.get(b).add(a);
            }
        }
        int[] from = new int[n];
        java.util.Arrays.fill(from, -2);
        from[entrance] = -1;
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        queue.add(entrance);
        while (!queue.isEmpty()) {
            int r = queue.poll();
            for (int next : adj.get(r)) {
                if (from[next] == -2) {
                    from[next] = r;
                    queue.add(next);
                }
            }
        }
        if (from[blood] == -2) {
            return new int[]{-1, 0};
        }
        int rooms = 0;
        int fairy = 0;
        for (int r = from[blood]; r >= 0 && r != entrance; r = from[r]) {
            if ("FAIRY".equalsIgnoreCase(floor.rooms().get(r).type())) {
                fairy = 1;
            } else {
                rooms++;
            }
        }
        return new int[]{rooms, fairy};
    }

    /** Every cell at either end of a link of the planned path. */
    private static Set<Long> spineCells(Floor floor) {
        Set<Long> out = new HashSet<>();
        if (floor.spine() != null) {
            for (Link l : floor.spine()) {
                out.add(cellKey(l.aX(), l.aZ()));
                out.add(cellKey(l.bX(), l.bZ()));
            }
        }
        return out;
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
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
                                  int roomsToBlood, boolean spineWanted, List<Pin> pins, Random rng) {
        Map<String, Candidate> byName = new HashMap<>();
        List<Candidate> normal = new ArrayList<>();
        List<Candidate> puzzleRooms = new ArrayList<>();
        // The trap rooms, kept OUT of the normal pool so a floor gets exactly one and gets it on purpose -
        // see isTrap and the wantTrap rule below.
        List<Candidate> trapRooms = new ArrayList<>();
        for (Candidate c : pool) {
            byName.put(c.name().toLowerCase(Locale.ROOT), c);
            if (isTrap(c.name(), c.type())) {
                trapRooms.add(c);
                continue;
            }
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
        // Doorways from the entrance to blood: his rooms, plus the fairy when there is one to put on the path,
        // plus one. Only the fallback growth below reads it; the planned path is exact by construction. (It was
        // slider + 1 until 2026-10-04, which left no room on the path for the fairy.)
        int bloodDepth = roomsToBlood + (byName.containsKey("fairy") ? 2 : 1);

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
        int entranceIdx = -1;
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
                entranceIdx = idx;
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
                // A pinned Fairy or Blood is a goal the path to blood must reach at a set length, so the seat
                // has to be within reach of it: each room on the way spans at most four cells. Without this the
                // random seat almost never suits a short path - measured with a pinned fairy, every attempt
                // fell back to the approximate growth.
                if (pins.isEmpty() || (fits(occupied, ex, ez, em.tilesX(), em.tilesZ())
                        && seatReaches(placed, placedFrom, em, ex, ez, roomsToBlood, seat))) {
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
            entranceIdx = commit(entrance, entranceRotation * 90, ex, ez, 0, occupied, placed, placedFrom, stubs,
                    used);
            parentOf.add(-1);   // the seed
            filled += em.tilesX() * em.tilesZ();
        }
        if (!dormant.isEmpty()) {
            // A pin can be next door to the seed, so give it the chance to be reached before anything grows.
            bloodPlacedDepth = wakePins(occupied, placed, placedFrom, stubs, links, dormant, pinsReached,
                    bloodCells, bloodPlacedDepth);
        }

        // THE PATH TO BLOOD IS LAID FIRST, at exactly the length he asked for, with the fairy on it.
        //
        // killer560 (2026-10-04): "I just generated a map where fairy was not on the path to blood and it had far
        // more than the 5 the slider had picked for it." Growing the floor and dropping blood in at the first
        // stub deep enough made the depth a lower bound, not a value (it went in at depth 11 on his 5), and the
        // fairy rule could only bias towards it. Measured with tools/layoutsim over every floor x slider x
        // puzzle combination: 85% of floors had the wrong number of rooms to blood and 64% had the fairy off
        // the path. Hypixel's floor has the fairy on the blood path always, so the path is planned as a whole
        // here - Entrance, the slider's rooms with the fairy among them (never next to the entrance or blood),
        // Blood - by a bounded search, and the rest of the floor then grows around it. doorLinks makes it THE
        // path through the doors by deciding its links first.
        List<Link> spineLinks = new ArrayList<>();
        Candidate fairyRoom = byName.get("fairy");
        Candidate bloodRoom = byName.get("blood");
        // A Fairy or Blood he PINNED in the designer is the path's goal at its own cell instead of a room to
        // place: the path has to arrive at it through one of its doorways, at the right length. Only while it
        // is still dormant - a pinned blood next door to the entrance was already reached at depth 1, and then
        // no path of his length exists.
        int fairyPin = fairyRoom == null ? -1 : pinnedIndex(placed, fairyRoom.name());
        int bloodPin = bloodRoom == null ? -1 : pinnedIndex(placed, bloodRoom.name());
        boolean fairyFree = fairyRoom != null && (fairyPin >= 0 ? dormant.contains(fairyPin)
                : !used.contains(fairyRoom.name()));
        boolean bloodFree = bloodRoom != null && (bloodPin >= 0 ? dormant.contains(bloodPin)
                : !used.contains(bloodRoom.name()));
        if (spineWanted && entranceIdx >= 0 && fairyFree && bloodFree && bloodPlacedDepth < 0) {
            int intermediates = roomsToBlood + 1;   // his rooms plus the fairy
            // An unpinned fairy goes at a drawn position, 2..intermediates-1 (never next to the entrance or blood);
            // a pinned one at whichever of those positions the path reaches it.
            int fairyAt = fairyPin >= 0 ? -1 : 2 + rng.nextInt(Math.max(1, intermediates - 2));
            List<SpineStep> spine = planSpine(placed, placedFrom, entranceIdx, occupied, normal, fairyRoom,
                    bloodRoom, fairyPin, bloodPin, fairyCells, intermediates, fairyAt, used, rng);
            if (spine == null) {
                return null;   // this seat could not take the path; attemptLoop draws another
            }
            // The goals are taken out of `dormant` while the path is committed, so a path room that happens to
            // face one of their doorways cannot wake it early at the wrong depth; each is adopted when the path
            // actually arrives.
            dormant.remove(fairyPin);
            dormant.remove(bloodPin);
            int prev = entranceIdx;
            int depth = 0;
            for (SpineStep st : spine) {
                depth++;
                int tx = st.fromX() + RoomDoors.DX[st.side()];
                int tz = st.fromZ() + RoomDoors.DZ[st.side()];
                int idx;
                if (st.pin() >= 0) {
                    idx = st.pin();
                    parentOf.set(idx, prev);
                    bloodPlacedDepth = adopt(idx, depth, placed, placedFrom, stubs, pinsReached, bloodCells,
                            bloodPlacedDepth);
                } else {
                    idx = commit(st.candidate(), st.rotation() * 90, st.originX(), st.originZ(), depth,
                            occupied, placed, placedFrom, stubs, used);
                    parentOf.add(prev);
                    filled += placed.get(idx).cellsX() * placed.get(idx).cellsZ();
                }
                Link link = new Link(st.fromX(), st.fromZ(), tx, tz);
                links.add(link);
                spineLinks.add(link);
                consume(stubs, st.fromX(), st.fromZ(), st.side(), prev);
                consume(stubs, tx, tz, (st.side() + 2) % 4, idx);
                RoomDoors.Mask mask = st.candidate().byRotation()[st.rotation()];
                if (st.candidate().name().equals(bloodRoom.name())) {
                    bloodPlacedDepth = depth;
                    fairyCellsInto(bloodCells, mask, st.originX(), st.originZ());
                    final int bloodIdx = idx;
                    stubs.removeIf(s -> s.owner == bloodIdx);   // blood is the end of the run, one way in
                } else {
                    if (st.candidate().name().equals(fairyRoom.name())) {
                        fairyPlaced[0] = true;
                        fairyIndex[0] = idx;
                        fairyCellsInto(fairyCells, mask, st.originX(), st.originZ());
                    }
                    bloodPlacedDepth = meetNeighbours(st.candidate(), mask, st.originX(), st.originZ(), idx, depth,
                            occupied, placed, placedFrom, stubs, links, dormant, pinsReached, bloodCells,
                            bloodPlacedDepth);
                }
                prev = idx;
            }
            if (!dormant.isEmpty()) {
                bloodPlacedDepth = wakePins(occupied, placed, placedFrom, stubs, links, dormant, pinsReached,
                        bloodCells, bloodPlacedDepth);
            }
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
                live.clear();
                for (int i = 0; i < stubs.size(); i++) {
                    if (!dormant.contains(stubs.get(i).owner)) {
                        live.add(i);
                    }
                }
                if (live.isEmpty()) {
                    break;   // everything still open belongs to a room nothing can reach
                }
                // GROW THE FAIRY'S SIDE while blood is still owed. killer560 (2026-10-01): "Fairy is still
                // not on the blood rushing path."
                //
                // Requiring blood to be a descendant of the fairy (below) is necessary and was not
                // sufficient: the fairy went in on a coin flip somewhere, the growth carried on wherever the
                // draw sent it, and by the time blood was due there was usually no stub left under the fairy
                // at all - so the escape hatch fired on nearly every floor and the rule did nothing. Biasing
                // the DRAW towards the fairy's own subtree is what makes the subtree exist to put blood in.
                //
                // A bias, not a restriction: when the fairy's side has nothing open the draw falls back to
                // the whole list, so a floor is never lost to this.
                // drawFrom, not "pool": growOnce's own parameter is already called pool (the room
                // candidates), and shadowing it here is how this file failed to compile.
                List<Integer> drawFrom = live;
                if (fairyIndex[0] >= 0 && bloodPlacedDepth < 0) {
                    List<Integer> under = new ArrayList<>();
                    for (int i : live) {
                        if (isDescendant(parentOf, stubs.get(i).owner, fairyIndex[0])) {
                            under.add(i);
                        }
                    }
                    if (!under.isEmpty()) {
                        drawFrom = under;
                    }
                }
                int si = drawFrom.get(rng.nextInt(drawFrom.size()));
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
                // NO COIN FLIP. The fairy goes in at the first spot that can take it, because blood now waits
                // on it - a fairy placed late is a fairy with no subtree under it, and that is what made the
                // rule above do nothing on most floors.
                boolean wantFairy = !fairyPlaced[0] && !wantBlood && needMore && stub.depth >= 1
                        && byName.containsKey("fairy") && !touchesFairy(tx, tz, bloodCells);
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
                } else if (wantTrap(trapRooms, used, stub.depth, cellsLeft, placed.size(), minRooms, rng)) {
                    // killer560 (2026-10-04): "It should always have new or old trap on every map generated."
                    // Asked for on a coin flip while the floor is young, and on every stub once it is nearly
                    // full, so it cannot be squeezed out by the last few cells.
                    wanted = trapRooms;
                } else if (puzzlesLeft > 0 && !puzzleRooms.isEmpty()
                        && (rng.nextDouble() < 0.35 || cellsLeft <= puzzlesLeft * 2 + 2)) {
                    // On a coin flip, and on every stub once the cells left are few enough that waiting longer
                    // risks the floor finishing short of the "Puzzles" slider (ensurePuzzles is the last resort).
                    wanted = puzzleRooms;
                } else {
                    wanted = normal;
                }
                // The dead-end guard counts in CELLS now: a one-doorway room spends a doorway and gives none
                // back, so it is only allowed once there is almost nothing left to fill.
                int remaining = Math.max(cellsLeft, minRooms - placed.size());
                // A given room is never held back by the size cap: there is exactly one blood and one fairy.
                Best best = choose(wanted, used, stub, tx, tz, occupied, stubs, remaining,
                        wantBlood || wantFairy || wanted == puzzleRooms || wanted == trapRooms, preferBig, cellsLeft,
                        wantBlood || wantFairy || wanted == trapRooms ? Integer.MAX_VALUE : maxArea, dormant, rng);
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
                // is what puts loops in the floor instead of a tree (doorLinks then keeps one way in).
                // DEGREES divided down to an index. Best.rotation is degrees, like everything else that
                // crosses into RoomPlacer, and indexing a four-element array with 180 is what it sounds like.
                bloodPlacedDepth = meetNeighbours(best.candidate, best.candidate.byRotation()[best.rotation / 90],
                        best.originX, best.originZ, idx, stub.depth + 1, occupied, placed, placedFrom, stubs, links,
                        dormant, pinsReached, bloodCells, bloodPlacedDepth);
                if ("PUZZLE".equalsIgnoreCase(best.candidate.type())) {
                    puzzlesLeft--;
                    // ONE DOOR. Nothing grows out of a puzzle: its other doorways go straight to the bricked-up
                    // list instead of becoming stubs - see isOneDoor.
                    for (int k = stubs.size() - 1; k >= 0; k--) {
                        if (stubs.get(k).owner == idx) {
                            failed.add(stubs.remove(k));
                        }
                    }
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
                    if (!fairyOnWay && fairyIndex[0] >= 0) {
                        // SAID OUT LOUD. The escape hatch is meant to be rare; a floor that takes it has a
                        // fairy the blood rush does not pass through, and if this line is in every log the
                        // bias above is not working rather than the floor being unusual.
                        LOGGER.warn("Sim floor: blood went in at depth {} WITHOUT the fairy on the way to it"
                                + " - the fairy's side of the floor had nothing left to grow into",
                                stub.depth + 1);
                    }
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
        return new Grown(new Floor(kept, links, open, bloodPlacedDepth, spineLinks), pinsReached, pinsUnusedNames,
                pinsUnused);
    }

    /**
     * Whether this stub should be spent on a trap room: none on the floor yet (a used trap's name is in
     * {@code used}, pinned or grown), at least one doorway in from the entrance, and either a 30% roll or the
     * floor close enough to full that waiting any longer risks never placing one.
     */
    private static boolean wantTrap(List<Candidate> trapRooms, Set<String> used, int stubDepth, int cellsLeft,
                                    int placedCount, int minRooms, Random rng) {
        if (trapRooms.isEmpty() || stubDepth < 1) {
            return false;
        }
        for (Candidate t : trapRooms) {
            if (used.contains(t.name())) {
                return false;
            }
        }
        return cellsLeft <= 8 || placedCount + 5 >= minRooms || rng.nextDouble() < 0.3;
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
        if (isOneDoor(p.type())) {
            stubs.removeIf(s -> s.owner == idx);   // and so is a puzzle - see isOneDoor
        }
        return bloodDepth;
    }

    /**
     * Every doorway of a room just committed that meets a doorway facing back becomes a link - this is what puts
     * loops in the floor ({@link #doorLinks} then keeps one way into each room). Opening one into a dormant pin
     * wakes it. Moved out of {@link #growOnce}'s loop unchanged so the planned path's rooms get the same pass.
     *
     * @param depth the new room's own depth
     * @return the blood depth, updated if a pinned blood room woke
     */
    private static int meetNeighbours(Candidate c, RoomDoors.Mask mask, int originX, int originZ, int idx,
                                      int depth, int[] occupied, List<Placement> placed,
                                      List<Candidate> placedFrom, List<Stub> stubs, List<Link> links,
                                      Set<Integer> dormant, List<String> pinsReached, Set<Long> bloodCells,
                                      int bloodPlacedDepth) {
        for (int[] door : RoomDoors.doorCells(mask, originX, originZ)) {
            int nx = door[0] + RoomDoors.DX[door[2]];
            int nz = door[1] + RoomDoors.DZ[door[2]];
            if (nx < 0 || nz < 0 || nx >= GRID || nz >= GRID) {
                continue;
            }
            int neighbour = occupied[nz * GRID + nx];
            if (neighbour < 0 || neighbour == idx) {
                continue;
            }
            if (forbiddenPair(c.type(), placed.get(neighbour).type())
                    || isOneDoor(c.type()) || isOneDoor(placed.get(neighbour).type())) {
                continue;   // a puzzle keeps the one door it came in by - see isOneDoor
            }
            if (consume(stubs, nx, nz, (door[2] + 2) % 4, neighbour)) {
                links.add(new Link(door[0], door[1], nx, nz));
                consume(stubs, door[0], door[1], door[2], idx);
                // THIS is the doorway that reaches a room he pinned, most of the time. The loop used to open a
                // real door into a dormant pin and consume its stub without recording that the pin was now part
                // of the floor, so the pin was pruned at the end WITH the link still pointing at its cells.
                // Measured: 63% of single pins honoured and 67 links to an empty cell over 200 floors, until
                // this went in.
                if (dormant.remove(neighbour)) {
                    bloodPlacedDepth = adopt(neighbour, depth + 1, placed, placedFrom, stubs,
                            pinsReached, bloodCells, bloodPlacedDepth);
                }
            }
        }
        return bloodPlacedDepth;
    }

    /** The index of a room he pinned by this name, or -1. Pins are committed before anything else is. */
    private static int pinnedIndex(List<Placement> placed, String name) {
        for (int i = 0; i < placed.size(); i++) {
            if (placed.get(i).name().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * One room of the planned Entrance-to-Blood path, and the doorway (cell and side) of the room before it that
     * leads into it. {@code pin} is the placement index when the room is one he pinned (already on the grid),
     * else -1.
     */
    private record SpineStep(Candidate candidate, int rotation, int originX, int originZ,
                             int fromX, int fromZ, int side, int pin) {
    }

    /** One way to place the next room of the path, with its sort key drawn once (no RNG in a comparator). */
    private record SpineOption(SpineStep step, double key) {
    }

    /** How many placements the path search may try before giving the attempt up (attemptLoop draws again). */
    private static final int SPINE_BUDGET = 4000;

    /** How many different rooms per position of the path the search tries before backing up a room. */
    private static final int SPINE_BRANCH = 4;

    /**
     * Plans the Entrance-to-Blood path: {@code intermediates} rooms - the Fairy at position {@code fairyAt}
     * (2..intermediates-1, so never next to the Entrance or Blood), ordinary rooms with at least two doorways
     * everywhere else - then Blood, each joined to the one before through a measured doorway in both rooms.
     * Depth-first with backtracking, bounded by {@link #SPINE_BUDGET}. A pinned Fairy or Blood
     * ({@code fairyPin}/{@code bloodPin} >= 0) is arrived at instead of placed, the fairy at any allowed position.
     *
     * @return the rooms in order, Blood last, or null when this attempt cannot take a path that long
     */
    private static List<SpineStep> planSpine(List<Placement> placed, List<Candidate> placedFrom, int entranceIdx,
                                             int[] occupied, List<Candidate> normal, Candidate fairy,
                                             Candidate blood, int fairyPin, int bloodPin, Set<Long> fairyCells,
                                             int intermediates, int fairyAt, Set<String> used, Random rng) {
        Map<String, Double> key = new HashMap<>();
        List<Candidate> ordinary = new ArrayList<>();
        for (Candidate c : normal) {
            if (c.doorCount() >= 2) {
                ordinary.add(c);
                // choose()'s own ordering with small rooms preferred: area, doorways, a die, recency.
                key.put(c.name(), c.area(0) * 0.45 - c.doorCount() * 0.9 + rng.nextDouble() * 4.0
                        + recency(c.name()) * RECENCY_ORDER_WEIGHT);
            }
        }
        key.put(fairy.name(), 0.0);
        key.put(blood.name(), 0.0);
        Spine search = new Spine(placed, placedFrom, occupied.clone(), ordinary, fairy, blood, fairyPin, bloodPin,
                intermediates, fairyAt, new HashSet<>(used), key, rng);
        search.fairyCells.addAll(fairyCells);
        Placement start = placed.get(entranceIdx);
        return search.extend(1, placedFrom.get(entranceIdx).byRotation()[start.rotation() / 90],
                start.originX(), start.originZ(), entranceIdx, false) ? search.out : null;
    }

    /** The search state of {@link #planSpine}. */
    private static final class Spine {
        final List<Placement> placed;
        final List<Candidate> placedFrom;
        final int[] occ;
        final List<Candidate> ordinary;
        final Candidate fairy;
        final Candidate blood;
        final int fairyPin;
        final int bloodPin;
        final int intermediates;
        final int fairyAt;
        final Set<String> used;
        final Map<String, Double> key;
        final Random rng;
        final Set<Long> fairyCells = new HashSet<>();
        final List<SpineStep> out = new ArrayList<>();
        int budget = SPINE_BUDGET;

        Spine(List<Placement> placed, List<Candidate> placedFrom, int[] occ, List<Candidate> ordinary,
              Candidate fairy, Candidate blood, int fairyPin, int bloodPin, int intermediates, int fairyAt,
              Set<String> used, Map<String, Double> key, Random rng) {
            this.placed = placed;
            this.placedFrom = placedFrom;
            this.occ = occ;
            this.ordinary = ordinary;
            this.fairy = fairy;
            this.blood = blood;
            this.fairyPin = fairyPin;
            this.bloodPin = bloodPin;
            this.intermediates = intermediates;
            this.fairyAt = fairyAt;
            this.used = used;
            this.key = key;
            this.rng = rng;
        }

        /**
         * Extends the path by the room at position {@code step}, entered from {@code from} (placement index
         * {@code fromIdx}, or -1 for a room only planned). {@code fairyDone} says whether the fairy is behind.
         */
        boolean extend(int step, RoomDoors.Mask from, int fromOx, int fromOz, int fromIdx, boolean fairyDone) {
            boolean last = step == intermediates + 1;
            if (last && !fairyDone) {
                return false;
            }
            boolean fairyHere = !last && !fairyDone && (fairyPin >= 0
                    ? step >= 2 && step <= intermediates - 1
                    : step == fairyAt);
            // A drawn fairy position is the only room allowed there; a pinned fairy may be arrived at on any
            // allowed step, and must be by the last one.
            boolean fairyOnly = fairyHere && (fairyPin < 0 || step == intermediates - 1);
            List<SpineOption> options = new ArrayList<>();
            for (int[] door : RoomDoors.doorCells(from, fromOx, fromOz)) {
                int tx = door[0] + RoomDoors.DX[door[2]];
                int tz = door[1] + RoomDoors.DZ[door[2]];
                if (tx < 0 || tz < 0 || tx >= GRID || tz >= GRID) {
                    continue;
                }
                int need = (door[2] + 2) % 4;
                int there = occ[tz * GRID + tx];
                if (there >= 0) {
                    // Only a pinned goal may be stepped onto, through a doorway of its own facing back.
                    int goal = last ? bloodPin : fairyHere ? fairyPin : -1;
                    if (goal < 0 || there != goal || there == fromIdx) {
                        continue;
                    }
                    Placement g = placed.get(goal);
                    Candidate gc = placedFrom.get(goal);
                    RoomDoors.Mask gm = gc.byRotation()[g.rotation() / 90];
                    if (!hasDoor(gm, g.originX(), g.originZ(), tx, tz, need)
                            || (last && touchesAny(gm, g.originX(), g.originZ(), fairyCells))) {
                        continue;
                    }
                    options.add(new SpineOption(new SpineStep(gc, g.rotation() / 90, g.originX(), g.originZ(),
                            door[0], door[1], door[2], goal), -100.0));
                    continue;
                }
                List<Candidate> pool;
                if (last) {
                    pool = bloodPin >= 0 ? List.of() : List.of(blood);
                } else if (fairyHere && fairyPin < 0) {
                    pool = List.of(fairy);
                } else if (fairyOnly) {
                    pool = List.of();
                } else {
                    pool = ordinary;
                }
                for (Candidate c : pool) {
                    if (used.contains(c.name()) || excluded(c.name(), used)) {
                        continue;
                    }
                    for (int r = 0; r < 4; r++) {
                        RoomDoors.Mask m = c.byRotation()[r];
                        for (int packed : m.edges()) {
                            if (RoomDoors.sideOf(packed) != need) {
                                continue;
                            }
                            int[] o = originFor(m, packed, need, tx, tz);
                            if (!fits(occ, o[0], o[1], m.tilesX(), m.tilesZ())) {
                                continue;
                            }
                            // Blood never next to the fairy (a room always between); everything before blood
                            // needs a doorway onward.
                            if (last ? touchesAny(m, o[0], o[1], fairyCells)
                                    : !hasOnward(m, o[0], o[1], tx, tz, need)) {
                                continue;
                            }
                            double k = key.get(c.name()) + rng.nextDouble();
                            boolean toFairy = !fairyDone && fairyPin >= 0;
                            int goal = toFairy ? fairyPin : bloodPin;
                            if (goal >= 0) {
                                // A pinned goal must still be reachable in the rooms left before it: each room
                                // spans at most four cells, so a room further than that per room left is a
                                // dead branch, cut here rather than searched. Nearer rooms are tried first.
                                int goalStep = toFairy ? intermediates - 1 : intermediates + 1;
                                int gap = gap(m, o[0], o[1], placed.get(goal), placedFrom.get(goal));
                                if (gap - 1 > 4 * (goalStep - step - 1)) {
                                    continue;
                                }
                                k += 0.6 * gap;
                            }
                            options.add(new SpineOption(
                                    new SpineStep(c, r, o[0], o[1], door[0], door[1], door[2], -1), k));
                        }
                    }
                }
            }
            options.sort(Comparator.comparingDouble(SpineOption::key));
            // At most SPINE_BRANCH different ROOMS per position, so backing up tries other rooms rather than the
            // same room at every rotation; every placement of a room already chosen is still tried.
            Set<String> tried = new HashSet<>();
            for (SpineOption opt : options) {
                if (budget-- <= 0) {
                    return false;
                }
                SpineStep st = opt.step();
                if (tried.add(st.candidate().name()) && tried.size() > SPINE_BRANCH) {
                    return false;
                }
                RoomDoors.Mask m = st.candidate().byRotation()[st.rotation()];
                boolean isPin = st.pin() >= 0;
                if (!isPin) {
                    mark(m, st.originX(), st.originZ(), Integer.MAX_VALUE);
                }
                out.add(st);
                if (last) {
                    return true;
                }
                used.add(st.candidate().name());
                boolean isFairy = st.candidate().name().equals(fairy.name());   // a pin is its own Candidate object
                Set<Long> fairyBefore = isFairy ? new HashSet<>(fairyCells) : null;
                if (isFairy) {
                    fairyCellsInto(fairyCells, m, st.originX(), st.originZ());
                }
                if (extend(step + 1, m, st.originX(), st.originZ(), isPin ? st.pin() : Integer.MAX_VALUE,
                        fairyDone || isFairy)) {
                    return true;
                }
                if (isFairy) {
                    fairyCells.clear();
                    fairyCells.addAll(fairyBefore);
                }
                if (!isPin) {
                    used.remove(st.candidate().name());
                    mark(m, st.originX(), st.originZ(), -1);
                }
                out.remove(out.size() - 1);
            }
            return false;
        }

        private void mark(RoomDoors.Mask m, int ox, int oz, int value) {
            for (int a = 0; a < m.tilesX(); a++) {
                for (int b = 0; b < m.tilesZ(); b++) {
                    occ[(oz + b) * GRID + ox + a] = value;
                }
            }
        }

        /** Whether this placement has a doorway, other than the one it is entered by, the path can go on through. */
        private boolean hasOnward(RoomDoors.Mask m, int ox, int oz, int tx, int tz, int need) {
            for (int[] door : RoomDoors.doorCells(m, ox, oz)) {
                if (door[0] == tx && door[1] == tz && door[2] == need) {
                    continue;
                }
                int nx = door[0] + RoomDoors.DX[door[2]];
                int nz = door[1] + RoomDoors.DZ[door[2]];
                if (nx < 0 || nz < 0 || nx >= GRID || nz >= GRID) {
                    continue;
                }
                if (nx >= ox && nx < ox + m.tilesX() && nz >= oz && nz < oz + m.tilesZ()) {
                    continue;   // its own other half
                }
                int there = occ[nz * GRID + nx];
                if (there < 0 || (there != Integer.MAX_VALUE && (there == fairyPin || there == bloodPin))) {
                    return true;   // a free cell, or a pinned goal the path may step onto
                }
            }
            return false;
        }
    }

    /**
     * Whether an entrance seat is near enough to a pinned Fairy and Blood for the path to reach each at the
     * length asked for. Gives up being picky after 40 draws, so a seat is always found.
     */
    private static boolean seatReaches(List<Placement> placed, List<Candidate> placedFrom, RoomDoors.Mask em,
                                       int ex, int ez, int roomsToBlood, int draw) {
        if (draw >= 40) {
            return true;
        }
        int intermediates = roomsToBlood + 1;
        for (int i = 0; i < placed.size(); i++) {
            String t = placed.get(i).type();
            int roomsBefore = "FAIRY".equalsIgnoreCase(t) ? intermediates - 2
                    : "BLOOD".equalsIgnoreCase(t) ? intermediates : -1;
            if (roomsBefore >= 0 && gap(em, ex, ez, placed.get(i), placedFrom.get(i)) - 1 > 4 * roomsBefore) {
                return false;
            }
        }
        return true;
    }

    /** Manhattan distance between the nearest cells of a placement and a placed room (1 = next door). */
    private static int gap(RoomDoors.Mask m, int ox, int oz, Placement g, Candidate gc) {
        RoomDoors.Mask gm = gc.byRotation()[g.rotation() / 90];
        int dx = Math.max(0, Math.max(g.originX() - (ox + m.tilesX() - 1), ox - (g.originX() + gm.tilesX() - 1)));
        int dz = Math.max(0, Math.max(g.originZ() - (oz + m.tilesZ() - 1), oz - (g.originZ() + gm.tilesZ() - 1)));
        return dx + dz;
    }

    /** Whether a placement has a doorway at cell (x, z) on {@code side}. */
    private static boolean hasDoor(RoomDoors.Mask m, int ox, int oz, int x, int z, int side) {
        for (int[] door : RoomDoors.doorCells(m, ox, oz)) {
            if (door[0] == x && door[1] == z && door[2] == side) {
                return true;
            }
        }
        return false;
    }

    /** Whether any cell of this placement is, or is next door to, one of {@code cells}. */
    private static boolean touchesAny(RoomDoors.Mask m, int ox, int oz, Set<Long> cells) {
        for (int a = 0; a < m.tilesX(); a++) {
            for (int b = 0; b < m.tilesZ(); b++) {
                if (touchesFairy(ox + a, oz + b, cells)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The origin that puts doorway {@code packed} (on side {@code need}) of {@code mask} on cell (tx, tz). */
    private static int[] originFor(RoomDoors.Mask mask, int packed, int need, int tx, int tz) {
        int index = RoomDoors.indexOf(packed);
        return switch (need) {
            case RoomDoors.NORTH -> new int[]{tx - index, tz};
            case RoomDoors.SOUTH -> new int[]{tx - index, tz - mask.tilesZ() + 1};
            case RoomDoors.WEST -> new int[]{tx, tz - index};
            default -> new int[]{tx - mask.tilesX() + 1, tz - index};
        };
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

    /**
     * Whether a room of this type has exactly ONE door - a puzzle.
     *
     * <p>killer560 (2026-10-04): "Somehow my map just generated with quiz having two doors in it when it can only have
     * one." On Hypixel every puzzle room is a dead end: one door in, and the puzzle's other wall openings are walls.
     * His log names the floor: "the fill pass put 2 room(s) in (... 2 through a carved wall): ... Redstone Crypt at
     * 5,0 through Quiz" - the fill pass cut a door through Quiz's wall to reach an empty cell, so Redstone Crypt
     * hung off Quiz and the door tree, which only refuses loops, had to keep both. Measured with
     * {@code tools/layoutsim -Dsweep=true} (30 floors per combination, his library and recency), 15% of floors had a
     * puzzle with two or three doors, through every route that links rooms: the growth hanging rooms off a puzzle's
     * other doorways, the loop pass, the fill pass meeting a puzzle's open doorway, and the carve. Every one of
     * them now asks this, and {@link #doorLinks} gives a puzzle one door at most as the last word.
     *
     * <p>Traps are left as they are (the trap rule is separate and he asked for it unchanged).
     */
    static boolean isOneDoor(String type) {
        return "PUZZLE".equalsIgnoreCase(type);
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
            // A wider die and a cost for having been on recent floors - see RECENT.
            key.put(c.name(), c.area(0) * areaWeight - c.doorCount() * 0.9 + rng.nextDouble() * 4.0
                    + recency(c.name()) * RECENCY_ORDER_WEIGHT);
        }
        shortlist.sort(Comparator.comparingDouble(c -> key.get(c.name())));

        Best best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int examined = 0;
        for (Candidate c : shortlist) {
            // The cap only ends a search that has FOUND something. Recency pushes the fresh rooms to the front,
            // so the ones that fit a tight spot can sit past the first sixty; stopping there left the stub
            // unfilled.
            if (examined++ > 60 && best != null) {
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
                    // NO recency in here. It used to subtract recency * 2.0, which is as much as two stranded
                    // cells cost, so a fresh room that walled a cell in beat a recent one that fitted cleanly -
                    // that is how "the more maps I generate the more it seems to not put rooms in" happened.
                    // Recency orders the shortlist above and nothing else; ties go to the earlier, fresher room.
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

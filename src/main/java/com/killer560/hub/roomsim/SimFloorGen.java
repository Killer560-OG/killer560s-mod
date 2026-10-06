package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.util.ModChat;

import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Generates a whole floor, rather than a corridor to the blood door.
 *
 * <p>killer560 (2026-09-28): "it shouldnt just generate rooms between me and blood it should generate a full
 * map", plus "have an option to choose what map size so for instance entrance, f1, f6, f7", "Max rooms to blood
 * should be 8 and it should be a sliding bar between 2-8. Do not count blood, green room, or fairy those are
 * given. Make puzzles a bar as well from 2-5."
 *
 * <p>The old generator laid a single line of rooms from the entrance to blood. That is not a floor and it is
 * not what a route is written against: on a real floor most of the rooms are off to the side, the order you
 * take them in is the decision being practised, and a corridor removes the decision entirely.
 *
 * <p>The layout itself lives in {@link SimFloorLayout}, which fits rooms together by their real doorways -
 * killer560 (2026-09-29): "it should read which rooms have doors where and make sure that each room is
 * reachable via going through doors into rooms." This file turns what that produces into a map code: one name
 * table entry per placement, the rotation on every cell the room covers, and a door in the cell between each
 * pair of linked rooms.
 *
 * <p>Rooms-to-blood is the distance from the entrance to the blood door along that map, counted the way he
 * counts it - blood, entrance and fairy are given and do not count towards it.
 *
 * <p><b>Rooms are rotated now.</b> They were not, for two reasons that both had to be dealt with first: the
 * cells were reserved from the unrotated footprint, so a quarter turn spilled a 2x1 room over its neighbour;
 * and {@link SimSecrets} measured every secret from the room's north-west corner whatever the rotation, so a
 * turned room threw its chests outside itself. The footprint is now rotated with the room and the secrets are
 * measured from the corner that matches the rotation, which is what made a door-driven layout possible at all
 * - with fixed orientations, fitting rooms together by their doorways almost never works.
 */
public final class SimFloorGen {

    private static final Random RNG = new Random();

    /** The room grid inside {@link DungeonLayout}'s 11x11: rooms sit on even coordinates, doors between. */
    private static final int ROOM_GRID = (DungeonLayout.GRID + 1) / 2;

    /**
     * Floor sizes, as the number of rooms on the map.
     *
     * <p>MEASURED, not estimated. killer560 ran Entrance through F7 on 2026-09-28 and {@code FloorSizeLog}
     * recorded each one; these are the largest sample per floor, which is the fully-revealed map - the smaller
     * samples are the same run seen earlier, before the whole thing was on the map.
     *
     * <p>Two of my estimates were wrong in a way worth keeping a note of. I had F6 bigger than F5 and F7
     * bigger than both; the measurements say F5 and F7 are 21 and F6 is 19, so floor number is not room count
     * and guessing from it was never going to work.
     *
     * <p><b>{@code cells} is the real target, {@code rooms} only a floor under it.</b> killer560 (2026-09-29):
     * "it still isnt generating a full map." A room is not a cell - a 1x2 covers two of the 36 room slots and a
     * 2x2 covers four - so a generator that stops at 21 ROOMS fills anywhere from 21 to 36 cells depending on
     * which rooms it happened to pick, and measured over 100 planned F7s it came out at a median of 30 with
     * whole rows of the grid empty.
     *
     * <p>36 for F7 is MEASURED: the {@code [LiveMap] Cell ... -> ROOM} lines from 40 of his own recorded
     * dungeon scans put a fully-walked floor at 34-36 of the 36 room slots, with 12 of the 40 at exactly 36.
     * The smaller floors are NOT measured - they are that same 36/21 cells-per-room ratio applied to each
     * floor's measured room count and capped at the grid, so they are an inference and the floor logger is
     * still the thing that would correct them.
     */
    public enum Floor {
        ENTRANCE("Entrance", "E", 11, 19),
        F1("Floor 1", "F1", 13, 22),
        F2("Floor 2", "F2", 15, 26),
        F3("Floor 3", "F3", 16, 27),
        F4("Floor 4", "F4", 19, 33),
        F5("Floor 5", "F5", 21, 36),
        F6("Floor 6", "F6", 19, 33),
        F7("Floor 7", "F7", 21, 36);

        public final String label;
        /**
         * The short form the rest of the mod writes a floor as - "F7", "E" - and the one
         * {@link com.killer560.hub.roomsim.SimState#setFloorLabel} expects.
         *
         * <p>That setter existed and nothing ever called it, so the sim thought it was on F7 whatever was
         * actually generated. Anything that behaves differently per floor was therefore wrong on every floor
         * but one: the mimic rule below, and the floor's own name in the sidebar.
         */
        public final String code;
        /** Fewest rooms the floor may have. Other code reads this, and scenario 73 asserts it. */
        public final int rooms;
        /** Room slots of the 6x6 grid to fill - what the generator actually aims at. */
        public final int cells;

        Floor(String label, String code, int rooms, int cells) {
            this.label = label;
            this.code = code;
            this.rooms = rooms;
            this.cells = cells;
        }
    }

    /** His sliders' limits, in one place so the menu and the generator cannot disagree. */
    public static final int MIN_ROOMS_TO_BLOOD = 2;
    /**
     * Eight, on killer560's word: "the max is 8 if you do not count blood green room or fairy. It cannot be
     * more."
     *
     * <p>My own measurement said ten and it was measuring the wrong thing - it counted every cell on the path
     * including the ones belonging to the three given rooms, which is not the number he means. The logger now
     * counts his way, so the next set of runs either confirms eight or shows me something I have still got
     * wrong.
     */
    public static final int MAX_ROOMS_TO_BLOOD = 8;
    public static final int MIN_PUZZLES = 2;
    public static final int MAX_PUZZLES = 5;

    private SimFloorGen() {
    }

    /**
     * Builds and opens a floor.
     *
     * @param roomsToBlood how many ordinary rooms stand between the entrance and blood - blood, the entrance
     *                     and the fairy room are given and are not counted, which is how he counts them
     */
    private static final org.slf4j.Logger LOGGER =
            com.killer560.hub.util.ModLog.get("killer560smod-roomsim");

    /**
     * A planned floor: the map code and what the planner decided, with nothing built yet.
     *
     * @param bloodDistance rooms on the Entrance-to-Blood path through the doors as built, counted the slider's
     *                      way (not the Entrance, the Fairy or Blood); -1 if blood cannot be reached
     * @param bigPlaced     rooms larger than one cell
     * @param keptPins      rooms he had placed by hand that the floor was built AROUND, at his own cells
     * @param unusedPins    {@code "Name - reason"} per room he had placed that could not be used; always empty
     *                      from the three-argument {@link #plan}, which has nothing pinned to it
     * @param missed        what the floor misses of what was asked (SimFloorLayout's shortfalls), empty when exact
     * @param filterNote    what the map designer's room filters did to this floor, for its status line - null when
     *                      no filter was given or it changed nothing worth saying. Starts "filters too strict" when
     *                      the filtered pool could not make a whole floor and every room was used instead.
     */
    public record Planned(MapCode.Decoded decoded, String code, int placedPuzzles, int bloodDistance,
                          int bigPlaced, List<String> keptPins, List<String> unusedPins, List<String> missed,
                          String filterNote) {

        Planned withNote(String note) {
            return new Planned(decoded, code, placedPuzzles, bloodDistance, bigPlaced, keptPins, unusedPins, missed,
                    note);
        }
    }

    public static void generate(Minecraft client, Floor floor, int puzzles, int roomsToBlood) {
        // The "this is a whole floor" flag is NOT set here any more; SimBuilder.build sets it.
        //
        // Setting it here set it too early and in too few places. Too early because SimWorld.open below
        // unloads whatever world is open first, and SimWorld.onWorldUnloaded calls SimState.leave(), which
        // clears the flag - so from the second generation of a session onwards it was already false by the
        // time the floor was built. Too few places because four other paths build a floor without coming
        // through here at all: SimGenerator's two map-code entries, the map editor's Build, and
        // SimRunHistory's rebuild. All five go through SimBuilder.build, so that is where it belongs.
        Planned planned = plan(floor, puzzles, roomsToBlood);
        if (planned == null) {
            return;   // plan() has already said why
        }
        // Which floor this is, for everything downstream that behaves differently per floor - the mimic rule
        // and the sidebar. Set here because this is the only entry point that KNOWS the floor; a map code
        // carries rooms, not a floor number, so those paths keep the default.
        SimState.setFloorLabel(floor.code);
        ModChat.send("Sim", ModChat.text(floor.label + ": "),
                ModChat.value(String.valueOf(planned.decoded().nameTable().length)),
                ModChat.text(" rooms, "), ModChat.value(String.valueOf(planned.placedPuzzles())),
                ModChat.text(" puzzle(s), blood "),
                ModChat.value(String.valueOf(planned.bloodDistance())), ModChat.text(" rooms in"));
        if (planned.bigPlaced() > 0) {
            ModChat.send("Sim", ModChat.dim(planned.bigPlaced() + " room(s) larger than 1x1"));
        }
        SimWorld.open(client, planned.code(), c -> SimBuilder.build(c, planned.code()),
                "Generating " + floor.label);
    }

    /**
     * Lays out a floor and returns it, without touching the world.
     *
     * <p>Split out of {@link #generate} on 2026-09-29 so the layout can be tested properly. Testing it through
     * {@code generate} means opening a world per floor, which is slow enough that a scenario can only afford
     * one - and one sample cannot tell "the generator never places multi-tile rooms" from "this floor happened
     * not to". The first version of that assertion passed and failed on alternate runs for exactly that reason,
     * and a test that flaps is worse than no test because it teaches you to ignore it.
     *
     * @return the planned floor, or null when it could not lay one out (having said so in chat)
     */
    public static Planned plan(Floor floor, int puzzles, int roomsToBlood) {
        return plan(floor, puzzles, roomsToBlood, null);
    }

    /**
     * The same, but laid out AROUND rooms he has already placed on the designer's grid.
     *
     * <p>killer560 (2026-09-29): "test stuff like putting in a single room that I want personally in generating
     * a map around the room". Every pinned room stays at the cell he put it at; its ROTATION is chosen by
     * {@link SimFloorLayout}, because a rotation decides which sides a room's doorways are on and so whether the
     * rest of the floor can connect to it, and the designer has no way of asking for one.
     *
     * <p>What a pin cannot do is make the floor worse. It is still connected from the entrance through doorways
     * measured in both rooms, one-doorway rooms still come out at dead ends, and the cell target is still met -
     * a pinned room nothing can reach is left OFF the floor and named in {@link Planned#unusedPins()}, and the
     * floor is then laid out again without it rather than handed over with a hole in it. If he pins the Entrance
     * that cell is the entrance; the L-shape filter does not apply to a room he placed himself.
     *
     * @param pinned cell index {@code gz * ROOM_GRID + gx} to room name, keyed the way the map designer keys it;
     *               null or empty makes this identical to the three-argument form
     */
    public static Planned plan(Floor floor, int puzzles, int roomsToBlood, Map<Integer, String> pinned) {
        Planned p = planWith(floor, puzzles, roomsToBlood, pinned, null, false);
        reportUnusedPins(p);
        return p;
    }

    /**
     * The same, drawing only on the rooms {@code allow} accepts - the map designer's room filters
     * ({@link SimRoomFilters#generatorAllows}). A room he pinned is always allowed.
     *
     * <p>The floor must still be a whole, valid floor. When the filtered rooms cannot make one (no layout at all,
     * or one that misses the room minimum, the cell target, blood, the trap or the exact path - SimFloorLayout's
     * shortfalls), the floor is laid out again from every room and he is told so in chat and in
     * {@link Planned#filterNote()}, rather than handed a broken floor or nothing. The recency memory is put back
     * first, so the discarded filtered floor does not count as "recently played".
     *
     * <p>Fewer allowed puzzles than the slider asks for is NOT a failure: the floor gets the puzzles he allowed and
     * the note says how many that was.
     *
     * @param allow null for every room, which makes this {@link #plan(Floor, int, int, Map)}
     */
    public static Planned plan(Floor floor, int puzzles, int roomsToBlood, Map<Integer, String> pinned,
                               java.util.function.Predicate<String> allow) {
        // Before the database loads every room reads NORMAL, so a type filter would judge them all wrongly - and
        // the unfiltered path already says "still loading" in chat and returns null.
        if (allow == null || !RoomDatabase.isReady()) {
            return plan(floor, puzzles, roomsToBlood, pinned);
        }
        Set<String> pinnedLower = new HashSet<>();
        if (pinned != null) {
            for (String n : pinned.values()) {
                pinnedLower.add(n.toLowerCase(Locale.ROOT));
            }
        }
        java.util.function.Predicate<String> keep = n -> allow.test(n) || pinnedLower.contains(n.toLowerCase(Locale.ROOT));
        int wantPuzzles = Math.max(MIN_PUZZLES, Math.min(MAX_PUZZLES, puzzles));
        int allowedPuzzles = 0;
        for (Map.Entry<String, RoomLibrary.Room> e : usableRooms().entrySet()) {
            int[] fp = cellFootprint(e.getValue());
            if ("PUZZLE".equalsIgnoreCase(typeOf(e.getKey())) && fp[0] == 1 && fp[1] == 1 && keep.test(e.getKey())) {
                allowedPuzzles++;
            }
        }
        Map<String, Double> recency = SimFloorLayout.recencySnapshot();
        Planned filtered = planWith(floor, puzzles, roomsToBlood, pinned, keep, true);
        String reason = filtered == null ? "no floor could be laid out from them"
                : filtered.missed().isEmpty() ? null : String.join(", ", filtered.missed());
        if (reason != null) {
            LOGGER.warn("Sim floor: the designer's room filters cannot make a whole {} ({}); using every room",
                    floor.label, reason);
            SimFloorLayout.restoreRecency(recency);
            ModChat.send("Sim", ModChat.text("Your room filters leave too few rooms for a whole " + floor.label
                    + " (" + reason + ") - "), ModChat.dim("generated this one from every room instead."));
            Planned full = planWith(floor, puzzles, roomsToBlood, pinned, null, false);
            reportUnusedPins(full);
            return full == null ? null : full.withNote("filters too strict - used every room");
        }
        reportUnusedPins(filtered);
        if (allowedPuzzles < wantPuzzles) {
            String note = "only " + allowedPuzzles + " puzzle" + (allowedPuzzles == 1 ? "" : "s")
                    + " allowed (slider " + wantPuzzles + ")";
            ModChat.send("Sim", ModChat.text("Your filters allow " + allowedPuzzles + " puzzle room"
                    + (allowedPuzzles == 1 ? "" : "s") + " and the slider asks for " + wantPuzzles + " - "),
                    ModChat.dim("this floor has " + filtered.placedPuzzles() + "."));
            return filtered.withNote(note);
        }
        return filtered.withNote("filtered");
    }

    /** Said here rather than only returned, so the reason reaches him even from a caller that ignores it. */
    private static void reportUnusedPins(Planned p) {
        if (p == null) {
            return;
        }
        for (String note : p.unusedPins()) {
            ModChat.send("Sim", ModChat.dim("could not keep your " + note));
        }
    }

    /**
     * The layout itself. {@code keep} narrows the room pool (null keeps every room); {@code quiet} keeps the
     * "could not lay out" messages out of chat, for an attempt the caller may still replace.
     */
    private static Planned planWith(Floor floor, int puzzles, int roomsToBlood, Map<Integer, String> pinned,
                                    java.util.function.Predicate<String> keep, boolean quiet) {
        long planStart = System.currentTimeMillis();
        Map<String, RoomLibrary.Room> usable = usableRooms();
        if (usable.isEmpty()) {
            ModChat.send("Sim", ModChat.text("No usable rooms in the room library - "),
                    ModChat.dim("the shipped rooms did not load (see the log)."));
            return null;
        }
        if (keep != null) {
            usable.keySet().removeIf(n -> !keep.test(n));
        }
        // Room TYPES come from the room database (typeOf); before it has loaded every room reads NORMAL, so the
        // layout has no Entrance, Blood, Fairy, Trap or puzzle to place and quietly hands back a floor without them
        // (testkit 73: "ENTRANCE #104: no blood room on the floor", and "-1 room(s) to blood" on a third of the
        // floors planned in that client, 2026-10-05). Same rule as the live map: do not judge rooms before isReady.
        if (!RoomDatabase.isReady()) {
            RoomDatabase.ensureLoading();
            ModChat.send("Sim", ModChat.text("The room database is still loading - "),
                    ModChat.dim("try again in a few seconds (without it no room is known to be the Entrance or Blood)."));
            return null;
        }
        usable = capChampions(usable, pinned);

        int wantRooms = Math.min(floor.rooms, ROOM_GRID * ROOM_GRID);
        int wantCells = Math.max(wantRooms, Math.min(floor.cells, ROOM_GRID * ROOM_GRID));
        int wantPuzzles = Math.max(MIN_PUZZLES, Math.min(MAX_PUZZLES, puzzles));
        // Counted his way: "the max is 8 if you do not count blood green room or fairy." So the path is the
        // Entrance, exactly this many rooms with the Fairy among them, and Blood - SimFloorLayout lays it first.
        // (This used to pass slider + 1 as a blood DEPTH, which had no room on it for the fairy.)
        int wantDistance = Math.max(MIN_ROOMS_TO_BLOOD, Math.min(MAX_ROOMS_TO_BLOOD, roomsToBlood));

        // The "recently used rooms" memory survives restarts now - see SimRecencyStore.
        SimRecencyStore.ensureLoaded();
        SimFloorLayout.PinnedFloor pinnedOut = SimFloorLayout.generate(
                usable, wantRooms, wantCells, wantPuzzles, wantDistance, pinned, RNG);
        if (pinnedOut != null) {
            SimRecencyStore.save();
        }
        SimFloorLayout.Floor laid = pinnedOut == null ? null : pinnedOut.floor();
        if (laid == null || laid.rooms().size() < 3) {
            if (!quiet) {
                ModChat.send("Sim", ModChat.text("Could not lay out a floor that size - "),
                        ModChat.dim("the Entrance room has to be captured first."));
            }
            return null;
        }
        // The pins it could not keep are said by the public plan()s (reportUnusedPins), once the floor that is
        // kept is known - a filtered attempt may still be replaced.

        int gridCells = DungeonLayout.GRID * DungeonLayout.GRID;
        int[] cellRoom = new int[gridCells];
        int[] cellDoor = new int[gridCells];
        int[] cellRotation = new int[gridCells];
        java.util.Arrays.fill(cellRoom, MapCode.NO_ROOM);
        List<String> nameTable = new ArrayList<>();

        int[] entranceCell = null;
        int[] bloodCell = null;
        int placedPuzzles = 0;
        int bigPlaced = 0;
        for (SimFloorLayout.Placement p : laid.rooms()) {
            nameTable.add(p.name());
            int id = nameTable.size() - 1;
            List<int[]> cells = new ArrayList<>();
            for (int a = 0; a < p.cellsX(); a++) {
                for (int c = 0; c < p.cellsZ(); c++) {
                    cells.add(new int[]{p.originX() + a, p.originZ() + c});
                }
            }
            markRoomCells(cellRoom, cellRotation, cells, id, p.rotation());
            if (cells.size() > 1) {
                bigPlaced++;
            }
            if ("PUZZLE".equalsIgnoreCase(p.type())) {
                placedPuzzles++;
            }
            if (entranceCell == null && "ENTRANCE".equalsIgnoreCase(p.type())) {
                entranceCell = new int[]{p.originX(), p.originZ()};
            }
            if (bloodCell == null && "BLOOD".equalsIgnoreCase(p.type())) {
                bloodCell = new int[]{p.originX(), p.originZ()};
            }
        }

        // Doors go in the cell BETWEEN two rooms, which is where a door lives on the grid - writing them onto
        // the room cells, as an older version did, left the map with nothing to draw a connector from and made
        // the floor logger report "blood -1 cells in" because its search steps between rooms only through one.
        // NO LOOPS, and this is the pass that decides it.
        //
        // killer560 (2026-09-30): "there should only be 1 way to enter a room for the first time [...] see how
        // i can essentially make an infinate loop by running through flags into supertall into slime then back
        // to flags."
        //
        // {@code laid.links()} is every adjacency the layout found, which is not a tree: measured over 120
        // planned floors (2026-09-29) every single one carried between 3 and 8 links more than a tree, and each
        // of those extra links IS one of his loops. The first attempt at this put the union-find in
        // {@code linkDoors}, which only the designer's explicitly-drawn path calls - so the generated floors,
        // the ones he was actually complaining about, were untouched and the scenario still reported all 120.
        // The check has to be here, where the generated floor's doors are written.
        //
        // Which links survive as doors is decided in SimFloorLayout.doorLinks, so the offline layout tool
        // (tools/layoutsim) measures exactly the door graph written here: the planned Entrance-to-Blood path
        // first (which is what makes it THE path - any other link between two of its rooms is then a loop and
        // refused), then blood and entrance links, then the rest. Its javadoc has the detail.
        int doors = 0;
        for (SimFloorLayout.Link link : SimFloorLayout.doorLinks(laid)) {
            int a = gridCell(new int[]{link.aX(), link.aZ()});
            int bCell = gridCell(new int[]{link.bX(), link.bZ()});
            int between = (a + bCell) / 2;
            if (cellDoor[between] != DungeonLayout.DOOR_NONE) {
                continue;
            }
            boolean toBlood = bloodCell != null
                    && (cellRoom[a] == cellRoom[gridCell(bloodCell)]
                        || cellRoom[bCell] == cellRoom[gridCell(bloodCell)]);
            boolean fromEntrance = entranceCell != null
                    && (cellRoom[a] == cellRoom[gridCell(entranceCell)]
                        || cellRoom[bCell] == cellRoom[gridCell(entranceCell)]);
            cellDoor[between] = toBlood ? DungeonLayout.DOOR_BLOOD
                    : fromEntrance ? DungeonLayout.DOOR_ENTRANCE
                    : DungeonLayout.DOOR_NORMAL;
            doors++;
        }

        LOGGER.info("[SimPhase] layout planned in {} ms: {} room(s) over {}/{} cell(s), {} door(s), "
                        + "{} doorway(s) to brick up",
                System.currentTimeMillis() - planStart, laid.rooms().size(), SimFloorLayout.cellsOf(laid),
                wantCells, doors, laid.openDoors().size());
        // Measured on the doors just written, not taken from the layout's own bookkeeping - the old number was
        // the growth's depth, which is not what the doors (or SimWitherDoors) see.
        int[] path = SimFloorLayout.pathToBlood(laid);
        if (path[0] != wantDistance || path[1] == 0) {
            LOGGER.warn("Sim floor: {} room(s) to blood (asked for {}), fairy {} the path", path[0], wantDistance,
                    path[1] == 1 ? "on" : "NOT on");
        }
        MapCode.Decoded decoded = new MapCode.Decoded(
                nameTable.toArray(new String[0]), cellRoom, cellDoor, cellRotation);
        return new Planned(decoded, MapCode.encodeDecoded(decoded), placedPuzzles, path[0], bigPlaced,
                pinnedOut.honouredPins(), pinnedOut.unusedPins(), pinnedOut.missed(), null);
    }

    /**
     * Builds exactly the floor he drew, instead of one this class invented.
     *
     * <p>killer560 (2026-09-29) wants Ashfall's Dungeon Maker: pick rooms from a list, place them on a grid,
     * press play. That is this - the map editor hands over a placement per room-grid cell and everything from
     * here down is the same code the random generator uses, so a drawn floor and a generated one differ only
     * in how the cells were chosen.
     *
     * <p>Placements are keyed by {@code gz * ROOM_GRID + gx} and anchored at their top-left cell, the corner
     * the capture measured from. A room whose footprint runs off the grid or over another room is dropped and
     * reported rather than pasted on top of its neighbour.
     *
     * @return how many rooms were placed, or -1 if there was nothing to build
     */
    public static int buildExplicit(Minecraft client, Map<Integer, String> placements) {
        Planned planned = planExplicit(placements);
        if (planned == null) {
            return -1;
        }
        ModChat.send("Sim", ModChat.text("Your map: "),
                ModChat.value(String.valueOf(planned.decoded().nameTable().length)),
                ModChat.text(" rooms, "), ModChat.value(String.valueOf(planned.bloodDistance())),
                ModChat.text(" doors"));
        SimWorld.open(client, planned.code(), c -> SimBuilder.build(c, planned.code()), "Building your map");
        return planned.decoded().nameTable().length;
    }

    /**
     * Lays out the drawn floor without touching the world - the editor's half of {@link #plan}.
     *
     * <p>Split for the same reason: a layout that can only be checked by building a world can only be checked
     * once per scenario, and once is not enough to tell a defect from a coincidence.
     *
     * <p>{@code bloodDistance} carries the DOOR COUNT here rather than a distance, because a drawn floor has
     * no generated blood path to measure.
     */
    public static Planned planExplicit(Map<Integer, String> placements) {
        return planExplicit(placements, false);
    }

    /**
     * The same, optionally without a word in chat - for the map designer, which re-plans the drawing every
     * time he places a room so it can show which gaps really become doors, and must not narrate each one.
     */
    public static Planned planExplicit(Map<Integer, String> placements, boolean quiet) {
        Map<String, RoomLibrary.Room> usable = usableRooms();
        if (placements == null || placements.isEmpty()) {
            if (!quiet) {
                ModChat.send("Sim", ModChat.text("Nothing placed on the map yet."));
            }
            return null;
        }
        int gridCells = DungeonLayout.GRID * DungeonLayout.GRID;
        int[] cellRoom = new int[gridCells];
        int[] cellDoor = new int[gridCells];
        int[] cellRotation = new int[gridCells];
        java.util.Arrays.fill(cellRoom, MapCode.NO_ROOM);
        List<String> nameTable = new ArrayList<>();

        Set<Long> filled = new java.util.HashSet<>();
        List<int[]> occupied = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        int[] entrance = null;
        int[] blood = null;

        // Sorted so the result does not depend on hash order - the same drawing must build the same floor.
        List<Integer> keys = new ArrayList<>(placements.keySet());
        java.util.Collections.sort(keys);
        for (int slot : keys) {
            String name = placements.get(slot);
            RoomLibrary.Room room = usable.get(name);
            if (room == null) {
                dropped.add(name + " (not captured)");
                continue;
            }
            int[] at = {slot % ROOM_GRID, slot / ROOM_GRID};
            int[] size = cellFootprint(room);
            List<int[]> cells = new ArrayList<>();
            boolean fits = true;
            for (int dx = 0; dx < size[0] && fits; dx++) {
                for (int dz = 0; dz < size[1] && fits; dz++) {
                    int[] c = {at[0] + dx, at[1] + dz};
                    if (c[0] >= ROOM_GRID || c[1] >= ROOM_GRID || filled.contains(key(c))) {
                        fits = false;
                    } else {
                        cells.add(c);
                    }
                }
            }
            if (!fits) {
                dropped.add(name + " (no room for its " + size[0] + "x" + size[1] + " footprint)");
                continue;
            }
            int id = nameTable.size();
            nameTable.add(name);
            for (int[] c : cells) {
                filled.add(key(c));
                occupied.add(c);
            }
            markRoomCells(cellRoom, cellRotation, cells, id, 0);
            String type = typeOf(name);
            if (entrance == null && "ENTRANCE".equals(type)) {
                entrance = at;
            }
            if (blood == null && "BLOOD".equals(type)) {
                blood = at;
            }
        }
        if (nameTable.isEmpty()) {
            if (!quiet) {
                ModChat.send("Sim", ModChat.text("Nothing on the map could be placed."));
            }
            return null;
        }
        // No entrance drawn: the first placement stands in, so /start still has a door to open.
        if (entrance == null) {
            entrance = new int[]{keys.get(0) % ROOM_GRID, keys.get(0) / ROOM_GRID};
        }
        int doors = linkDoors(cellRoom, cellDoor, occupied, filled, entrance, blood);

        for (String d : quiet ? List.<String>of() : dropped) {
            ModChat.send("Sim", ModChat.dim("skipped " + d));
        }
        MapCode.Decoded decoded = new MapCode.Decoded(
                nameTable.toArray(new String[0]), cellRoom, cellDoor, cellRotation);
        return new Planned(decoded, MapCode.encodeDecoded(decoded), 0, doors, 0, List.of(), List.of(), List.of(),
                null);
    }

    /** Every room that can actually be pasted - complete AND at the current footprint. */
    public static Map<String, RoomLibrary.Room> usableRooms() {
        Map<String, RoomLibrary.Room> usable = new HashMap<>();
        for (String name : RoomLibrary.names()) {
            RoomLibrary.Room r = RoomLibrary.get(name);
            if (r != null && r.usable()) {
                usable.put(name, r);
            }
        }
        return usable;
    }

    /** The database's shape for a room ("1x1", "1x2", "2x2", "L", ...), or null when it does not know it. */
    public static String shapeOf(String name) {
        var entry = com.killer560.hub.roomdatabase.RoomDatabase.lookupByName(name);
        return entry == null ? null : entry.shape;
    }

    /** The database's type for a room, or "NORMAL" when it does not know it. */
    /**
     * At most ONE yellow room on a floor.
     *
     * <p>killer560 (2026-10-01): "there should only be the ability for there to be 1 yellow room per run."
     * Yellow on the Catacombs map is the miniboss colour - {@code MapPainter} maps colour 74 to it - and the
     * room database's name for that type is {@code CHAMPION}, of which it holds four.
     *
     * <p>Done by trimming the CANDIDATE POOL rather than by constraining the layout engine, which is both
     * simpler and exactly equivalent: a floor never uses the same room name twice, so a pool holding one
     * champion can place at most one champion. The engine keeps its existing type rules untouched.
     *
     * <p>A champion he PINNED is always the one kept, because a pin is a deliberate request and dropping it
     * would surface as "could not keep your pinned room" for a reason he never asked about.
     */
    private static Map<String, RoomLibrary.Room> capChampions(Map<String, RoomLibrary.Room> usable,
                                                              Map<Integer, String> pinned) {
        List<String> champions = new ArrayList<>();
        for (String name : usable.keySet()) {
            if ("CHAMPION".equalsIgnoreCase(typeOf(name))) {
                champions.add(name);
            }
        }
        if (champions.size() <= 1) {
            return usable;
        }
        String keep = null;
        if (pinned != null) {
            for (String name : champions) {
                if (pinned.containsValue(name)) {
                    keep = name;
                    break;
                }
            }
        }
        if (keep == null) {
            keep = champions.get(RNG.nextInt(champions.size()));
        }
        Map<String, RoomLibrary.Room> trimmed = new java.util.LinkedHashMap<>(usable);
        for (String name : champions) {
            if (!name.equals(keep)) {
                trimmed.remove(name);
            }
        }
        return trimmed;
    }

    public static String typeOf(String name) {
        var entry = com.killer560.hub.roomdatabase.RoomDatabase.lookupByName(name);
        return entry == null || entry.type == null ? "NORMAL" : entry.type;
    }

    /**
     * Puts a door in every gap between two DIFFERENT rooms that touch.
     *
     * <p>Lifted out of {@link #generate} so the drawn floor and the generated one cannot drift apart. The rule
     * that matters is the one about a room's own halves: a 2x2's four cells touch each other and must not get
     * doors between them, or the floor fills with doorways inside single rooms.
     */
    /**
     * Puts a door in every gap between two DIFFERENT rooms that touch - unless it would close a LOOP.
     *
     * <p>killer560 (2026-09-30): "when it generates a map there should only be 1 way to enter a room for the
     * first time [...] see how i can essentially make an infinate loop by running through flags into supertall
     * into slime then back to flags."
     *
     * <p>The growth pass already opens one door per room as it reaches it, which is a tree by construction.
     * This pass then doored every remaining adjacency, and every one of those extra doors closed a cycle -
     * that is exactly what a loop is. So the rooms are tracked with a union-find seeded from the doors that
     * already exist, and a gap is only opened when the two sides are not already connected some other way.
     * Everything else stays walled, which is what the seal pass bricks up.
     *
     * <p>The rule that matters is the one about a room's own halves: a 2x2's four cells touch each other and
     * must not get doors between them, or the floor fills with doorways inside single rooms. That is handled
     * by comparing room ids, not cells, and it is also why the union-find is keyed on the room id.
     */
    private static int linkDoors(int[] cellRoom, int[] cellDoor, List<int[]> occupied, Set<Long> filled,
                                 int[] entrance, int[] blood) {
        // The doors the growth pass already placed - and they are NOT all keepers.
        //
        // This used to only SEED the union-find from them, on the assumption that the growth pass produced a
        // tree and that all this had to do was refuse to add a second way in. It does not: measured over 120
        // planned floors (2026-09-29) EVERY floor already carried between 3 and 8 doors more than a tree, so
        // refusing to add more left every loop in place. killer560 (2026-09-30): "there should only be 1 way
        // to enter a room for the first time" - so a door that closes a cycle has to be REMOVED, not merely
        // not-added.
        //
        // Two passes, because which door of a cycle gets dropped matters. Wither, blood and entrance doors are
        // the run's structure: they are seeded first and always survive, so the pruning can only ever fall on
        // an ordinary door. Interior doors - both sides the same multi-tile room - are not edges at all and
        // are left alone.
        Map<Integer, Integer> parent = new HashMap<>();
        for (int pass = 0; pass < 2; pass++) {
            for (int cell = 0; cell < cellDoor.length; cell++) {
                int type = cellDoor[cell];
                if (type == DungeonLayout.DOOR_NONE) {
                    continue;
                }
                boolean special = type != DungeonLayout.DOOR_NORMAL;
                if (special != (pass == 0)) {
                    continue;
                }
                int gx = cell % DungeonLayout.GRID;
                int gz = cell / DungeonLayout.GRID;
                int left = gx > 0 ? cellRoom[cell - 1] : MapCode.NO_ROOM;
                int right = gx + 1 < DungeonLayout.GRID ? cellRoom[cell + 1] : MapCode.NO_ROOM;
                int up = gz > 0 ? cellRoom[cell - DungeonLayout.GRID] : MapCode.NO_ROOM;
                int down = gz + 1 < DungeonLayout.GRID ? cellRoom[cell + DungeonLayout.GRID] : MapCode.NO_ROOM;
                int a = MapCode.NO_ROOM;
                int b = MapCode.NO_ROOM;
                if (left != MapCode.NO_ROOM && right != MapCode.NO_ROOM) {
                    a = left;
                    b = right;
                } else if (up != MapCode.NO_ROOM && down != MapCode.NO_ROOM) {
                    a = up;
                    b = down;
                }
                if (a == MapCode.NO_ROOM || a == b) {
                    continue; // a door into nothing, or the inside of one multi-tile room
                }
                if (!special && find(parent, a) == find(parent, b)) {
                    cellDoor[cell] = DungeonLayout.DOOR_NONE;
                    continue;
                }
                union(parent, a, b);
            }
        }

        int doors = 0;
        for (int[] c : occupied) {
            for (int[] step : new int[][]{{1, 0}, {0, 1}}) {
                int[] n = {c[0] + step[0], c[1] + step[1]};
                if (!filled.contains(key(n))) {
                    continue;
                }
                int a = gridCell(c);
                int bCell = gridCell(n);
                if (cellRoom[a] < 0 || cellRoom[bCell] < 0 || cellRoom[a] == cellRoom[bCell]) {
                    continue;
                }
                int between = (a + bCell) / 2;
                if (cellDoor[between] != DungeonLayout.DOOR_NONE) {
                    continue;
                }
                // Already reachable from each other: a door here would be a second way in.
                if (find(parent, cellRoom[a]) == find(parent, cellRoom[bCell])) {
                    continue;
                }
                union(parent, cellRoom[a], cellRoom[bCell]);
                boolean toBlood = blood != null && (same(c, blood) || same(n, blood));
                boolean fromEntrance = entrance != null && (same(c, entrance) || same(n, entrance));
                cellDoor[between] = toBlood ? DungeonLayout.DOOR_BLOOD
                        : fromEntrance ? DungeonLayout.DOOR_ENTRANCE
                        : DungeonLayout.DOOR_NORMAL;
                doors++;
            }
        }
        return doors;
    }

    private static int find(Map<Integer, Integer> parent, int room) {
        int root = room;
        while (parent.getOrDefault(root, root) != root) {
            root = parent.get(root);
        }
        // Path compression, so a long chain of rooms does not make every later lookup walk it again.
        int walk = room;
        while (parent.getOrDefault(walk, walk) != walk) {
            int next = parent.get(walk);
            parent.put(walk, root);
            walk = next;
        }
        return root;
    }

    private static void union(Map<Integer, Integer> parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent.put(ra, rb);
        }
    }


    /**
     * How many grid cells a captured room covers, as {width, height}.
     *
     * <p>Read from the capture rather than from the database's shape string, because the capture is what will
     * actually be pasted. If those two ever disagree the paste wins, so the layout has to be planned against
     * it - planning against the database and pasting the capture is how you get a room overlapping its
     * neighbour.
     */
    private static int[] cellFootprint(RoomLibrary.Room r) {
        if (r == null) {
            return new int[]{1, 1};
        }
        return new int[]{tilesAcross(r.sizeX), tilesAcross(r.sizeZ)};
    }

    /**
     * Room cells spanned by a captured dimension.
     *
     * <p>One room cell is one tile: cells sit 16 blocks apart, rooms occupy every other one, and a tile is 31
     * blocks with a 1-block seam. So the inverse of {@link RoomLibrary#footprint} is the whole of it -
     * {@code size = tiles * 32 + 1}, therefore {@code tiles = (size - 1) / 32}.
     *
     * <p>Wrong between 00:27 and 01:20 on 2026-09-29, and only for multi-tile rooms. The old form read the
     * captured size as a TILE span and halved it, which was exactly right while capture was inflating every
     * multi-tile room by a factor of about 1.65 - the two errors cancelled. Fixing the capture left this one
     * standing alone, and it then planned a 2-tile room into a single cell: 65 blocks pasted into a 32-block
     * slot, straight over the neighbour. That is "the map isnt generating right".
     */
    private static int tilesAcross(int size) {
        return Math.max(1, (size - 1) / (RoomLibrary.TILE + 1));
    }

    /**
     * The cells a room of this footprint would occupy starting here, or null if it does not fit.
     *
     * <p>It must fit entirely inside the shape that was grown and touch nothing already placed. Anchored at the
     * top-left, which is the corner the capture measured from.
     */
    private static List<int[]> footprintCells(int[] at, int[] size, Set<Long> inShape, Set<Long> filled) {
        List<int[]> cells = new ArrayList<>();
        for (int dx = 0; dx < size[0]; dx++) {
            for (int dz = 0; dz < size[1]; dz++) {
                int[] c = {at[0] + dx, at[1] + dz};
                long k = key(c);
                if (!inShape.contains(k) || filled.contains(k)) {
                    return null;
                }
                cells.add(c);
            }
        }
        return cells;
    }








    /**
     * Marks every cell a room covers, INCLUDING the connector cells between its own tiles.
     *
     * <p>This is the convention a live Hypixel capture already uses - {@code DungeonLayout} marks the cell
     * between two tiles of one room as part of that room ("ROOM (connector)" in its own log) - and the
     * generator did not follow it. It wrote only the even tile cells and left the connector as NO_ROOM.
     *
     * <p>{@code SimBuilder} pastes one room per group found by flood-filling cells that share an id, and that
     * flood fill steps ONE cell at a time. From an even tile cell its neighbours are the odd connectors, so on
     * a generated floor it never reached the room's other tile: every tile became its own group and the whole
     * room was pasted at each of them, offset by a tile and smeared over the neighbour. Live captures were
     * fine, which is why this survived - the two paths disagreed about what a room's cells are.
     *
     * <p>Filling the connector fixes it at the source and leaves one convention instead of two. Connectors
     * BETWEEN DIFFERENT rooms are untouched: those are where the doors go.
     */
    private static void markRoomCells(int[] cellRoom, int[] cellRotation, List<int[]> cells, int id,
                                      int rotation) {
        // The whole grid box the room's tiles span, which is every tile cell plus every connector between
        // them - including the CENTRE of a 2x2, which sits diagonally between four tiles and is reached by no
        // edge midpoint. A first version filled only the edge seams and left 2x2 rooms at 8 cells of 9, which
        // still split the flood fill. The reserved cells are always a rectangle (footprintCells walks a
        // rectangle), so the box IS the room.
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int[] c : cells) {
            minX = Math.min(minX, c[0]);
            minZ = Math.min(minZ, c[1]);
            maxX = Math.max(maxX, c[0]);
            maxZ = Math.max(maxZ, c[1]);
        }
        for (int gz = minZ * 2; gz <= maxZ * 2; gz++) {
            for (int gx = minX * 2; gx <= maxX * 2; gx++) {
                int cell = gz * DungeonLayout.GRID + gx;
                cellRoom[cell] = id;
                cellRotation[cell] = rotation;
            }
        }
    }

    /** Room cell to the 11x11 grid index - rooms live on even coordinates. */
    private static int gridCell(int[] c) {
        return (c[1] * 2) * DungeonLayout.GRID + (c[0] * 2);
    }

    private static long key(int[] c) {
        return ((long) c[0] << 32) ^ (c[1] & 0xffffffffL);
    }

    private static boolean same(int[] a, int[] b) {
        return a[0] == b[0] && a[1] == b[1];
    }
}

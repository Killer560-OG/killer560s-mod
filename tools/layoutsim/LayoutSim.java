import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.roomsim.RoomLibrary;
import com.killer560.hub.roomsim.SimFloorGen;
import com.killer560.hub.roomsim.SimFloorLayout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Runs the sim's real floor layout (SimFloorLayout + RoomDoors, unmodified) outside the game, over real captures.
 *
 * <pre>
 * tools/layoutsim/run.sh -Droomdata=.../rooms-modern.json [-Drooms=DIR] [-Drecent=.../sim-recent.json]
 *                        [-Dcorrupt=Name,Name] [-Dnorecency=true] [-Dusage=true] [floors]
 * </pre>
 *
 * The shipped captures are always read; a usable capture in -Drooms wins over the shipped one, as in the game.
 * -Dcorrupt drops the rooms RoomTileAudit refused (the game names them in its log). Lays out F7s the way
 * SimFloorGen.plan does (21 rooms, 36 cells, one champion) and prints the cell coverage.
 *
 * <p>-Dsweep=true instead lays out [floors] floors for EVERY floor size x "Rooms to blood" (2..8) x "Puzzles"
 * (2..5) and judges each one through the door graph the build writes ({@link SimFloorLayout#doorLinks}, the same
 * call SimFloorGen.plan makes): rooms on the Entrance-to-Blood path (not counting Entrance, Fairy, Blood) equal
 * the slider, the Fairy on that path, SimWitherDoors marking exactly that path's ordinary doors, every room
 * reachable through doors, the cell target met, the room minimum, one blood, one trap, one fairy, and the puzzle
 * count. -Dbloodoffset (default 0) is added to the slider before it is passed in (the generator before 2026-10-04
 * took a blood depth, slider + 1, so its baseline was measured with 1); -Ddump=N
 * prints the first N failing floors. -Donly=F7 -Dslider=5 -Dpuzzles=3 narrow the sweep to one combination;
 * -Dpinroom=Quiz -Dpincell=14 pins that room at that cell (cellZ * 6 + cellX) on every floor, as the Map Designer
 * does, and -Ddumpdrop=N prints the first N floors that dropped it. "floors that dropped the fixed pin" counts
 * floors whose generate() left a pin (-Dpin or -Dpinroom) off.
 */
public final class LayoutSim {

    public static void main(String[] args) throws Exception {
        int floors = args.length > 1 ? Integer.parseInt(args[1]) : 500;
        Path bundled = Path.of(args[0]);
        java.util.Set<String> corrupt = new java.util.HashSet<>();
        for (String s : System.getProperty("corrupt", "").split(",")) {
            if (!s.isBlank()) {
                corrupt.add(s.trim().toLowerCase());
            }
        }
        Map<String, RoomLibrary.Room> rooms = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        String disk = System.getProperty("rooms");
        if (disk != null) {
            try (var files = Files.list(Path.of(disk))) {
                for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                    RoomLibrary.Room r = read(f);
                    if (r != null && r.usable()) {
                        rooms.put(r.name, r);
                    }
                }
            }
        }
        try (var files = Files.list(bundled)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                RoomLibrary.Room r = read(f);
                if (r != null && r.usable() && !rooms.containsKey(r.name)) {
                    rooms.put(r.name, r);
                }
            }
        }
        rooms.keySet().removeIf(n -> corrupt.contains(n.toLowerCase()));
        RoomLibrary.ROOMS.putAll(rooms);
        String roomdata = System.getProperty("roomdata");
        if (roomdata != null) {
            for (var e : JsonParser.parseString(Files.readString(Path.of(roomdata))).getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                if (!o.has("name") || o.get("name").isJsonNull()) {
                    continue;
                }
                String n = o.get("name").getAsString();
                if (o.has("type") && !o.get("type").isJsonNull()) {
                    SimFloorGen.TYPE.put(n, o.get("type").getAsString());
                }
                if (o.has("shape") && !o.get("shape").isJsonNull()) {
                    SimFloorGen.SHAPE.put(n, o.get("shape").getAsString());
                }
            }
        }
        String recent = System.getProperty("recent");
        if (recent != null) {
            Map<String, Double> saved = new HashMap<>();
            for (var e : JsonParser.parseString(Files.readString(Path.of(recent))).getAsJsonObject().entrySet()) {
                saved.put(e.getKey(), e.getValue().getAsDouble());
            }
            SimFloorLayout.restoreRecency(saved);
        }
        boolean noRecency = Boolean.getBoolean("norecency");
        if (Boolean.getBoolean("sweep")) {
            sweep(rooms, floors, noRecency);
            return;
        }
        System.out.println(rooms.size() + " usable room(s); " + floors + " F7 floor(s), recency "
                + (noRecency ? "OFF" : "ON" + (recent != null ? " starting from " + recent : "")));

        Random rng = new Random(Long.getLong("seed", System.nanoTime()));
        int[] cellHist = new int[37];
        int shortFloors = 0;
        Map<String, Integer> used = new TreeMap<>();
        java.util.Set<String> previous = new java.util.HashSet<>();
        long shared = 0;
        int badFloors = 0;
        long t0 = System.nanoTime();
        int warnBefore = com.killer560.hub.util.ModLog.warnings;
        for (int i = 0; i < floors; i++) {
            if (noRecency) {
                clearRecency();
            }
            Map<String, RoomLibrary.Room> usable = capChampions(rooms, rng);
            SimFloorLayout.Floor f = SimFloorLayout.generate(usable, 21, 36, 3, 8, rng);
            String broken = check(f);
            if (broken != null) {
                badFloors++;
                if (badFloors <= 3) {
                    System.out.println("BROKEN floor " + i + ": " + broken);
                    dump(f);
                }
            }
            int cells = SimFloorLayout.cellsOf(f);
            cellHist[cells]++;
            if (cells < 36) {
                if (shortFloors++ < Integer.getInteger("dump", 0)) {
                    dump(f);
                }
            }
            java.util.Set<String> now = new java.util.HashSet<>();
            for (SimFloorLayout.Placement p : f.rooms()) {
                used.merge(p.name(), 1, Integer::sum);
                String t = p.type().toUpperCase();
                if (!t.equals("BLOOD") && !t.equals("ENTRANCE") && !t.equals("FAIRY")) {
                    now.add(p.name());
                }
            }
            if (i > 0) {
                for (String n : now) {
                    shared += previous.contains(n) ? 1 : 0;
                }
            }
            previous = now;
        }
        System.out.printf("%.2f ms a floor%n", (System.nanoTime() - t0) / 1e6 / floors);
        System.out.println("cells filled -> floors:");
        for (int c = 0; c <= 36; c++) {
            if (cellHist[c] > 0) {
                System.out.println("  " + c + "/36: " + cellHist[c]);
            }
        }
        System.out.println("floors with an empty cell: " + shortFloors + " of " + floors);
        System.out.println("floors failing the structure check (overlap, bad link, unreachable room, traps != 1, "
                + "blood != 1): " + badFloors);
        System.out.printf("variety: on average %.1f rooms of a floor were also on the floor before it%n",
                floors > 1 ? (double) shared / (floors - 1) : 0.0);
        System.out.println("distinct rooms used: " + used.size() + " of " + rooms.size()
                + ", warnings logged: " + (com.killer560.hub.util.ModLog.warnings - warnBefore));
        if (Boolean.getBoolean("usage")) {
            used.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                    .forEach(e -> System.out.println("  " + e.getValue() + "  " + e.getKey()));
        }
    }

    /** Name, minimum rooms, cell target - SimFloorGen.Floor, which the offline stub does not carry. */
    private static final Object[][] FLOORS = {
            {"E", 11, 19}, {"F1", 13, 22}, {"F2", 15, 26}, {"F3", 16, 27},
            {"F4", 19, 33}, {"F5", 21, 36}, {"F6", 19, 33}, {"F7", 21, 36}};

    private static int DROPPED;
    private static final Map<Integer, Integer> PUZZLE_DIFF = new TreeMap<>();
    private static final Map<String, Integer> PUZZLE_DOORS = new TreeMap<>();
    private static final Map<Integer, Integer> TRAP_DOORS = new TreeMap<>();

    private static final String[] FAILS = {"path length != slider", "fairy not on path", "wither doors != path",
            "cells short of target", "rooms < minimum", "blood != 1", "trap != 1", "fairy != 1",
            "puzzles != slider", "room unreachable by doors", "structure check", "puzzle with != 1 door",
            "trap with != 2 doors (info)"};

    private static void sweep(Map<String, RoomLibrary.Room> rooms, int perCombo, boolean noRecency)
            throws Exception {
        int offset = Integer.getInteger("bloodoffset", 0);
        Random rng = new Random(Long.getLong("seed", System.nanoTime()));
        System.out.println(rooms.size() + " usable room(s); " + perCombo + " floor(s) per floor x slider x puzzles"
                + " combination, passing slider + " + offset + ", recency " + (noRecency ? "OFF" : "ON"));
        Map<String, Integer> types = new TreeMap<>();
        for (String n : rooms.keySet()) {
            types.merge(SimFloorGen.typeOf(n) + ("L".equalsIgnoreCase(SimFloorGen.shapeOf(n)) ? "(L)" : ""), 1,
                    Integer::sum);
        }
        System.out.println("room types: " + types);
        int total = 0;
        int anyFail = 0;
        int[] fails = new int[FAILS.length];
        Map<String, int[]> bySlider = new TreeMap<>();
        Map<String, int[]> byFloor = new LinkedHashMap<>();
        Map<Integer, Integer> lengthHist = new TreeMap<>();
        java.util.Set<String> previous = new java.util.HashSet<>();
        long shared = 0;
        int pairs = 0;
        java.util.Set<String> distinct = new java.util.HashSet<>();
        int dumped = 0;
        int warnBefore = com.killer560.hub.util.ModLog.warnings;
        long t0 = System.nanoTime();
        for (Object[] fl : FLOORS) {
            String fname = (String) fl[0];
            int minRooms = (Integer) fl[1];
            int wantCells = Math.max(minRooms, Math.min((Integer) fl[2], 36));
            for (int k = 2; k <= 8; k++) {
                for (int puzzles = 2; puzzles <= 5; puzzles++) {
                    for (int n = 0; n < perCombo; n++) {
                        if (noRecency) {
                            clearRecency();
                        }
                        Map<String, RoomLibrary.Room> usable = capChampions(rooms, rng);
                        SimFloorLayout.Floor f;
                        String pinKind = System.getProperty("pin");
                        String pinRoom = System.getProperty("pinroom");
                        if (System.getProperty("only") != null && !System.getProperty("only").equals(fname)
                                || Integer.getInteger("slider", k) != k
                                || Integer.getInteger("puzzles", puzzles) != puzzles) {
                            continue;
                        }
                        if (pinRoom != null) {
                            Map<Integer, String> pinned = new HashMap<>();
                            pinned.put(Integer.getInteger("pincell", 14), pinRoom);
                            SimFloorLayout.PinnedFloor pf = SimFloorLayout.generate(usable, minRooms, wantCells,
                                    puzzles, k + offset, pinned, rng);
                            f = pf.floor();
                            if (!pf.unusedPins().isEmpty()) {
                                DROPPED++;
                                if (DROPPED <= Integer.getInteger("dumpdrop", 0)) {
                                    System.out.println("DROPPED " + pf.unusedPins());
                                    dump(f);
                                }
                            }
                        } else if (pinKind != null) {
                            // -Dpin=normal|fairy|blood: one room of that kind pinned at a random cell, as the
                            // Map Designer does when he places a room before pressing Generate.
                            List<String> names = new ArrayList<>();
                            for (String nm : usable.keySet()) {
                                if (SimFloorGen.typeOf(nm).equalsIgnoreCase(pinKind)
                                        && !"L".equalsIgnoreCase(SimFloorGen.shapeOf(nm))) {
                                    names.add(nm);
                                }
                            }
                            Map<Integer, String> pinned = new HashMap<>();
                            pinned.put(rng.nextInt(36), names.get(rng.nextInt(names.size())));
                            SimFloorLayout.PinnedFloor pf = SimFloorLayout.generate(usable, minRooms, wantCells,
                                    puzzles, k + offset, pinned, rng);
                            f = pf.floor();
                            if (!pf.unusedPins().isEmpty()) {
                                DROPPED++;
                            }
                        } else {
                            f = SimFloorLayout.generate(usable, minRooms, wantCells, puzzles, k + offset, rng);
                        }
                        boolean[] bad = judge(f, k, puzzles, minRooms, wantCells, lengthHist);
                        total++;
                        boolean any = false;
                        for (int i = 0; i < bad.length; i++) {
                            if (bad[i]) {
                                fails[i]++;
                                any |= !FAILS[i].endsWith("(info)");
                            }
                        }
                        anyFail += any ? 1 : 0;
                        int[] sl = bySlider.computeIfAbsent("slider " + k, x -> new int[3]);
                        sl[0]++;
                        sl[1] += bad[0] ? 1 : 0;
                        sl[2] += bad[1] ? 1 : 0;
                        int[] fr = byFloor.computeIfAbsent(fname, x -> new int[2]);
                        fr[0]++;
                        fr[1] += any ? 1 : 0;
                        if (any && dumped < Integer.getInteger("dump", 0)) {
                            dumped++;
                            StringBuilder why = new StringBuilder();
                            for (int i = 0; i < bad.length; i++) {
                                if (bad[i]) {
                                    why.append(FAILS[i]).append("; ");
                                }
                            }
                            System.out.println("FAILED " + fname + " slider " + k + " puzzles " + puzzles + ": "
                                    + why);
                            dump(f);
                        }
                        java.util.Set<String> now = new java.util.HashSet<>();
                        for (SimFloorLayout.Placement p : f.rooms()) {
                            String t = p.type().toUpperCase();
                            if (!t.equals("BLOOD") && !t.equals("ENTRANCE") && !t.equals("FAIRY")) {
                                now.add(p.name());
                            }
                            distinct.add(p.name());
                        }
                        if (fname.equals("F7") && !previous.isEmpty()) {
                            for (String r : now) {
                                shared += previous.contains(r) ? 1 : 0;
                            }
                            pairs++;
                        }
                        previous = fname.equals("F7") ? now : java.util.Set.of();
                    }
                }
            }
        }
        System.out.printf("%d floors, %.2f ms a floor, %d warnings logged%n", total,
                (System.nanoTime() - t0) / 1e6 / total, com.killer560.hub.util.ModLog.warnings - warnBefore);
        System.out.printf("floors failing ANY check: %d (%.2f%%)%n", anyFail, 100.0 * anyFail / total);
        System.out.println("floors that dropped the fixed pin: " + DROPPED);
        for (int i = 0; i < FAILS.length; i++) {
            System.out.printf("  %-28s %6d (%.2f%%)%n", FAILS[i], fails[i], 100.0 * fails[i] / total);
        }
        System.out.println("by slider (floors, path length wrong, fairy off path):");
        for (var e : bySlider.entrySet()) {
            System.out.printf("  %s: %d, %d, %d%n", e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]);
        }
        System.out.println("by floor (floors, any failure):");
        for (var e : byFloor.entrySet()) {
            System.out.printf("  %s: %d, %d%n", e.getKey(), e.getValue()[0], e.getValue()[1]);
        }
        System.out.println("path length minus slider -> floors (-99 = no path): " + lengthHist);
        System.out.println("puzzles placed minus slider -> floors: " + PUZZLE_DIFF);
        System.out.println("puzzle rooms with != 1 door (name:doors -> count): " + PUZZLE_DOORS);
        System.out.println("trap rooms by door count when != 2 (doors -> count): " + TRAP_DOORS);
        System.out.printf("variety: consecutive F7s share %.1f rooms; %d distinct rooms used of %d%n",
                pairs > 0 ? (double) shared / pairs : 0.0, distinct.size(), rooms.size());
    }

    /** Which of {@link #FAILS} this floor fails, judged through the doors the build would write. */
    private static boolean[] judge(SimFloorLayout.Floor f, int slider, int puzzles, int minRooms, int wantCells,
                                   Map<Integer, Integer> lengthHist) {
        boolean[] bad = new boolean[FAILS.length];
        int g = SimFloorLayout.GRID;
        int[] owner = new int[g * g];
        java.util.Arrays.fill(owner, -1);
        int entrance = -1;
        int blood = -1;
        int fairy = -1;
        int bloods = 0;
        int traps = 0;
        int fairies = 0;
        int puzzleRooms = 0;
        for (int i = 0; i < f.rooms().size(); i++) {
            SimFloorLayout.Placement p = f.rooms().get(i);
            String t = p.type().toUpperCase();
            switch (t) {
                case "ENTRANCE" -> entrance = i;
                case "BLOOD" -> {
                    blood = i;
                    bloods++;
                }
                case "FAIRY" -> {
                    fairy = i;
                    fairies++;
                }
                case "PUZZLE" -> puzzleRooms++;
                default -> {
                }
            }
            traps += "TRAP".equals(t) ? 1 : 0;
            for (int a = 0; a < p.cellsX(); a++) {
                for (int b = 0; b < p.cellsZ(); b++) {
                    owner[(p.originZ() + b) * g + p.originX() + a] = i;
                }
            }
        }
        bad[3] = SimFloorLayout.cellsOf(f) < wantCells;
        bad[4] = f.rooms().size() < minRooms;
        bad[5] = bloods != 1;
        bad[6] = traps != 1;
        bad[7] = fairies != 1;
        bad[8] = puzzleRooms != puzzles;
        PUZZLE_DIFF.merge(puzzleRooms - puzzles, 1, Integer::sum);
        bad[10] = check(f) != null;
        // The door graph, exactly as SimFloorGen.plan writes it: one door per link doorLinks keeps.
        List<SimFloorLayout.Link> doors = SimFloorLayout.doorLinks(f);
        List<List<int[]>> adj = new ArrayList<>();
        for (int i = 0; i < f.rooms().size(); i++) {
            adj.add(new ArrayList<>());
        }
        int big = 11;
        int[] cellRoom = new int[big * big];
        int[] cellDoor = new int[big * big];
        java.util.Arrays.fill(cellRoom, -1);
        String[] names = new String[f.rooms().size()];
        for (int i = 0; i < f.rooms().size(); i++) {
            SimFloorLayout.Placement p = f.rooms().get(i);
            names[i] = p.name();
            for (int x = p.originX() * 2; x <= (p.originX() + p.cellsX() - 1) * 2; x++) {
                for (int z = p.originZ() * 2; z <= (p.originZ() + p.cellsZ() - 1) * 2; z++) {
                    cellRoom[z * big + x] = i;
                }
            }
        }
        for (SimFloorLayout.Link l : doors) {
            int a = owner[l.aZ() * g + l.aX()];
            int b = owner[l.bZ() * g + l.bX()];
            int between = (l.aZ() + l.bZ()) * big + (l.aX() + l.bX());
            if (a < 0 || b < 0 || a == b || cellDoor[between] != 0) {
                continue;
            }
            cellDoor[between] = a == blood || b == blood ? 3 : a == entrance || b == entrance ? 4 : 1;
            adj.get(a).add(new int[]{b, between});
            adj.get(b).add(new int[]{a, between});
        }
        int[] from = new int[f.rooms().size()];
        int[] via = new int[f.rooms().size()];
        java.util.Arrays.fill(from, -2);
        int reached = 0;
        if (entrance >= 0) {
            from[entrance] = -1;
            java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>(List.of(entrance));
            while (!q.isEmpty()) {
                int r = q.poll();
                reached++;
                for (int[] e : adj.get(r)) {
                    if (from[e[0]] == -2) {
                        from[e[0]] = r;
                        via[e[0]] = e[1];
                        q.add(e[0]);
                    }
                }
            }
        }
        bad[9] = reached != f.rooms().size();
        // Doors per room, counted from the same door graph: a puzzle on Hypixel has exactly one.
        int[] doorCount = new int[f.rooms().size()];
        for (int i = 0; i < f.rooms().size(); i++) {
            doorCount[i] = adj.get(i).size();
        }
        for (int i = 0; i < f.rooms().size(); i++) {
            String t = f.rooms().get(i).type().toUpperCase();
            if ("PUZZLE".equals(t) && doorCount[i] != 1) {
                bad[11] = true;
                PUZZLE_DOORS.merge(f.rooms().get(i).name() + ":" + doorCount[i], 1, Integer::sum);
            }
            if ("TRAP".equals(t) && doorCount[i] != 2) {
                bad[12] = true;
                TRAP_DOORS.merge(doorCount[i], 1, Integer::sum);
            }
        }
        boolean[] expectWither = new boolean[big * big];
        if (blood >= 0 && from[blood] != -2) {
            int count = 0;
            boolean fairyOn = false;
            for (int r = blood; r >= 0; r = from[r]) {
                if (r != blood && r != entrance && r != fairy) {
                    count++;
                }
                fairyOn |= r == fairy;
                if (from[r] >= 0 && cellDoor[via[r]] == 1) {
                    expectWither[via[r]] = true;
                }
            }
            bad[0] = count != slider;
            bad[1] = !fairyOn;
            lengthHist.merge(count - slider, 1, Integer::sum);
        } else {
            bad[0] = true;
            bad[1] = true;
            lengthHist.merge(-99, 1, Integer::sum);
        }
        boolean[] wither = com.killer560.hub.roomsim.SimWitherDoors.compute(cellRoom, cellDoor, names);
        bad[2] = !java.util.Arrays.equals(wither, expectWither);
        return bad;
    }

    /** What is wrong with a floor's structure, or null. */
    private static String check(SimFloorLayout.Floor f) {
        int g = SimFloorLayout.GRID;
        int[] owner = new int[g * g];
        java.util.Arrays.fill(owner, -1);
        int traps = 0;
        int blood = 0;
        int entrance = -1;
        for (int i = 0; i < f.rooms().size(); i++) {
            SimFloorLayout.Placement p = f.rooms().get(i);
            traps += "TRAP".equalsIgnoreCase(p.type()) ? 1 : 0;
            blood += "BLOOD".equalsIgnoreCase(p.type()) ? 1 : 0;
            if ("ENTRANCE".equalsIgnoreCase(p.type())) {
                entrance = i;
            }
            for (int a = 0; a < p.cellsX(); a++) {
                for (int b = 0; b < p.cellsZ(); b++) {
                    int c = (p.originZ() + b) * g + p.originX() + a;
                    if (owner[c] >= 0) {
                        return "rooms " + owner[c] + " and " + i + " overlap";
                    }
                    owner[c] = i;
                }
            }
        }
        if (traps != 1 || blood != 1 || entrance < 0) {
            return "traps " + traps + ", blood " + blood + ", entrance " + entrance;
        }
        Map<Integer, List<Integer>> adj = new HashMap<>();
        for (SimFloorLayout.Link l : f.links()) {
            if (Math.abs(l.aX() - l.bX()) + Math.abs(l.aZ() - l.bZ()) != 1) {
                return "link not between neighbours " + l;
            }
            int a = owner[l.aZ() * g + l.aX()];
            int b = owner[l.bZ() * g + l.bX()];
            if (a < 0 || b < 0 || a == b) {
                return "link into an empty cell or inside one room " + l;
            }
            adj.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
            adj.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
        }
        java.util.Set<Integer> seen = new java.util.HashSet<>(List.of(entrance));
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>(List.of(entrance));
        while (!q.isEmpty()) {
            for (int n : adj.getOrDefault(q.poll(), List.of())) {
                if (seen.add(n)) {
                    q.add(n);
                }
            }
        }
        return seen.size() == f.rooms().size() ? null
                : (f.rooms().size() - seen.size()) + " room(s) unreachable from the entrance";
    }

    /** The floor as a grid: room index per cell, '-' between linked cells, 'o' for an open (bricked) doorway. */
    private static void dump(SimFloorLayout.Floor f) {
        int g = SimFloorLayout.GRID;
        char[][] out = new char[g * 3][g * 5];
        for (char[] row : out) {
            java.util.Arrays.fill(row, ' ');
        }
        int[] owner = new int[g * g];
        java.util.Arrays.fill(owner, -1);
        for (int i = 0; i < f.rooms().size(); i++) {
            SimFloorLayout.Placement p = f.rooms().get(i);
            for (int a = 0; a < p.cellsX(); a++) {
                for (int b = 0; b < p.cellsZ(); b++) {
                    owner[(p.originZ() + b) * g + p.originX() + a] = i;
                }
            }
        }
        for (int c = 0; c < g * g; c++) {
            String s = owner[c] < 0 ? " .." : String.format("%3d", owner[c]);
            for (int k = 0; k < 3; k++) {
                out[(c / g) * 3 + 1][(c % g) * 5 + 1 + k] = s.charAt(k);
            }
        }
        for (SimFloorLayout.Link l : f.links()) {
            int r = l.aZ() * 3 + 1 + (l.bZ() - l.aZ()) * 1;
            int col = l.aX() * 5 + 2 + (l.bX() - l.aX()) * 2;
            out[r][col] = l.bX() != l.aX() ? '-' : '|';
        }
        for (int[] o : f.openDoors()) {
            int dx = new int[]{0, 1, 0, -1}[o[2]];
            int dz = new int[]{-1, 0, 1, 0}[o[2]];
            out[o[1] * 3 + 1 + dz][o[0] * 5 + 2 + dx * 2] = 'o';
        }
        for (char[] row : out) {
            System.out.println(new String(row));
        }
        for (int i = 0; i < f.rooms().size(); i++) {
            SimFloorLayout.Placement p = f.rooms().get(i);
            System.out.print(i + "=" + p.name() + "(" + p.type().charAt(0) + p.cellsX() + "x" + p.cellsZ()
                    + " d" + p.depth() + ") ");
        }
        System.out.println();
    }

    private static void clearRecency() throws Exception {
        var f = SimFloorLayout.class.getDeclaredField("RECENT");
        f.setAccessible(true);
        ((Map<?, ?>) f.get(null)).clear();
    }

    /** SimFloorGen.capChampions: one CHAMPION room per floor. */
    static Map<String, RoomLibrary.Room> capChampions(Map<String, RoomLibrary.Room> usable, Random rng) {
        List<String> champs = new ArrayList<>();
        for (String n : usable.keySet()) {
            if ("CHAMPION".equalsIgnoreCase(SimFloorGen.typeOf(n))) {
                champs.add(n);
            }
        }
        Map<String, RoomLibrary.Room> out = new LinkedHashMap<>(usable);
        if (champs.size() > 1) {
            String keep = champs.get(rng.nextInt(champs.size()));
            for (String n : champs) {
                if (!n.equals(keep)) {
                    out.remove(n);
                }
            }
        }
        return out;
    }

    /** RoomLibrary.fromJson plus Room.usable(), minus the tile audit (pass its refusals as -Dcorrupt). */
    static RoomLibrary.Room read(Path f) {
        try {
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            int minY = o.has("minY") ? o.get("minY").getAsInt() : 60;
            int maxY = o.has("maxY") ? o.get("maxY").getAsInt() : 140;
            int sx = o.get("sizeX").getAsInt();
            int sz = o.get("sizeZ").getAsInt();
            RoomLibrary.Room r = new RoomLibrary.Room(o.get("name").getAsString(), sx, sz, minY, maxY);
            for (var p : o.getAsJsonArray("palette")) {
                r.palette.add(p.getAsString());
            }
            if (o.has("blocksZ")) {
                try (var in = new java.io.DataInputStream(new java.util.zip.GZIPInputStream(
                        new java.io.ByteArrayInputStream(java.util.Base64.getDecoder()
                                .decode(o.get("blocksZ").getAsString()))))) {
                    for (int i = 0; i < r.blocks.length; i++) {
                        r.blocks[i] = in.readShort();
                    }
                } catch (java.io.EOFException ignored) {
                    // shorter than expected: the rest stays unread, as in the game
                }
            } else if (o.has("blocks")) {
                JsonArray b = o.getAsJsonArray("blocks");
                for (int i = 0; i < b.size() && i < r.blocks.length; i++) {
                    r.blocks[i] = b.get(i).getAsShort();
                }
            }
            r.margin = o.has("margin") ? o.get("margin").getAsInt() : (sx % 31 == 2 ? 1 : 0);
            boolean[] seen = new boolean[sx * sz];
            if (o.has("seenZ")) {
                byte[] packed = java.util.Base64.getDecoder().decode(o.get("seenZ").getAsString());
                for (int i = 0; i < seen.length && (i >> 3) < packed.length; i++) {
                    seen[i] = (packed[i >> 3] & (1 << (i & 7))) != 0;
                }
            } else if (o.has("seenColumn")) {
                JsonArray s = o.getAsJsonArray("seenColumn");
                for (int i = 0; i < s.size() && i < seen.length; i++) {
                    seen[i] = s.get(i).getAsBoolean();
                }
            }
            int n = 0;
            for (boolean b : seen) {
                n += b ? 1 : 0;
            }
            boolean format = r.margin == 1 && sx >= 33 && (sx - 1) % 32 == 0 && sz >= 33 && (sz - 1) % 32 == 0;
            r.usable = format && seen.length > 0 && (double) n / seen.length >= 0.999;
            return r;
        } catch (Exception e) {
            return null;
        }
    }
}

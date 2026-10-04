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
            SimFloorLayout.Floor f = SimFloorLayout.generate(usable, 21, 36, 3, 9, rng);
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
    private static Map<String, RoomLibrary.Room> capChampions(Map<String, RoomLibrary.Room> usable, Random rng) {
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
    private static RoomLibrary.Room read(Path f) {
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

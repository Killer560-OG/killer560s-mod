package com.killer560.hub.bloodrush;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.autoroutes.RouteCoords;
import com.killer560.hub.util.ModLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shared blood rush table: every room's boxes (room-relative corners, the box NUMBER is the place in the room's list)
 * and the role plans per entry door and team size, so a player on this mod and one on a mod that uses the same table get
 * the same boxes and the same split (killer560, 2026-11-16). The ONLY source of spawn boxes in the mod (brboxes-only-1117:
 * the mod's own learning is gone): Blood Rush Helper, the Dungeon Sim's starred mobs and Auto Clear ask this class
 * ({@link #boxes}, {@link #worldBoxes}). Per room the shared table wins; only a room it has no boxes for falls back to the jar's
 * static {@code fallback-boxes.json} (never mixed inside one room, {@link #isShared}); a room in neither has none. The table is a
 * versioned snapshot in the jar ({@code br-data.json}, built by tools/bloodrush/build-br-data.py). Nothing here contacts a server.
 *
 * <p>TODO(shared api): when the shared service is ready, {@link #load()} is the one method that changes: fill {@link Data}
 * from it (snapshot as the offline fallback) and nothing else in the mod needs touching.
 */
public final class BloodRushData {

    private static final Logger LOGGER = ModLog.get("killer560smod-bloodrush");
    static final String SNAPSHOT = "/assets/killer560smod/bloodrush/br-data.json";
    static final String FALLBACK = "/assets/killer560smod/bloodrush/fallback-boxes.json";
    /** An entry door counts as the plan's door within this many blocks (Manhattan), else the room has no plan for it. */
    private static final int DOOR_REACH = 3;

    /** Each role's boxes (by box number, 1 up) in kill order, and each role's stack. */
    record Plan(int[][] roles, int[][] stacks) {
    }

    /** One entry door (and for the door runner's plans the wither door) with its plans by team size. */
    private record Door(int x, int z, int wx, int wz, Map<Integer, Plan> byCount) {
    }

    /** The whole table. */
    private record Data(String version, Map<String, int[][]> boxes, Map<String, List<Door>> normal, Map<String, List<Door>> master,
                        Map<String, List<Door>> doorer, Map<String, List<Door>> doorerMaster, Set<String> mini, Map<String, int[][]> fallback) {
    }

    private static Data data;

    private BloodRushData() {
    }

    private static synchronized Data data() {
        if (data == null) {
            data = load();
        }
        return data;
    }

    /** The one place the table comes from (see the class note). */
    private static Data load() {
        try (InputStream in = BloodRushData.class.getResourceAsStream(SNAPSHOT)) {
            JsonObject root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, int[][]> boxes = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("b").entrySet()) {
                boxes.put(e.getKey(), ints(e.getValue().getAsJsonArray()));
            }
            Set<String> mini = new HashSet<>();
            root.getAsJsonArray("mini").forEach(x -> mini.add(x.getAsString()));
            return new Data(root.get("v").getAsString(), boxes, plans(root.getAsJsonObject("p"), false),
                    plans(root.getAsJsonObject("m"), false), plans(root.getAsJsonObject("d"), true),
                    plans(root.getAsJsonObject("dm"), true), mini, fallback());
        } catch (Exception e) {
            LOGGER.error("[BloodRush] box and role table unreadable, no boxes anywhere", e);
            return new Data("none", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), Map.of());
        }
    }

    /** Our own static boxes for rooms the shared table lacks ({@code fallback-boxes.json}, built once from the spots shipped before 2026-11-17). */
    private static Map<String, int[][]> fallback() {
        Map<String, int[][]> out = new HashMap<>();
        try (InputStream in = BloodRushData.class.getResourceAsStream(FALLBACK)) {
            for (Map.Entry<String, JsonElement> e : JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("b").entrySet()) {
                out.put(key(e.getKey()), ints(e.getValue().getAsJsonArray()));
            }
        } catch (Exception e) {
            LOGGER.error("[BloodRush] fallback boxes unreadable", e);
        }
        return out;
    }

    private static int[][] ints(JsonArray rows) {
        int[][] out = new int[rows.size()][];
        for (int i = 0; i < out.length; i++) {
            JsonArray r = rows.get(i).getAsJsonArray();
            out[i] = new int[r.size()];
            for (int j = 0; j < r.size(); j++) {
                out[i][j] = r.get(j).getAsInt();
            }
        }
        return out;
    }

    private static Map<String, List<Door>> plans(JsonObject table, boolean withWither) {
        Map<String, List<Door>> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> room : table.entrySet()) {
            List<Door> doors = new ArrayList<>();
            for (Map.Entry<String, JsonElement> d : room.getValue().getAsJsonObject().entrySet()) {
                String[] parts = d.getKey().split(">");
                String[] a = parts[0].split(",");
                String[] w = withWither ? parts[1].split(",") : new String[]{"0", "0"};
                Map<Integer, Plan> byCount = new HashMap<>();
                for (Map.Entry<String, JsonElement> c : d.getValue().getAsJsonObject().entrySet()) {
                    JsonArray p = c.getValue().getAsJsonArray();
                    byCount.put(Integer.parseInt(c.getKey()), new Plan(ints(p.get(0).getAsJsonArray()),
                            p.size() > 1 ? ints(p.get(1).getAsJsonArray()) : new int[p.get(0).getAsJsonArray().size()][0]));
                }
                doors.add(new Door(Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(w[0]), Integer.parseInt(w[1]), byCount));
            }
            out.put(room.getKey(), doors);
        }
        return out;
    }

    private static String key(String room) {
        return room == null ? "" : room.replace('-', ' ');
    }

    /** The snapshot's date, for logs and tests. */
    static String version() {
        return data().version;
    }

    /** The room's boxes (x1,y1,z1,x2,y2,z2 in room coordinates, box number = index + 1), the shared table's, else the jar's fallback for a room it lacks, else null. */
    public static int[][] boxes(String room) {
        Data d = data();
        int[][] b = d.boxes.get(key(room));
        return b != null ? b : d.fallback.get(key(room));
    }

    /** True when the room's boxes and plans are the shared table's; false for a fallback room or a room with none. */
    public static boolean isShared(String room) {
        return data().boxes.containsKey(key(room));
    }

    /** The room's boxes in the world for its frame (a box spans its two corner blocks), in table order; empty if the table has none. */
    public static List<AABB> worldBoxes(String room, RouteCoords.Frame frame) {
        int[][] table = boxes(room);
        List<AABB> out = new ArrayList<>();
        for (int[] t : table == null || frame == null ? new int[0][] : table) {
            BlockPos a = RouteCoords.toRealBlock(frame, new BlockPos(t[0], t[1], t[2]));
            BlockPos b = RouteCoords.toRealBlock(frame, new BlockPos(t[3], t[4], t[5]));
            out.add(new AABB(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                    Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1));
        }
        return out;
    }

    /** A miniboss room: every role kills every box there, from any door. */
    static boolean isMini(String room) {
        return data().mini.contains(key(room));
    }

    /** Test hook: gives {@code room} these boxes as our own fallback ones (and takes it out of the shared table), until {@link #resetForTest}. */
    public static void putForTest(String room, int[][] boxes) {
        Data d = data();
        d.boxes.remove(key(room));
        d.fallback.put(key(room), boxes);
    }

    /** Test hook: adds fallback boxes for {@code room} WITHOUT touching the shared table, to prove the table still wins. */
    public static void putFallbackForTest(String room, int[][] boxes) {
        data().fallback.put(key(room), boxes);
    }

    /** Test hook: forgets {@link #putForTest} changes by loading the table again. */
    public static synchronized void resetForTest() {
        data = null;
    }

    /** Test hook: where a room's boxes come from, "shared", "fallback" or "none". */
    public static String sourceForTest(String room) {
        return isShared(room) ? "shared" : boxes(room) != null ? "fallback" : "none";
    }

    /** Test hook: each room with boxes (shared or fallback) and its box count. */
    public static Map<String, Integer> countsForTest() {
        Map<String, Integer> out = new HashMap<>();
        data().fallback.forEach((k, v) -> out.put(k, v.length));
        data().boxes.forEach((k, v) -> out.put(k, v.length));
        return out;
    }

    /** Room names the table covers (tests). */
    static Set<String> rooms() {
        return data().boxes.keySet();
    }

    /**
     * The plan for a room entered by the door at room coordinates (dx, dz) for {@code n} killing: the nearest door the table
     * has, if within {@link #DOOR_REACH}; with the door runner killing ({@code doorer}) the plan for that door and the room's
     * wither door {@code wither} (null: unknown, no plan). In master mode the master plans first, else the normal ones.
     */
    static Plan plan(String room, int dx, int dz, int[] wither, int n, boolean master, boolean doorer) {
        Data d = data();
        String r = key(room);
        if (doorer) {
            if (wither == null) {
                return null;
            }
            Plan p = master ? nearest(d.doorerMaster.get(r), dx, dz, wither, n) : null;
            return p != null ? p : nearest(d.doorer.get(r), dx, dz, wither, n);
        }
        Plan p = master ? nearest(d.master.get(r), dx, dz, null, n) : null;
        return p != null ? p : nearest(d.normal.get(r), dx, dz, null, n);
    }

    private static Plan nearest(List<Door> doors, int dx, int dz, int[] wither, int n) {
        if (doors == null) {
            return null;
        }
        Door best = null;
        int bestD = Integer.MAX_VALUE;
        int bestEntry = 0;
        int bestWither = 0;
        for (Door d : doors) {
            int entry = Math.abs(d.x - dx) + Math.abs(d.z - dz);
            int w = wither == null ? 0 : Math.abs(d.wx - wither[0]) + Math.abs(d.wz - wither[1]);
            if (entry + w < bestD) {
                bestD = entry + w;
                best = d;
                bestEntry = entry;
                bestWither = w;
            }
        }
        return best == null || bestEntry > DOOR_REACH || bestWither > DOOR_REACH ? null : best.byCount.get(n);
    }

    /** What a box is to the player in a plan: {@code MINE} with its order, {@code STACK}, {@code THEIRS} (role, 0 = only in
     *  other roles' stacks) or {@code NONE} (the plan leaves it to nobody). */
    enum Kind { MINE, STACK, THEIRS, NONE }

    record Look(Kind kind, int n) {
    }

    private static final Look NONE = new Look(Kind.NONE, 0);

    /** Box {@code number} in {@code plan} for the role {@code playing} (null: none), exactly as the shared rule reads it. */
    static Look look(Plan plan, int number, Integer playing) {
        if (playing != null) {
            int at = indexOf(plan.roles, playing - 1, number);
            if (at >= 0) {
                return new Look(Kind.MINE, at + 1);
            }
            if (indexOf(plan.stacks, playing - 1, number) >= 0) {
                return new Look(Kind.STACK, 0);
            }
        }
        for (int i = 0; i < plan.roles.length; i++) {
            if (indexOf(plan.roles, i, number) >= 0) {
                return new Look(Kind.THEIRS, i + 1);
            }
        }
        for (int i = 0; i < plan.stacks.length; i++) {
            if (indexOf(plan.stacks, i, number) >= 0) {
                return new Look(Kind.THEIRS, 0);
            }
        }
        return NONE;
    }

    private static int indexOf(int[][] lists, int role, int number) {
        if (role < 0 || role >= lists.length) {
            return -1;
        }
        for (int i = 0; i < lists[role].length; i++) {
            if (lists[role][i] == number) {
                return i;
            }
        }
        return -1;
    }
}

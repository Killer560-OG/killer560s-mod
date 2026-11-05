package com.killer560.hub.bloodrush;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.autoroutes.RouteCoords;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where each room's starred mobs spawn, learned from his own runs: the block a starred mob stood on when the client
 * first saw it with nobody near it, kept per room in the room's own coordinates ({@link RouteCoords#toRelativeBlock},
 * clay corner and rotation from the live map), so a spot comes back at every rotation and in any later run. The same
 * spot seen again (within {@link #MERGE} blocks) only counts up.
 *
 * <p>Our own data, not the reference mod adm's: adm's boxes come from its site and are All Rights Reserved. Learned only on
 * Hypixel, never in the Dungeon Sim (whose mobs are placed by the sim, from this very file among others). The Dungeon
 * Sim's Spawn Starred Mobs places a room's mobs on these spots ({@code roomsim.SimLiveMobs}).
 *
 * <p>Stored in {@code killer560smod-bloodrush-spots.json}: {@code {"version":1,"rooms":{"Name":[[x,y,z,seen],...]}}}.
 *
 * <p>The jar also ships a table of spots ({@link #BUNDLED}, the same format), built by
 * {@code tools/bloodrush/build-spots.py} from the spots learned on his own Hypixel runs, so a room shows boxes before
 * its mobs are in view in a run that never saw the room (bloodrush2-1104, killer560 2026-11-03: "the blood rush helper
 * boxes arent really loading in properly or at all"). A room's spots are its learned ones plus each shipped one no
 * learned spot is within {@link #MERGE} of. There is no public table of starred mob spawns (adm's is All Rights
 * Reserved), so the shipped table only covers rooms someone running this mod has learned.
 * Client thread only.
 */
public final class BloodRushSpots {

    private static final Logger LOGGER = ModLog.get("killer560smod-bloodrush");
    public static final String FILE_NAME = "killer560smod-bloodrush-spots.json";
    private static final Path FILE = ModPaths.config(FILE_NAME);
    /** A spot this close (each axis, blocks) to a known one is that one seen again. */
    static final int MERGE = 2;
    /** At most this many spots a room: a bigger room has about 20 starred mobs. */
    static final int MAX_PER_ROOM = 48;
    private static final long SAVE_EVERY_MS = 10_000L;
    /** The shipped table, inside the jar. */
    static final String BUNDLED = "/assets/killer560smod/bloodrush/spawn-spots.json";

    private static Map<String, List<int[]>> bundled;

    private static Map<String, List<int[]>> rooms;
    private static boolean dirty;
    /** Bumped whenever any room's spot positions change, so a cache of world boxes can key on it. */
    private static int version;
    private static long lastSave;

    private BloodRushSpots() {
    }

    private static Map<String, List<int[]>> rooms() {
        if (rooms == null) {
            rooms = read();
        }
        return rooms;
    }

    private static Map<String, List<int[]>> bundled() {
        if (bundled == null) {
            bundled = new HashMap<>();
            try (java.io.InputStream in = BloodRushSpots.class.getResourceAsStream(BUNDLED)) {
                if (in != null) {
                    bundled = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } else {
                    LOGGER.warn("[BloodRush] no shipped spawn spot table in the jar ({})", BUNDLED);
                }
            } catch (Exception e) {
                LOGGER.warn("[BloodRush] shipped spawn spot table unreadable: {}", e.toString());
            }
        }
        return bundled;
    }

    /**
     * A room's spots, room-relative {x, y, z, seen}: the learned ones, then each shipped one no learned spot is within
     * {@link #MERGE} of; empty when none. A copy.
     */
    public static List<int[]> spots(String room) {
        List<int[]> out = new ArrayList<>();
        if (room == null) {
            return out;
        }
        List<int[]> got = rooms().get(room);
        if (got != null) {
            for (int[] s : got) {
                out.add(s.clone());
            }
        }
        List<int[]> shipped = bundled().get(room);
        if (shipped != null) {
            int learned = out.size();
            for (int[] s : shipped) {
                boolean known = false;
                for (int i = 0; i < learned && !known; i++) {
                    int[] l = out.get(i);
                    known = Math.abs(l[0] - s[0]) <= MERGE && Math.abs(l[1] - s[1]) <= MERGE
                            && Math.abs(l[2] - s[2]) <= MERGE;
                }
                if (!known) {
                    out.add(s.clone());
                }
            }
        }
        return out;
    }

    static int version() {
        return version;
    }

    /** Every room the shipped table has spots for, with its count, for the tab and tests. */
    public static Map<String, Integer> bundledCounts() {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, List<int[]>> e : bundled().entrySet()) {
            if (!e.getValue().isEmpty()) {
                out.put(e.getKey(), e.getValue().size());
            }
        }
        return out;
    }

    /** Test hook: sets a room's shipped spots (room-relative {x, y, z}) in memory, replacing what the jar had. */
    public static void putBundledForTest(String room, List<int[]> spots) {
        List<int[]> list = new ArrayList<>();
        for (int[] s : spots) {
            list.add(new int[]{s[0], s[1], s[2], 1});
        }
        bundled().put(room, list);
        version++;
    }

    /** A room's learned spots as world blocks for the room's frame (rotation and clay corner). */
    public static List<BlockPos> worldSpots(String room, RouteCoords.Frame frame) {
        List<BlockPos> out = new ArrayList<>();
        if (frame == null) {
            return out;
        }
        for (int[] s : spots(room)) {
            out.add(RouteCoords.toRealBlock(frame, new BlockPos(s[0], s[1], s[2])));
        }
        return out;
    }

    /** Remembers a starred mob's spawn block in {@code room}. True if the spot is new. */
    static boolean learn(String room, RouteCoords.Frame frame, BlockPos feet) {
        if (room == null || frame == null || feet == null) {
            return false;
        }
        BlockPos rel = RouteCoords.toRelativeBlock(frame, feet);
        List<int[]> list = rooms().computeIfAbsent(room, k -> new ArrayList<>());
        for (int[] s : list) {
            if (Math.abs(s[0] - rel.getX()) <= MERGE && Math.abs(s[1] - rel.getY()) <= MERGE
                    && Math.abs(s[2] - rel.getZ()) <= MERGE) {
                s[3]++;
                dirty = true;
                return false;
            }
        }
        if (list.size() >= MAX_PER_ROOM) {
            return false;
        }
        list.add(new int[]{rel.getX(), rel.getY(), rel.getZ(), 1});
        dirty = true;
        version++;
        LOGGER.info("[BloodRush] learned a spawn spot in {} at room {},{},{} ({} now)", room, rel.getX(), rel.getY(),
                rel.getZ(), list.size());
        return true;
    }

    /** Every room that has spots, with its count, for the tab and tests. */
    public static Map<String, Integer> counts() {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, List<int[]>> e : rooms().entrySet()) {
            if (!e.getValue().isEmpty()) {
                out.put(e.getKey(), e.getValue().size());
            }
        }
        return out;
    }

    /** Forgets every learned spot and deletes the file. */
    public static void forgetAll() {
        rooms().clear();
        dirty = false;
        version++;
        try {
            Files.deleteIfExists(FILE);
        } catch (Exception e) {
            LOGGER.warn("[BloodRush] could not delete {}: {}", FILE.getFileName(), e.toString());
        }
    }

    /** Test hook: sets a room's spots (room-relative {x, y, z}), replacing what it had. Not saved. */
    public static void putForTest(String room, List<int[]> spots) {
        List<int[]> list = new ArrayList<>();
        for (int[] s : spots) {
            list.add(new int[]{s[0], s[1], s[2], s.length > 3 ? s[3] : 1});
        }
        rooms().put(room, list);
        version++;
    }

    /**
     * Test hook: forgets every learned spot in memory (the file is left alone), or re-reads it with {@code reload}; the
     * shipped table is read from the jar again either way.
     */
    public static void resetForTest(boolean reload) {
        rooms = reload ? read() : new HashMap<>();
        bundled = null;
        dirty = false;
        version++;
    }

    /** Saves if something was learned and the last save is old enough (or {@code now}). */
    static void saveIfDirty(boolean now) {
        long t = System.currentTimeMillis();
        if (!dirty || (!now && t - lastSave < SAVE_EVERY_MS)) {
            return;
        }
        lastSave = t;
        dirty = false;
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject rs = new JsonObject();
        for (Map.Entry<String, List<int[]>> e : rooms().entrySet()) {
            JsonArray a = new JsonArray();
            for (int[] s : e.getValue()) {
                JsonArray p = new JsonArray();
                for (int v : s) {
                    p.add(v);
                }
                a.add(p);
            }
            rs.add(e.getKey(), a);
        }
        root.add("rooms", rs);
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[BloodRush] could not save {}: {}", FILE.getFileName(), e.toString());
        }
    }

    private static Map<String, List<int[]>> read() {
        if (!Files.exists(FILE)) {
            return new HashMap<>();
        }
        try {
            return parse(Files.readString(FILE, StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOGGER.warn("[BloodRush] {} unreadable, starting empty: {}", FILE.getFileName(), e.toString());
            return new HashMap<>();
        }
    }

    private static Map<String, List<int[]>> parse(String json) {
        Map<String, List<int[]>> out = new HashMap<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject rs = root.getAsJsonObject("rooms");
            if (rs == null) {
                return out;
            }
            for (Map.Entry<String, JsonElement> e : rs.entrySet()) {
                if (!e.getValue().isJsonArray() || e.getKey().isEmpty() || e.getKey().length() > 64) {
                    continue;
                }
                List<int[]> list = new ArrayList<>();
                for (JsonElement p : e.getValue().getAsJsonArray()) {
                    if (!p.isJsonArray() || p.getAsJsonArray().size() < 3 || list.size() >= MAX_PER_ROOM) {
                        continue;
                    }
                    JsonArray a = p.getAsJsonArray();
                    list.add(new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(),
                            a.size() > 3 ? a.get(3).getAsInt() : 1});
                }
                out.put(e.getKey(), list);
            }
        } catch (Exception e) {
            LOGGER.warn("[BloodRush] spawn spot table unreadable: {}", e.toString());
        }
        return out;
    }
}

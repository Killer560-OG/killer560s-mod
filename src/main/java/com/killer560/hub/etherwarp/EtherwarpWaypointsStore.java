package com.killer560.hub.etherwarp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Saved etherwarp waypoints, in their own file ({@code killer560smod-etherwarp-waypoints.json}) separate
 * from {@link EtherwarpWaypointsConfig}'s settings - same split {@code RouteStore}/{@code WaypointRoutesConfig}
 * use for Waypoint Routes.
 * <p>
 * Persisted across restarts (unlike the old per-run-only list this replaces) because every position here is
 * room-relative (see {@link EtherwarpWaypoint}'s doc) rather than an absolute world coordinate - the exact
 * change killer560 asked for ("Make sure they dont save based off of location but off of location in a
 * room") is also what makes keeping them around safe: a spot marked in "Fire Room" this run is still the
 * same spot in "Fire Room" next run, however that copy of the room happens to be rotated or placed.
 * <p>
 * Every mutation is followed by {@link #save()} from the caller, same discipline as {@code RouteStore}.
 */
public final class EtherwarpWaypointsStore {

    private static final Logger LOGGER = ModLog.get("killer560smod-etherwarp");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-etherwarp-waypoints.json");

    private static List<EtherwarpWaypoint> waypoints;

    private EtherwarpWaypointsStore() {
    }

    public static List<EtherwarpWaypoint> all() {
        if (waypoints == null) {
            load();
        }
        return waypoints;
    }

    /** Every waypoint saved for this room template, in placement order (the order {@link #nextOrder} and the
     *  on-screen numbering both rely on). */
    public static List<EtherwarpWaypoint> forRoom(String roomName) {
        List<EtherwarpWaypoint> out = new ArrayList<>();
        if (roomName == null) {
            return out;
        }
        for (EtherwarpWaypoint w : all()) {
            if (roomName.equals(w.roomName)) {
                out.add(w);
            }
        }
        out.sort((a, b) -> Integer.compare(a.order, b.order));
        return out;
    }

    /** The next placement-order number for a new waypoint in this room - one past the highest currently
     *  saved, so a gap left by a removed waypoint is never reused and numbers only ever grow. */
    public static int nextOrder(String roomName) {
        int max = 0;
        for (EtherwarpWaypoint w : forRoom(roomName)) {
            max = Math.max(max, w.order);
        }
        return max + 1;
    }

    public static void add(EtherwarpWaypoint waypoint) {
        all().add(waypoint);
    }

    public static void remove(String id) {
        all().removeIf(w -> w.id.equals(id));
    }

    /** killer560, 2026-09-27: "If i do clear it should only clear the ones in the room I am in." @return how
     *  many were removed. */
    public static int clearRoom(String roomName) {
        if (roomName == null) {
            return 0;
        }
        int before = all().size();
        all().removeIf(w -> roomName.equals(w.roomName));
        return before - all().size();
    }

    public static void load() {
        waypoints = new ArrayList<>();
        if (!Files.exists(PATH)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("waypoints")) {
                return;
            }
            for (JsonElement el : root.getAsJsonArray("waypoints")) {
                JsonObject obj = el.getAsJsonObject();
                waypoints.add(new EtherwarpWaypoint(
                        str(obj, "id", null),
                        str(obj, "name", "Waypoint"),
                        str(obj, "roomName", null),
                        obj.get("relX").getAsInt(),
                        obj.get("relY").getAsInt(),
                        obj.get("relZ").getAsInt(),
                        obj.has("order") ? obj.get("order").getAsInt() : 1));
            }
        } catch (Exception e) {
            // Same rule RouteStore follows: every later mutation calls save(), which would silently overwrite
            // an unreadable file with only what parsed so far - back the original up first.
            LOGGER.warn("[Etherwarp] Failed to load waypoints file ({} waypoint(s) recovered), backing it up",
                    waypoints.size(), e);
            try {
                Files.copy(PATH, PATH.resolveSibling("killer560smod-etherwarp-waypoints.broken-"
                        + System.currentTimeMillis() + ".json"));
            } catch (Exception backupError) {
                LOGGER.warn("[Etherwarp] Could not back up the unreadable waypoints file", backupError);
            }
        }
    }

    public static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            JsonArray arr = new JsonArray();
            for (EtherwarpWaypoint w : all()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", w.id);
                obj.addProperty("name", w.name);
                obj.addProperty("roomName", w.roomName);
                obj.addProperty("relX", w.relX);
                obj.addProperty("relY", w.relY);
                obj.addProperty("relZ", w.relZ);
                obj.addProperty("order", w.order);
                arr.add(obj);
            }
            JsonObject root = new JsonObject();
            root.add("waypoints", arr);
            Files.writeString(PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[Etherwarp] Failed to save waypoints file", e);
        }
    }

    private static String str(JsonObject obj, String key, String def) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : def;
    }
}

package com.killer560.hub.dungeonwaypoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dungeon Waypoints settings and the player's own waypoints ({@code killer560smod-dungeonwaypoints.json}). A waypoint is
 * stored in its room template's own frame (room name + relative x/y/z, the way Etherwarp Waypoints does), so it shows in
 * that room on every run however the room is rotated or placed. A box is one block; a line waypoint is also joined to the
 * waypoint before it in that room's list by a line.
 */
public final class DungeonWaypointsConfig {

    /** One waypoint: {@code line} joins it to the previous waypoint of its room. {@code color} is ARGB. */
    public record Wp(String room, int x, int y, int z, int color, boolean line) {
    }

    public static final int DEFAULT_COLOR = 0xFF00E5FF;
    private static final Path PATH = ModPaths.config("killer560smod-dungeonwaypoints.json");
    private static DungeonWaypointsConfig instance;

    private boolean enabled = false;
    private boolean throughWalls = true;
    private int color = DEFAULT_COLOR;
    private final List<Wp> waypoints = new ArrayList<>();
    /** Bumped by every change to the list, so the renderer rebuilds what it derives from it. */
    private int version;

    private DungeonWaypointsConfig() {
    }

    public static DungeonWaypointsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    /** Loads, then tells FeatureBits (profile apply reloads every config). */
    public static void load() {
        DungeonWaypointsConfig c = new DungeonWaypointsConfig();
        JsonObject o = ConfigJson.read(PATH);
        if (o != null) {
            c.enabled = ConfigJson.getBool(o, "enabled", false);
            c.throughWalls = ConfigJson.getBool(o, "throughWalls", true);
            c.color = ConfigJson.getInt(o, "color", DEFAULT_COLOR);
            JsonArray a = ConfigJson.getArray(o, "waypoints");
            if (a != null) {
                for (var el : a) {
                    Wp w = fromJson(el);
                    if (w != null) {
                        c.waypoints.add(w);
                    }
                }
            }
        }
        instance = c;
        FeatureBits.changed();
    }

    public void save() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("throughWalls", throughWalls);
        o.addProperty("color", color);
        o.add("waypoints", toJson(waypoints));
        ConfigJson.write(PATH, o);
    }

    /** One waypoint as a JSON object (the pack format), or null when {@code el} is not a valid one. */
    static Wp fromJson(com.google.gson.JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        JsonObject j = el.getAsJsonObject();
        String room = ConfigJson.getString(j, "room", null);
        if (room == null || room.isBlank() || !j.has("x") || !j.has("y") || !j.has("z")) {
            return null;
        }
        return new Wp(room, ConfigJson.getInt(j, "x", 0), ConfigJson.getInt(j, "y", 0), ConfigJson.getInt(j, "z", 0),
                ConfigJson.getInt(j, "color", DEFAULT_COLOR), ConfigJson.getBool(j, "line", false));
    }

    static JsonArray toJson(List<Wp> list) {
        JsonArray a = new JsonArray();
        for (Wp w : list) {
            JsonObject j = new JsonObject();
            j.addProperty("room", w.room());
            j.addProperty("x", w.x());
            j.addProperty("y", w.y());
            j.addProperty("z", w.z());
            j.addProperty("color", w.color());
            j.addProperty("line", w.line());
            a.add(j);
        }
        return a;
    }

    /** The stored switch alone (no Skyblock Only gate): a FeatureBits gate. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    public void setEnabled(boolean v) {
        enabled = v;
        FeatureBits.changed();
    }

    public boolean isThroughWalls() {
        return throughWalls;
    }

    public void setThroughWalls(boolean v) {
        throughWalls = v;
    }

    /** The color a newly placed waypoint gets. */
    public int getColor() {
        return color;
    }

    public void setColor(int argb) {
        color = argb;
    }

    public int version() {
        return version;
    }

    /** The room's waypoints in placement order. */
    public List<Wp> forRoom(String room) {
        List<Wp> out = new ArrayList<>();
        for (Wp w : waypoints) {
            if (w.room().equals(room)) {
                out.add(w);
            }
        }
        return out;
    }

    public int count() {
        return waypoints.size();
    }

    public void add(Wp w) {
        waypoints.add(w);
        version++;
    }

    public void remove(Wp w) {
        waypoints.remove(w);
        version++;
    }

    /** Replaces {@code old} with {@code neu} at the same place in the list (a color change). */
    public void replace(Wp old, Wp neu) {
        int i = waypoints.indexOf(old);
        if (i >= 0) {
            waypoints.set(i, neu);
            version++;
        }
    }

    /** Removes every waypoint of {@code room}; @return how many. */
    public int clearRoom(String room) {
        int n = waypoints.size();
        waypoints.removeIf(w -> w.room().equals(room));
        version++;
        return n - waypoints.size();
    }

    /** The whole list as pack text (compact JSON). */
    public String exportPack() {
        JsonObject o = new JsonObject();
        o.addProperty("pack", "killer560smod-dungeonwaypoints");
        o.add("waypoints", toJson(waypoints));
        return o.toString();
    }

    /** Adds every valid waypoint of {@code text} not already saved. @return how many were added, or -1 when it is not a pack. */
    public int importPack(String text) {
        try {
            JsonObject o = com.google.gson.JsonParser.parseString(text.trim()).getAsJsonObject();
            JsonArray a = ConfigJson.getArray(o, "waypoints");
            if (a == null) {
                return -1;
            }
            int added = 0;
            for (var el : a) {
                Wp w = fromJson(el);
                if (w != null && !waypoints.contains(w)) {
                    waypoints.add(w);
                    added++;
                }
            }
            version++;
            return added;
        } catch (Exception e) {
            return -1;
        }
    }
}

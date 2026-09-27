package com.killer560.hub.mining.chmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings + saved waypoints for the Crystal Hollows Map / Interactive Map tab. Ships OFF like every other
 * new Mining (WIP) feature; waypoints start empty ("Add Waypoint Here" fills them, same UX
 * {@code gui.tab.F7SpotsTab} already uses for its own walk-to waypoints).
 * <p>
 * Not cheat-gated and not an automation: opening the map, seeing your own real position on it and setting
 * a travel-target marker never sends an interaction or moves the player - see
 * {@link CrystalHollowsMapScreen}'s class doc for why this is safe in the legit jar too.
 */
public final class CrystalHollowsMapConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-chmap");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-chmap.json");

    public static final float MIN_SCALE = 0.5f;
    public static final float MAX_SCALE = 4f;

    private static CrystalHollowsMapConfig instance;

    private boolean enabled = false;
    private float mapScale = 1.5f;
    private final List<CrystalHollowsWaypoint> waypoints = new ArrayList<>();

    private CrystalHollowsMapConfig() {
    }

    public static CrystalHollowsMapConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        instance = new CrystalHollowsMapConfig();
        if (!Files.exists(CONFIG_PATH)) {
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            instance.enabled = ConfigJson.getBool(obj, "enabled", false);
            instance.mapScale = ConfigJson.getFloat(obj, "mapScale", 1.5f);
            JsonArray arr = ConfigJson.getArray(obj, "waypoints");
            if (arr != null) {
                for (JsonElement e : arr) {
                    if (e == null || !e.isJsonObject()) {
                        continue;
                    }
                    JsonObject w = e.getAsJsonObject();
                    instance.waypoints.add(new CrystalHollowsWaypoint(
                            ConfigJson.getString(w, "name", "Waypoint"),
                            ConfigJson.getDouble(w, "x", 0),
                            ConfigJson.getDouble(w, "y", 0),
                            ConfigJson.getDouble(w, "z", 0)));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Couldn't read {} - starting fresh", CONFIG_PATH, e);
            instance = new CrystalHollowsMapConfig();
        }
    }

    public void save() {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("mapScale", mapScale);
            JsonArray arr = new JsonArray();
            for (CrystalHollowsWaypoint w : waypoints) {
                JsonObject o = new JsonObject();
                o.addProperty("name", w.name);
                o.addProperty("x", w.x);
                o.addProperty("y", w.y);
                o.addProperty("z", w.z);
                arr.add(o);
            }
            obj.add("waypoints", arr);
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("Couldn't save {}", CONFIG_PATH, e);
        }
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public float getMapScale() {
        return mapScale;
    }

    public void setMapScale(float mapScale) {
        this.mapScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, mapScale));
    }

    public List<CrystalHollowsWaypoint> getWaypoints() {
        return waypoints;
    }

    public void addWaypoint(CrystalHollowsWaypoint waypoint) {
        waypoints.add(waypoint);
    }

    /** @return true if a waypoint was removed. */
    public boolean removeLastWaypoint() {
        if (waypoints.isEmpty()) {
            return false;
        }
        waypoints.remove(waypoints.size() - 1);
        return true;
    }
}

package com.killer560.hub.mapcycler;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/** Map Cycler settings ({@link MapCyclerFeature}, roomcycle-1118). Cheat build only; the switch starts OFF. */
public final class MapCyclerConfig {

    public static final Path CONFIG_PATH = ModPaths.config("killer560smod-mapcycler.json");

    public static final int MIN_ROOMS = 1;
    public static final int MAX_ROOMS = 10;
    /** Hypixel makes no new instance for 30 s after the last one (CreationCooldownFeature.COOLDOWN_MS). */
    public static final int MAX_REROLLS_CAP = 100;

    private static MapCyclerConfig instance;

    private boolean enabled = false;
    private int maxRooms = 5;
    private int maxRerolls = 20;

    private MapCyclerConfig() {
    }

    public static MapCyclerConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        MapCyclerConfig cfg = new MapCyclerConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.maxRooms = clamp(ConfigJson.getInt(obj, "maxRooms", 5), MIN_ROOMS, MAX_ROOMS);
            cfg.maxRerolls = clamp(ConfigJson.getInt(obj, "maxRerolls", 20), 0, MAX_REROLLS_CAP);
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("maxRooms", maxRooms);
        obj.addProperty("maxRerolls", maxRerolls);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** The switch: only in the cheat build. */
    public boolean isEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && enabled;
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            MapCyclerFeature.stop(null);
        }
    }

    public int getMaxRooms() {
        return maxRooms;
    }

    public void setMaxRooms(int value) {
        maxRooms = clamp(value, MIN_ROOMS, MAX_ROOMS);
    }

    public int getMaxRerolls() {
        return maxRerolls;
    }

    public void setMaxRerolls(int value) {
        maxRerolls = clamp(value, 0, MAX_REROLLS_CAP);
    }
}

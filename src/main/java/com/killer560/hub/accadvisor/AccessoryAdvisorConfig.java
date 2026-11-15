package com.killer560.hub.accadvisor;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/** Persisted "Accessory Advisor" settings - see {@link AccessoryAdvisor}. Ships disabled. Visual only. */
public final class AccessoryAdvisorConfig {

    /** How the next buys are ranked; the panel's label for each is {@link #label}. */
    public enum Mode {
        BEST_OVERALL("Best Overall"),
        UPGRADE_ONLY("Upgrade Only"),
        BUY_NEW("Buy New"),
        CHEAPEST("Cheapest"),
        CHEAPEST_PER_MP("Cheapest Per MP"),
        MOST_MP("Most MP");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    public static final int MIN_ROWS = 3;
    public static final int MAX_ROWS = 15;

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-accadvisor.json");

    private static AccessoryAdvisorConfig instance;
    private static int version;

    private boolean enabled = false;
    private Mode mode = Mode.BEST_OVERALL;
    private int rows = 8;

    private AccessoryAdvisorConfig() {
    }

    public static AccessoryAdvisorConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        AccessoryAdvisorConfig cfg = new AccessoryAdvisorConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.mode = ConfigJson.getEnum(obj, "mode", Mode.class, Mode.BEST_OVERALL);
            cfg.setRows(ConfigJson.getInt(obj, "rows", 8));
        }
        instance = cfg;
        version++;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("mode", mode.name());
        obj.addProperty("rows", rows);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    /** Moves on every change, so the panel rebuilds its rows only when a setting changed. */
    static int version() {
        return version;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        version++;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.BEST_OVERALL : mode;
        version++;
    }

    public int getRows() {
        return rows;
    }

    public void setRows(int rows) {
        this.rows = Math.max(MIN_ROWS, Math.min(MAX_ROWS, rows));
        version++;
    }
}

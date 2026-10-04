package com.killer560.hub.invsort;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Auto Inventory Sorter settings. Ships disabled by default, same as every other new feature in this
 *  mod - it automates real container clicks, so it is also hard-gated on
 *  {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} (see {@link #isEnabled()}). */
public final class InventorySorterConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-invsort.json");

    private static InventorySorterConfig instance;

    private boolean enabled = false;
    /** Delay between the swap-cycle clicks - purely cosmetic pacing on top of {@code ActionGate}'s own one
     *  action-per-tick floor, same idea as Auto Croesus's min/max delay. */
    private int minDelayMs = 120;
    private int maxDelayMs = 280;

    private InventorySorterConfig() {
    }

    public static InventorySorterConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new InventorySorterConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            InventorySorterConfig cfg = new InventorySorterConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
            cfg.minDelayMs = ConfigJson.getInt(obj, "minDelayMs", cfg.minDelayMs);
            cfg.maxDelayMs = ConfigJson.getInt(obj, "maxDelayMs", cfg.maxDelayMs);
            instance = cfg;
        } catch (Exception e) {
            instance = new InventorySorterConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("minDelayMs", minDelayMs);
            obj.addProperty("maxDelayMs", maxDelayMs);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED}: it clicks container slots for
     *  you, same category as Auto Croesus. */
    public boolean isEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMinDelayMs() {
        return minDelayMs;
    }

    public int getMaxDelayMs() {
        return maxDelayMs;
    }

    public void setMinDelayMs(int v) {
        this.minDelayMs = Math.max(0, v);
    }

    public void setMaxDelayMs(int v) {
        this.maxDelayMs = Math.max(0, v);
    }
}

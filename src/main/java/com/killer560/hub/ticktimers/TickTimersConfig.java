package com.killer560.hub.ticktimers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Tick Timers settings - see {@link TickTimersFeature}. Ships disabled by default. */
public final class TickTimersConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-ticktimers.json");

    private static TickTimersConfig instance;

    private boolean enabled = false;
    private boolean displayInTicks = false;
    private boolean showSymbol = true;
    private boolean showPrefix = true;
    private boolean necronTimer = true;
    private boolean goldorTimer = true;
    // Odin TickTimers.kt's own "Start timer" setting (default false): shows Goldor's 104-tick "Start:"
    // countdown after Storm dies. Off = the Goldor line only shows the repeating "Tick:" timer, like Odin.
    private boolean goldorStartTimer = false;
    private boolean stormTimer = true;

    private TickTimersConfig() {
    }

    public static TickTimersConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new TickTimersConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            TickTimersConfig cfg = new TickTimersConfig();
            cfg.enabled = getBool(obj, "enabled", false);
            cfg.displayInTicks = getBool(obj, "displayInTicks", false);
            cfg.showSymbol = getBool(obj, "showSymbol", true);
            cfg.showPrefix = getBool(obj, "showPrefix", true);
            cfg.necronTimer = getBool(obj, "necronTimer", true);
            cfg.goldorTimer = getBool(obj, "goldorTimer", true);
            cfg.goldorStartTimer = getBool(obj, "goldorStartTimer", false);
            cfg.stormTimer = getBool(obj, "stormTimer", true);
            instance = cfg;
        } catch (Exception e) {
            instance = new TickTimersConfig();
        }
    }

    private static boolean getBool(JsonObject obj, String key, boolean def) {
        return ConfigJson.getBool(obj, key, def);
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("displayInTicks", displayInTicks);
            obj.addProperty("showSymbol", showSymbol);
            obj.addProperty("showPrefix", showPrefix);
            obj.addProperty("necronTimer", necronTimer);
            obj.addProperty("goldorTimer", goldorTimer);
            obj.addProperty("goldorStartTimer", goldorStartTimer);
            obj.addProperty("stormTimer", stormTimer);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDisplayInTicks() {
        return displayInTicks;
    }

    public void setDisplayInTicks(boolean displayInTicks) {
        this.displayInTicks = displayInTicks;
    }

    public boolean isShowSymbol() {
        return showSymbol;
    }

    public void setShowSymbol(boolean showSymbol) {
        this.showSymbol = showSymbol;
    }

    public boolean isShowPrefix() {
        return showPrefix;
    }

    public void setShowPrefix(boolean showPrefix) {
        this.showPrefix = showPrefix;
    }

    public boolean isNecronTimer() {
        return necronTimer;
    }

    public void setNecronTimer(boolean necronTimer) {
        this.necronTimer = necronTimer;
    }

    public boolean isGoldorTimer() {
        return goldorTimer;
    }

    public void setGoldorTimer(boolean goldorTimer) {
        this.goldorTimer = goldorTimer;
    }

    public boolean isGoldorStartTimer() {
        return goldorStartTimer;
    }

    public void setGoldorStartTimer(boolean goldorStartTimer) {
        this.goldorStartTimer = goldorStartTimer;
    }

    public boolean isStormTimer() {
        return stormTimer;
    }

    public void setStormTimer(boolean stormTimer) {
        this.stormTimer = stormTimer;
    }
}

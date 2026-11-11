package com.killer560.hub.islands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings of one Rift or Fishing feature (staged for after 2.0), saved as {@code killer560smod-<category>-<name>.json}
 * (folders skyblock/rift and skyblock/fishing). Each feature declares its keys once with their defaults ({@link #flag},
 * {@link #num}, {@link #text}); every value is clamped on set and on load. {@code "enabled"} is the feature's switch and
 * always defaults OFF.
 */
public final class IslandConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, IslandConfig> ALL = new LinkedHashMap<>();

    private final String name;
    private final Path path;
    private final Map<String, Object> defaults = new LinkedHashMap<>();
    private final Map<String, int[]> ranges = new LinkedHashMap<>();
    private final Map<String, Object> values = new LinkedHashMap<>();
    private boolean loaded;

    private IslandConfig(String category, String name) {
        this.name = name;
        this.path = ModPaths.config("killer560smod-" + category + "-" + name + ".json");
        defaults.put("enabled", false);
    }

    /** The config of feature {@code name} in {@code category} ("rift" or "fishing"), made on first use. */
    public static synchronized IslandConfig of(String category, String name) {
        return ALL.computeIfAbsent(category + "-" + name, k -> new IslandConfig(category, name));
    }

    /** Every Rift and Fishing config made so far (read by the testkit). */
    public static synchronized List<IslandConfig> all() {
        return new ArrayList<>(ALL.values());
    }

    /** Re-reads every Rift and Fishing config from disk (profile apply). */
    public static void loadAll() {
        for (IslandConfig c : all()) {
            c.load();
        }
        com.killer560.hub.util.FeatureBits.changed();
    }

    public IslandConfig flag(String key, boolean def) {
        defaults.put(key, def);
        loaded = false;
        return this;
    }

    public IslandConfig num(String key, int def, int min, int max) {
        defaults.put(key, def);
        ranges.put(key, new int[]{min, max});
        loaded = false;
        return this;
    }

    public IslandConfig text(String key, String def) {
        defaults.put(key, def);
        loaded = false;
        return this;
    }

    /** The settings file. */
    public Path path() {
        return path;
    }

    public String name() {
        return name;
    }

    /** The stored switch alone (no Skyblock Only gate): a FeatureBits gate. */
    public boolean isEnabledRaw() {
        return on("enabled");
    }

    public boolean isEnabled() {
        return isEnabledRaw() && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean on) {
        set("enabled", on);
    }

    public boolean on(String key) {
        return get(key) instanceof Boolean b && b;
    }

    public int num(String key) {
        return get(key) instanceof Integer i ? i : 0;
    }

    public String text(String key) {
        return get(key) instanceof String s ? s : "";
    }

    public int min(String key) {
        int[] r = ranges.get(key);
        return r == null ? 0 : r[0];
    }

    public int max(String key) {
        int[] r = ranges.get(key);
        return r == null ? 0 : r[1];
    }

    public synchronized void set(String key, Object value) {
        ensureLoaded();
        Object def = defaults.get(key);
        if (def == null) {
            return;
        }
        if (def instanceof Boolean && value instanceof Boolean) {
            values.put(key, value);
        } else if (def instanceof Integer && value instanceof Number n) {
            values.put(key, clamp(key, n.intValue()));
        } else if (def instanceof String && value instanceof String) {
            values.put(key, value);
        }
        if ("enabled".equals(key)) {
            com.killer560.hub.util.FeatureBits.changed();
        }
    }

    private int clamp(String key, int v) {
        int[] r = ranges.get(key);
        return r == null ? v : Math.max(r[0], Math.min(r[1], v));
    }

    private synchronized Object get(String key) {
        ensureLoaded();
        Object v = values.get(key);
        return v != null ? v : defaults.get(key);
    }

    private void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    public synchronized void load() {
        loaded = true;
        values.clear();
        if (!Files.exists(path)) {
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, Object> d : defaults.entrySet()) {
                JsonElement e = obj.get(d.getKey());
                if (e == null || !e.isJsonPrimitive()) {
                    continue;
                }
                Object def = d.getValue();
                if (def instanceof Boolean && e.getAsJsonPrimitive().isBoolean()) {
                    values.put(d.getKey(), e.getAsBoolean());
                } else if (def instanceof Integer && e.getAsJsonPrimitive().isNumber()) {
                    values.put(d.getKey(), clamp(d.getKey(), e.getAsInt()));
                } else if (def instanceof String && e.getAsJsonPrimitive().isString()) {
                    values.put(d.getKey(), e.getAsString());
                }
            }
        } catch (Exception ignored) {
            values.clear();
        }
    }

    public synchronized void save() {
        ensureLoaded();
        try {
            Files.createDirectories(path.getParent());
            JsonObject obj = new JsonObject();
            for (String key : defaults.keySet()) {
                Object v = get(key);
                if (v instanceof Boolean b) {
                    obj.addProperty(key, b);
                } else if (v instanceof Integer i) {
                    obj.addProperty(key, i);
                } else if (v instanceof String s) {
                    obj.addProperty(key, s);
                }
            }
            Files.writeString(path, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}

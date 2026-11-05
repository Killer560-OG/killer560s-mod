package com.killer560.hub.reforge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reforge Helper settings (see {@link ReforgeHelperFeature}). Off by default. */
public final class ReforgeHelperConfig {

    public static final int DEFAULT_COLOR = 0xFF55FF55;
    public static final int MAX_WANTED_LENGTH = 200;
    /** At most this many wanted reforges are read from the setting. */
    static final int MAX_WANTED = 24;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-reforge-helper.json");

    private static ReforgeHelperConfig instance;

    private boolean enabled = false;
    private String wanted = "";
    private boolean blockWanted = true;
    private int color = DEFAULT_COLOR;
    /** {@link #wanted} parsed by {@link #key}, rebuilt only when it is set. */
    private Set<String> wantedKeys = Set.of();
    /** Goes up on every change of the wanted list, so the panel knows to rebuild its rows. */
    private int version;

    private ReforgeHelperConfig() {
    }

    public static ReforgeHelperConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        ReforgeHelperConfig cfg = new ReforgeHelperConfig();
        try {
            if (Files.exists(CONFIG_PATH)) {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
                cfg.blockWanted = ConfigJson.getBool(obj, "blockWanted", true);
                cfg.color = ConfigJson.getInt(obj, "color", DEFAULT_COLOR);
                cfg.setWanted(ConfigJson.getString(obj, "wanted", ""));
            }
        } catch (Exception ignored) {
            cfg = new ReforgeHelperConfig();
        }
        cfg.version = instance == null ? 0 : instance.version + 1;
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("wanted", wanted);
            obj.addProperty("blockWanted", blockWanted);
            obj.addProperty("color", color);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** A reforge name or NBT modifier as one key: "Lumberjack's" -> "lumberjacks", "Double-Bit" -> "double_bit". */
    static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s_-]", "").replaceAll("[\\s-]+", "_");
    }

    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    /** Comma-separated reforge names ("Fabled, Withered"). */
    public String getWanted() {
        return wanted;
    }

    public void setWanted(String v) {
        String s = v == null ? "" : v;
        wanted = s.length() > MAX_WANTED_LENGTH ? s.substring(0, MAX_WANTED_LENGTH) : s;
        Set<String> keys = new LinkedHashSet<>();
        for (String part : wanted.split(",")) {
            String k = key(part);
            if (!k.isEmpty() && k.length() <= 32 && keys.size() < MAX_WANTED) {
                keys.add(k);
            }
        }
        wantedKeys = Collections.unmodifiableSet(keys);
        version++;
    }

    /** The wanted reforges as {@link #key}s. */
    public Set<String> wantedKeys() {
        return wantedKeys;
    }

    /** Adds {@code name} to the wanted list, or removes every entry naming it (by name or {@code modifier}). */
    public void toggleWanted(String name, String modifier) {
        String k = key(name);
        String m = key(modifier);
        List<String> kept = new ArrayList<>();
        boolean removed = false;
        for (String part : wanted.split(",")) {
            String pk = key(part);
            if (pk.isEmpty()) {
                continue;
            }
            if (pk.equals(k) || pk.equals(m)) {
                removed = true;
            } else {
                kept.add(part.trim());
            }
        }
        if (!removed) {
            kept.add(name);
        }
        setWanted(String.join(", ", kept));
    }

    int version() {
        return version;
    }

    /** Cancel a click on the Reforge button while the item already has a wanted reforge. */
    public boolean isBlockWanted() {
        return blockWanted;
    }

    public void setBlockWanted(boolean v) {
        blockWanted = v;
    }

    public int getColor() {
        return color;
    }

    public void setColor(int v) {
        color = v;
    }
}

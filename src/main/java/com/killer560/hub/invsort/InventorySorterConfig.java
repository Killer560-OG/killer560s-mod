package com.killer560.hub.invsort;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.KeyUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persisted Auto Inventory Sorter settings. Ships disabled by default, same as every other new feature in this
 *  mod - it automates real container clicks, so it is also hard-gated on
 *  {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} (see {@link #isEnabled()}).
 *  <p>
 *  Pacing is in client TICKS since 2026-10-08 (killer560: "it should have a slider for ticks between sorting ...
 *  Have a random slider essentially to it as an option as well"): every click waits {@link #ticksBetweenMoves} plus
 *  0..{@link #randomExtraTicks} more. A file from before then has {@code minDelayMs}/{@code maxDelayMs}; they are
 *  converted once, from the old keys, at 50 ms a tick. Per-layout keybinds live here too (layout name -> key code). */
public final class InventorySorterConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-invsort.json");

    public static final int MIN_TICKS = 1;
    public static final int MAX_TICKS = 20;
    public static final int MAX_RANDOM_TICKS = 20;

    private static InventorySorterConfig instance;

    private boolean enabled = false;
    /** Client ticks between two clicks the sorter sends (ActionGate's one-per-tick floor is 1). */
    private int ticksBetweenMoves = 3;
    /** Up to this many extra ticks, picked at random for each click. */
    private int randomExtraTicks = 2;
    /** Layout name -> key (or mouse-button, {@link KeyUtil#MOUSE_CODE_BASE}) code that applies it. Insertion ordered. */
    private final Map<String, Integer> layoutKeys = new LinkedHashMap<>();

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
        InventorySorterConfig cfg = new InventorySorterConfig();
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
            if (obj.has("ticksBetweenMoves")) {
                cfg.setTicksBetweenMoves(ConfigJson.getInt(obj, "ticksBetweenMoves", cfg.ticksBetweenMoves));
                cfg.setRandomExtraTicks(ConfigJson.getInt(obj, "randomExtraTicks", cfg.randomExtraTicks));
            } else if (obj.has("minDelayMs") || obj.has("maxDelayMs")) {
                // The old millisecond pair, converted once from the old file's own keys.
                int min = ConfigJson.getInt(obj, "minDelayMs", 120);
                int max = Math.max(min, ConfigJson.getInt(obj, "maxDelayMs", 280));
                cfg.setTicksBetweenMoves(Math.round(min / 50f));
                cfg.setRandomExtraTicks(Math.round((max - min) / 50f));
            }
            JsonObject keys = ConfigJson.getObject(obj, "layoutKeys");
            if (keys != null) {
                for (String name : keys.keySet()) {
                    int code = KeyUtil.sanitizeBind(ConfigJson.getInt(keys, name, KeyUtil.NONE));
                    if (code != KeyUtil.NONE && InventoryLayoutStore.validateName(name) == null) {
                        cfg.layoutKeys.put(name, code);
                    }
                }
            }
        } catch (Exception e) {
            // Unreadable file: defaults (whatever was read before the failure is kept).
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("ticksBetweenMoves", ticksBetweenMoves);
            obj.addProperty("randomExtraTicks", randomExtraTicks);
            JsonObject keys = new JsonObject();
            for (Map.Entry<String, Integer> e : layoutKeys.entrySet()) {
                keys.addProperty(e.getKey(), e.getValue());
            }
            obj.add("layoutKeys", keys);
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

    public int getTicksBetweenMoves() {
        return ticksBetweenMoves;
    }

    public void setTicksBetweenMoves(int ticks) {
        this.ticksBetweenMoves = Math.max(MIN_TICKS, Math.min(MAX_TICKS, ticks));
    }

    public int getRandomExtraTicks() {
        return randomExtraTicks;
    }

    public void setRandomExtraTicks(int ticks) {
        this.randomExtraTicks = Math.max(0, Math.min(MAX_RANDOM_TICKS, ticks));
    }

    /** The key bound to {@code layoutName} (case-insensitive), or {@link KeyUtil#NONE}. */
    public int getLayoutKey(String layoutName) {
        if (layoutName == null) {
            return KeyUtil.NONE;
        }
        for (Map.Entry<String, Integer> e : layoutKeys.entrySet()) {
            if (e.getKey().equalsIgnoreCase(layoutName)) {
                return e.getValue();
            }
        }
        return KeyUtil.NONE;
    }

    /** Binds {@code code} to the layout; {@link KeyUtil#NONE} (or an invalid code) unbinds it. */
    public void setLayoutKey(String layoutName, int code) {
        if (layoutName == null) {
            return;
        }
        layoutKeys.keySet().removeIf(k -> k.equalsIgnoreCase(layoutName));
        int clean = KeyUtil.sanitizeBind(code);
        if (clean != KeyUtil.NONE) {
            layoutKeys.put(layoutName, clean);
        }
    }

    /** Moves a binding along with a layout rename. */
    public void renameLayoutKey(String from, String to) {
        int code = getLayoutKey(from);
        setLayoutKey(from, KeyUtil.NONE);
        if (code != KeyUtil.NONE) {
            setLayoutKey(to, code);
        }
    }

    /** A copy: layout name -> key code. */
    public Map<String, Integer> getLayoutKeys() {
        return new LinkedHashMap<>(layoutKeys);
    }
}

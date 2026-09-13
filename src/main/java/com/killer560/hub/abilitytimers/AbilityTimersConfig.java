package com.killer560.hub.abilitytimers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Persisted list of {@link AbilityTimerEntry} - see that class's own doc. Seeded once with a "Mask
 *  (Necron's Handle)" preset, per killer560's explicit request - its duration is a placeholder, NOT a
 *  verified real Hypixel cooldown value (this session had no way to confirm the real number against a
 *  live game, and this codebase's own standing rule is "confirmed, not guessed" for anything gameplay-
 *  numeric) - correct it once you know the real cooldown, same as setting up any other timer here. */
public final class AbilityTimersConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-abilitytimers.json");

    private static AbilityTimersConfig instance;

    private boolean enabled = true;
    private boolean presetsSeeded = false;
    private final List<AbilityTimerEntry> entries = new ArrayList<>();

    private AbilityTimersConfig() {
    }

    public static AbilityTimersConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        AbilityTimersConfig cfg = new AbilityTimersConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                cfg.enabled = !root.has("enabled") || root.get("enabled").getAsBoolean();
                cfg.presetsSeeded = root.has("presetsSeeded") && root.get("presetsSeeded").getAsBoolean();
                if (root.has("entries")) {
                    for (var el : root.getAsJsonArray("entries")) {
                        JsonObject obj = el.getAsJsonObject();
                        AbilityTimerEntry e = new AbilityTimerEntry();
                        e.id = getString(obj, "id", e.id);
                        e.name = getString(obj, "name", e.name);
                        e.durationMs = obj.has("durationMs") ? obj.get("durationMs").getAsInt() : e.durationMs;
                        e.keyCode = obj.has("keyCode") ? obj.get("keyCode").getAsInt() : -1;
                        e.colorHex = getString(obj, "colorHex", e.colorHex);
                        e.enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
                        cfg.entries.add(e);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (!cfg.presetsSeeded) {
            AbilityTimerEntry mask = new AbilityTimerEntry();
            mask.name = "Mask (Necron's Handle) - SET REAL DURATION";
            mask.durationMs = 60_000;
            mask.colorHex = "3399FF";
            cfg.entries.add(mask);
            cfg.presetsSeeded = true;
        }
        instance = cfg;
        instance.save();
    }

    private static String getString(JsonObject obj, String key, String fallback) {
        return obj.has(key) ? obj.get(key).getAsString() : fallback;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);
            root.addProperty("presetsSeeded", presetsSeeded);
            JsonArray array = new JsonArray();
            for (AbilityTimerEntry e : entries) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", e.id);
                obj.addProperty("name", e.name);
                obj.addProperty("durationMs", e.durationMs);
                obj.addProperty("keyCode", e.keyCode);
                obj.addProperty("colorHex", e.colorHex);
                obj.addProperty("enabled", e.enabled);
                array.add(obj);
            }
            root.add("entries", array);
            Files.writeString(CONFIG_PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<AbilityTimerEntry> entries() {
        return entries;
    }

    public AbilityTimerEntry addNew() {
        AbilityTimerEntry e = new AbilityTimerEntry();
        e.name = "Timer " + (entries.size() + 1);
        entries.add(e);
        save();
        return e;
    }

    public void remove(String id) {
        entries.removeIf(e -> e.id.equals(id));
        save();
    }
}

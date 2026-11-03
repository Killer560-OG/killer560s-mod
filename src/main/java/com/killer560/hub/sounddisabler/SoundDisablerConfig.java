package com.killer560.hub.sounddisabler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Sound Disabler tab's settings (2026-11-02): the master switch (off by default), one switch per {@link MutedSound}
 * (Bonzo's Staff on, the rest off), the free-form Custom Sound IDs list and Show Sound IDs. Stored as
 * {@code killer560smod-sound-disabler.json}: {@code {"enabled": false, "sounds": {"bonzo_staff": true, ...},
 * "custom": "entity.villager.*, minecraft:block.note_block.harp", "showIds": false}}.
 */
public final class SoundDisablerConfig {

    private static final Logger LOGGER = ModLog.get("killer560smod-sounddisabler");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-sound-disabler.json");
    /** The Custom Sound IDs box's length limit. */
    public static final int CUSTOM_MAX = 1000;

    private static SoundDisablerConfig instance;

    private boolean enabled;
    /** Indexed by {@link MutedSound#ordinal()}: checked on every sound the game plays, so no map lookups. */
    private final boolean[] on = new boolean[MutedSound.ALL.length];
    private String custom = "";
    private boolean showIds;
    /** {@link #custom} parsed: exact ids, and prefixes for entries ending in '*'. Rebuilt by {@link #setCustom}. */
    private List<String> customExact = List.of();
    private List<String> customPrefix = List.of();

    private SoundDisablerConfig() {
        for (MutedSound s : MutedSound.ALL) {
            on[s.ordinal()] = s.defaultOn;
        }
    }

    public static SoundDisablerConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        SoundDisablerConfig cfg = new SoundDisablerConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
                if (obj.has("sounds") && obj.get("sounds").isJsonObject()) {
                    JsonObject sounds = obj.getAsJsonObject("sounds");
                    for (MutedSound s : MutedSound.ALL) {
                        if (sounds.has(s.key)) {
                            cfg.on[s.ordinal()] = sounds.get(s.key).getAsBoolean();
                        }
                    }
                }
                cfg.setCustom(obj.has("custom") ? obj.get("custom").getAsString() : "");
                cfg.showIds = obj.has("showIds") && obj.get("showIds").getAsBoolean();
            } catch (Exception e) {
                LOGGER.warn("[SoundDisabler] could not read {}, using the defaults", CONFIG_PATH, e);
                cfg = new SoundDisablerConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            JsonObject sounds = new JsonObject();
            for (MutedSound s : MutedSound.ALL) {
                sounds.addProperty(s.key, on[s.ordinal()]);
            }
            obj.add("sounds", sounds);
            obj.addProperty("custom", custom);
            obj.addProperty("showIds", showIds);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[SoundDisabler] could not save {}", CONFIG_PATH, e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public boolean isOn(MutedSound s) {
        return on[s.ordinal()];
    }

    public void setOn(MutedSound s, boolean value) {
        on[s.ordinal()] = value;
    }

    public String getCustom() {
        return custom;
    }

    /**
     * Sets the Custom Sound IDs list: ids separated by commas, spaces or new lines. An id without a namespace is a
     * Minecraft one ({@code entity.villager.ambient} is {@code minecraft:entity.villager.ambient}); one ending in
     * {@code *} matches every id starting with what is before it ({@code entity.villager.*}).
     */
    public void setCustom(String value) {
        String v = value == null ? "" : value.strip();
        if (v.length() > CUSTOM_MAX) {
            v = v.substring(0, CUSTOM_MAX);
        }
        custom = v;
        List<String> exact = new ArrayList<>();
        List<String> prefix = new ArrayList<>();
        for (String raw : v.split("[,\\s]+")) {
            String e = raw.strip().toLowerCase(Locale.ROOT);
            if (e.isEmpty() || e.equals("*")) {
                continue;   // a lone "*" would mute every sound in the game: never what a list entry means
            }
            if (e.indexOf(':') < 0) {
                e = "minecraft:" + e;
            }
            if (e.endsWith("*")) {
                prefix.add(e.substring(0, e.length() - 1));
            } else {
                exact.add(e);
            }
        }
        customExact = List.copyOf(exact);
        customPrefix = List.copyOf(prefix);
    }

    /** Whether the full id {@code id} ({@code namespace:path}) is on the Custom Sound IDs list. */
    public boolean customMatches(String id) {
        for (String e : customExact) {
            if (e.equals(id)) {
                return true;
            }
        }
        for (String p : customPrefix) {
            if (id.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** The number of entries the Custom Sound IDs list parsed to. */
    public int customCount() {
        return customExact.size() + customPrefix.size();
    }

    public boolean isShowIds() {
        return showIds;
    }

    public void setShowIds(boolean value) {
        showIds = value;
    }
}

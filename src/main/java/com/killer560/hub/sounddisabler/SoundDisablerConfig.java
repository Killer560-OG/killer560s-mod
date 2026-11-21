package com.killer560.hub.sounddisabler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Sound Disabler tab's settings (2026-11-02): the master switch (off by default), one switch per {@link MutedSound}
 * (every row off; killer560 2026-11-02: "dont have it on by default"), Show Sound IDs, and since sounddisabler-menu-1118 the
 * per-sound list the Custom Sounds screen edits (a mute and a 0..200% volume for any sound id) plus the Volume Sliders option
 * that gives every preset row a volume too. Stored as {@code killer560smod-sound-disabler.json}: {@code {"enabled": false,
 * "sounds": {"bonzo_staff": false, ...}, "showIds": false, "volumeSliders": false, "presetVolumes": {"bonzo_staff": 50},
 * "custom": [{"id": "minecraft:entity.villager.ambient", "muted": true, "volume": 100}]}}. Only changed sounds are stored.
 * An older {@code "custom"} string ("entity.villager.*, block.note_block.harp") is read as muted entries.
 */
public final class SoundDisablerConfig {

    private static final Logger LOGGER = ModLog.get("killer560smod-sounddisabler");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-sound-disabler.json");
    /** The volume range of every slider, in percent, and its step. */
    public static final int MAX_PERCENT = 200;
    public static final int STEP_PERCENT = 5;

    /** One sound's setting: muted outright, and its volume in percent (kept while muted). */
    public record Setting(boolean muted, int percent) {
        boolean isDefault() { return !muted && percent == 100; }
    }

    private static SoundDisablerConfig instance;

    private boolean enabled;
    /** Indexed by {@link MutedSound#ordinal()}: checked on every sound the game plays, so no map lookups. */
    private final boolean[] on = new boolean[MutedSound.ALL.length];
    private final int[] presetPercent = new int[MutedSound.ALL.length];
    private boolean showIds;
    private boolean volumeSliders;
    /** Changed sounds only, looked up by id on every sound played (no allocation: Identifier hashes its two strings once). */
    private final Map<Identifier, Setting> sounds = new ConcurrentHashMap<>();

    private SoundDisablerConfig() {
        for (MutedSound s : MutedSound.ALL) {
            on[s.ordinal()] = s.defaultOn;
            presetPercent[s.ordinal()] = 100;
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
                JsonObject rows = obj.has("sounds") && obj.get("sounds").isJsonObject() ? obj.getAsJsonObject("sounds") : null;
                JsonObject vols = obj.has("presetVolumes") && obj.get("presetVolumes").isJsonObject()
                        ? obj.getAsJsonObject("presetVolumes") : null;
                for (MutedSound s : MutedSound.ALL) {
                    if (rows != null && rows.has(s.key)) {
                        cfg.on[s.ordinal()] = rows.get(s.key).getAsBoolean();
                    }
                    if (vols != null && vols.has(s.key)) {
                        cfg.presetPercent[s.ordinal()] = clampPercent(vols.get(s.key).getAsInt());
                    }
                }
                cfg.showIds = obj.has("showIds") && obj.get("showIds").getAsBoolean();
                cfg.volumeSliders = obj.has("volumeSliders") && obj.get("volumeSliders").getAsBoolean();
                JsonElement custom = obj.get("custom");
                if (custom != null && custom.isJsonArray()) {
                    for (JsonElement e : (JsonArray) custom) {
                        JsonObject o = e.getAsJsonObject();
                        Identifier id = Identifier.tryParse(o.get("id").getAsString());
                        if (id != null) {
                            cfg.put(id, new Setting(o.has("muted") && o.get("muted").getAsBoolean(),
                                    o.has("volume") ? clampPercent(o.get("volume").getAsInt()) : 100));
                        }
                    }
                } else if (custom != null && custom.isJsonPrimitive()) {
                    cfg.migrateCustomList(custom.getAsString());
                }
            } catch (Exception e) {
                LOGGER.warn("[SoundDisabler] could not read {}, using the defaults", CONFIG_PATH, e);
                cfg = new SoundDisablerConfig();
            }
        }
        instance = cfg;
    }

    /**
     * The old Custom Sound IDs box (ids separated by commas, spaces or new lines; no namespace means minecraft; a trailing
     * {@code *} is a prefix) becomes muted entries: an exact id as it was, a prefix as every registered sound that starts with
     * it. A lone {@code *} was never a valid entry.
     */
    private void migrateCustomList(String list) {
        for (String raw : list.split("[,\\s]+")) {
            String e = raw.strip().toLowerCase(Locale.ROOT);
            if (e.isEmpty() || e.equals("*")) {
                continue;
            }
            if (e.indexOf(':') < 0) {
                e = "minecraft:" + e;
            }
            if (e.endsWith("*")) {
                String prefix = e.substring(0, e.length() - 1);
                for (Identifier id : BuiltInRegistries.SOUND_EVENT.keySet()) {
                    if (id.toString().startsWith(prefix)) {
                        put(id, new Setting(true, 100));
                    }
                }
            } else {
                Identifier id = Identifier.tryParse(e);
                if (id != null) {
                    put(id, new Setting(true, 100));
                }
            }
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            JsonObject rows = new JsonObject();
            JsonObject vols = new JsonObject();
            for (MutedSound s : MutedSound.ALL) {
                rows.addProperty(s.key, on[s.ordinal()]);
                if (presetPercent[s.ordinal()] != 100) {
                    vols.addProperty(s.key, presetPercent[s.ordinal()]);
                }
            }
            obj.add("sounds", rows);
            obj.addProperty("showIds", showIds);
            obj.addProperty("volumeSliders", volumeSliders);
            obj.add("presetVolumes", vols);
            JsonArray custom = new JsonArray();
            sounds.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(en -> {
                JsonObject o = new JsonObject();
                o.addProperty("id", en.getKey().toString());
                o.addProperty("muted", en.getValue().muted());
                o.addProperty("volume", en.getValue().percent());
                custom.add(o);
            });
            obj.add("custom", custom);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[SoundDisabler] could not save {}", CONFIG_PATH, e);
        }
    }

    public static int clampPercent(int p) {
        return Math.max(0, Math.min(MAX_PERCENT, p));
    }

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean value) { enabled = value; }

    public boolean isOn(MutedSound s) { return on[s.ordinal()]; }

    public void setOn(MutedSound s, boolean value) { on[s.ordinal()] = value; }

    public boolean isShowIds() { return showIds; }

    public void setShowIds(boolean value) { showIds = value; }

    public boolean isVolumeSliders() { return volumeSliders; }

    public void setVolumeSliders(boolean value) { volumeSliders = value; }

    /** The preset row's volume in percent (100 = untouched); kept while Volume Sliders is off. */
    public int getPresetPercent(MutedSound s) { return presetPercent[s.ordinal()]; }

    public void setPresetPercent(MutedSound s, int percent) { presetPercent[s.ordinal()] = clampPercent(percent); }

    /** This sound's setting, or null when it is at the default (plays, 100%). */
    public Setting sound(Identifier id) { return sounds.get(id); }

    public int soundCount() { return sounds.size(); }

    /** Sets a sound's mute and volume; a sound back at the default is dropped from the list. */
    public void setSound(Identifier id, boolean muted, int percent) {
        put(id, new Setting(muted, clampPercent(percent)));
    }

    private void put(Identifier id, Setting s) {
        if (s.isDefault()) {
            sounds.remove(id);
        } else {
            sounds.put(id, s);
        }
    }

    public void clearSounds() { sounds.clear(); }

    /** The ids that have a changed setting (the Custom Sounds screen lists them even when no sound plays under that id). */
    public java.util.Set<Identifier> changedIds() { return sounds.keySet(); }
}

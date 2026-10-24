package com.killer560.hub.mapping;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Map Extras settings. Only the Extra Info Overlay is left: it draws score, secrets for S+, crypts, deaths
 *  and bonus kills under the Dungeon Map HUD ({@code LiveMapFeature.LiveMapHudElement}) from the Score Calculator's
 *  tracking, which runs for it with Score Calculator off.
 *  <p>
 *  The three placeholders that lived here (Funny Map, Mimic Room Show/Hide and Player-Head Class Recolor, which never
 *  drew anything) became real settings of the Dungeon Map on 2026-10-15 and live in {@code LiveMapConfig}: Funny Map and
 *  Show Mimic Room (cheat build) and Recolor by Class, which rings a teammate's head in his class color. Their old keys
 *  in this file are ignored. */
public final class MappingConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-mapping.json");

    private static MappingConfig instance;

    private boolean enabled = false;
    private boolean extraInfoEnabled = false;

    private MappingConfig() {
    }

    public static MappingConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new MappingConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            MappingConfig cfg = new MappingConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.extraInfoEnabled = ConfigJson.getBool(obj, "extraInfoEnabled", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new MappingConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("extraInfoEnabled", extraInfoEnabled);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Master toggle - no longer gates anything since the periodic diagnostic log in {@link MappingFeature} was removed. The "Dump Held Map
     *  Now" button/command works regardless of this, since it's an explicit one-shot user action. */
    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isExtraInfoEnabled() {
        return extraInfoEnabled;
    }

    public void setExtraInfoEnabled(boolean extraInfoEnabled) {
        this.extraInfoEnabled = extraInfoEnabled;
    }
}

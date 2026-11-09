package com.killer560.hub.mapping;

import com.killer560.hub.util.ModPaths;
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

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-mapping.json");

    private static MappingConfig instance;

    private boolean enabled = false;
    private boolean extraInfoEnabled = false;
    /** Extra Info Scale: the overlay's text size against its usual size (to-do Doc, 2026-11-01: "Make the extra info
     *  overlay have a slider bar for the scale of it"). 0.5 to 2, 1 by default. */
    public static final float MIN_EXTRA_INFO_SCALE = 0.5f;
    public static final float MAX_EXTRA_INFO_SCALE = 2f;
    private float extraInfoScale = 1f;

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
            cfg.setExtraInfoScale(ConfigJson.getFloat(obj, "extraInfoScale", 1f));
            instance = cfg;
        } catch (Exception e) {
            instance = new MappingConfig();
        }
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("extraInfoEnabled", extraInfoEnabled);
        obj.addProperty("extraInfoScale", extraInfoScale);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    /** Master toggle - no longer gates anything since the periodic diagnostic log in {@link MappingFeature} was removed. The "Dump Held Map
     *  Now" button/command works regardless of this, since it's an explicit one-shot user action. */
    public boolean isEnabled() { return enabled && com.killer560.hub.util.SkyblockGate.allows(); }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isExtraInfoEnabled() { return extraInfoEnabled; }

    public void setExtraInfoEnabled(boolean extraInfoEnabled) { this.extraInfoEnabled = extraInfoEnabled; }

    public float getExtraInfoScale() { return extraInfoScale; }

    public void setExtraInfoScale(float v) {
        this.extraInfoScale = Float.isNaN(v) ? 1f : Math.max(MIN_EXTRA_INFO_SCALE, Math.min(MAX_EXTRA_INFO_SCALE, v));
    }
}

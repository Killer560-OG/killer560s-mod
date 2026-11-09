package com.killer560.hub.hideeffects;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Hide Potion Effects (killer560, 2026-11-02: "find if there is a way to hide these effects as well"). Two switches:
 * the effects panel vanilla draws beside the inventory (ON by default: his last word on it, 2026-10-18, was "just make it
 * always on"), and the effect icons in the HUD's top right (OFF by default).
 * <p>
 * Until 2026-11-02 the panel was hidden only while the Inventory Theme was on, and before 2026-10-18 by a
 * {@code hidePotionEffects} key in the Inventory Theme's file. That old key is NOT read: a {@code false} saved before
 * 10-18 would bring the panel back after he asked for it always hidden. This file starts at the defaults. The panel now
 * follows this switch alone, with the theme on or off.
 */
public final class HideEffectsConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-hideeffects.json");

    private static volatile HideEffectsConfig instance;

    private boolean hidePotionEffects = false;
    private boolean hideEffectIcons = false;

    private HideEffectsConfig() {
    }

    public static HideEffectsConfig getInstance() {
        HideEffectsConfig cfg = instance;
        if (cfg == null) {
            load();
            cfg = instance;
        }
        return cfg;
    }

    public static void load() {
        HideEffectsConfig cfg = new HideEffectsConfig();
        try {
            if (Files.exists(CONFIG_PATH)) {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.hidePotionEffects = ConfigJson.getBool(obj, "hidePotionEffects", cfg.hidePotionEffects);
                cfg.hideEffectIcons = ConfigJson.getBool(obj, "hideEffectIcons", cfg.hideEffectIcons);
            }
        } catch (Exception ignored) {
            // Unparseable file: defaults for this session.
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("hidePotionEffects", hidePotionEffects);
        obj.addProperty("hideEffectIcons", hideEffectIcons);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    /** The saved switch, for the menu. */
    public boolean isHidePotionEffectsRaw() {
        return hidePotionEffects;
    }

    /** Whether the inventory's effects panel is hidden right now (the switch and the mod-wide Skyblock Only gate). */
    public boolean isHidePotionEffects() {
        return hidePotionEffects && SkyblockGate.allows();
    }

    public void setHidePotionEffects(boolean on) {
        this.hidePotionEffects = on;
    }

    public boolean isHideEffectIconsRaw() {
        return hideEffectIcons;
    }

    /** Whether the HUD's effect icons (top right) are hidden right now. */
    public boolean isHideEffectIcons() {
        return hideEffectIcons && SkyblockGate.allows();
    }

    public void setHideEffectIcons(boolean on) {
        this.hideEffectIcons = on;
    }
}

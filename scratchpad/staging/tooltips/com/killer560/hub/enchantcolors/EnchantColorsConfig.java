package com.killer560.hub.enchantcolors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persisted "Enchant Colours" settings - see {@link EnchantColorsFeature}. Ships disabled by default.
 *  <p>
 *  Gated on {@link SkyblockGate} because this reads Hypixel's own lore text and Hypixel's
 *  {@code ExtraAttributes} NBT: outside Skyblock there is nothing for it to match, so it should follow
 *  "Skyblock Only" like every other lore-parsing feature. (Scrollable Tooltips deliberately is not - see
 *  {@code TooltipScrollConfig}.) */
public final class EnchantColorsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-enchantcolors.json");

    private static EnchantColorsConfig instance;

    private boolean enabled = false;
    /** killer560: "an option to colour only configured enchantments vs recolouring all". When on, an enchant
     *  with no entry in {@link #colors} is left exactly as Hypixel sent it. */
    private boolean onlyConfigured = true;
    /** Colour given to a recognised enchant with no entry of its own, while {@link #onlyConfigured} is off. */
    private int defaultColor = EnchantColorsDefaults.UNLISTED;
    private boolean ultimateEnabled = true;
    private int ultimateColor = EnchantColorsDefaults.ULTIMATE;
    /** SkyHanni always renders ultimates bold; kept as a toggle rather than hardcoded. */
    private boolean ultimateBold = true;
    /** Lore-normalised enchant name -> ARGB. Insertion-ordered so the settings list doesn't reshuffle. */
    private Map<String, Integer> colors = EnchantColorsDefaults.build();

    private EnchantColorsConfig() {
    }

    public static EnchantColorsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            // No file at all means a first run: seed the table. An EXISTING file is always honoured exactly,
            // even with an empty "colors" object, so deleting every default sticks instead of coming back.
            instance = new EnchantColorsConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            EnchantColorsConfig cfg = new EnchantColorsConfig();
            // Per-key reads (util/ConfigJson): one bad value must not reset the whole file to defaults.
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.onlyConfigured = ConfigJson.getBool(obj, "onlyConfigured", true);
            cfg.defaultColor = ConfigJson.getInt(obj, "defaultColor", EnchantColorsDefaults.UNLISTED);
            cfg.ultimateEnabled = ConfigJson.getBool(obj, "ultimateEnabled", true);
            cfg.ultimateColor = ConfigJson.getInt(obj, "ultimateColor", EnchantColorsDefaults.ULTIMATE);
            cfg.ultimateBold = ConfigJson.getBool(obj, "ultimateBold", true);
            JsonObject colors = ConfigJson.getObject(obj, "colors");
            if (colors != null) {
                Map<String, Integer> parsed = new LinkedHashMap<>();
                for (String key : colors.keySet()) {
                    String name = EnchantColorsFeature.normalize(key);
                    if (!name.isEmpty()) {
                        parsed.put(name, ConfigJson.getInt(colors, key, EnchantColorsDefaults.UNLISTED));
                    }
                }
                cfg.colors = parsed;
            }
            instance = cfg;
        } catch (Exception e) {
            instance = new EnchantColorsConfig();
        }
        EnchantColorsFeature.clearCache();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("onlyConfigured", onlyConfigured);
            obj.addProperty("defaultColor", defaultColor);
            obj.addProperty("ultimateEnabled", ultimateEnabled);
            obj.addProperty("ultimateColor", ultimateColor);
            obj.addProperty("ultimateBold", ultimateBold);
            JsonObject colors = new JsonObject();
            for (Map.Entry<String, Integer> e : this.colors.entrySet()) {
                colors.addProperty(e.getKey(), e.getValue());
            }
            obj.add("colors", colors);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        // Every rendered tooltip is cached by lore identity, so an edit has to invalidate it or the new
        // colour wouldn't show until you hovered something else (SkyHanni hits the same problem and solves
        // it with a "configChanged" dirty flag on every colour option).
        EnchantColorsFeature.clearCache();
    }

    /** Real value, ignoring "Skyblock Only" - for the settings screen, which must show what is actually on. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isOnlyConfigured() {
        return onlyConfigured;
    }

    public void setOnlyConfigured(boolean onlyConfigured) {
        this.onlyConfigured = onlyConfigured;
    }

    public int getDefaultColor() {
        return defaultColor;
    }

    public void setDefaultColor(int defaultColor) {
        this.defaultColor = defaultColor;
    }

    public boolean isUltimateEnabled() {
        return ultimateEnabled;
    }

    public void setUltimateEnabled(boolean ultimateEnabled) {
        this.ultimateEnabled = ultimateEnabled;
    }

    public int getUltimateColor() {
        return ultimateColor;
    }

    public void setUltimateColor(int ultimateColor) {
        this.ultimateColor = ultimateColor;
    }

    public boolean isUltimateBold() {
        return ultimateBold;
    }

    public void setUltimateBold(boolean ultimateBold) {
        this.ultimateBold = ultimateBold;
    }

    public Map<String, Integer> getColors() {
        return Collections.unmodifiableMap(colors);
    }

    /** @return the override for this lore name, or null. */
    public Integer getColor(String normalizedName) {
        return colors.get(normalizedName);
    }

    /** Adds or updates one override. Caller still has to {@link #save()}. */
    public void putColor(String name, int argb) {
        String key = EnchantColorsFeature.normalize(name);
        if (!key.isEmpty()) {
            colors.put(key, argb);
        }
    }

    public void removeColor(String normalizedName) {
        colors.remove(normalizedName);
    }

    public void resetColorsToDefaults() {
        colors = EnchantColorsDefaults.build();
    }
}

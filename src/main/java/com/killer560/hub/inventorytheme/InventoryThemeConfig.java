package com.killer560.hub.inventorytheme;

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

/** Persisted "Custom Inventory Overlay" settings (killer560's item 5.8: "Custom inventory overlay in
 *  the mod's theme") - re-skins the vanilla chest/inventory GUI (background, per-slot backdrops, hover
 *  highlight, title text) to match the mod's own Amber look instead of Minecraft's stone texture, the
 *  same kind of thing SkyHanni/Odin already do. Follows the same per-key {@link ConfigJson} load/save
 *  shape every neighbouring {@code XyzConfig} in this mod uses, so a single malformed/missing key never
 *  resets the whole file. OFF by default per the New-tab rule - killer560 hasn't confirmed this live. */
public final class InventoryThemeConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-inventorytheme.json");

    /** Amber accent (matches {@code ModScreen}'s own header/underline color) - the accent color's
     *  "follow the mod theme" default. */
    public static final int THEME_ACCENT = 0xFFCC6600;

    public static final float MIN_OPACITY = 0.2f;
    public static final float MAX_OPACITY = 1.0f;

    private static volatile InventoryThemeConfig instance;

    private boolean enabled = false;
    /** Per killer560's brief: "whether to theme Hypixel menus only or every container" - defaults to
     *  the narrower, safer scope (only hypixel.net/p3sim.net) rather than reskinning every vanilla
     *  singleplayer/creative chest the moment this is turned on. */
    private boolean hypixelOnly = true;
    private float backgroundOpacity = 0.85f;
    private boolean useCustomAccent = false;
    private int customAccentColor = THEME_ACCENT;

    private InventoryThemeConfig() {
    }

    public static InventoryThemeConfig getInstance() {
        InventoryThemeConfig cfg = instance;
        if (cfg == null) {
            load();
            cfg = instance;
        }
        return cfg;
    }

    public static void load() {
        InventoryThemeConfig cfg = new InventoryThemeConfig();
        try {
            if (Files.exists(CONFIG_PATH)) {
                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
                cfg.hypixelOnly = ConfigJson.getBool(obj, "hypixelOnly", cfg.hypixelOnly);
                cfg.backgroundOpacity = clampOpacity(ConfigJson.getFloat(obj, "backgroundOpacity", cfg.backgroundOpacity));
                cfg.useCustomAccent = ConfigJson.getBool(obj, "useCustomAccent", cfg.useCustomAccent);
                cfg.customAccentColor = ConfigJson.getInt(obj, "customAccentColor", cfg.customAccentColor);
            }
        } catch (Exception ignored) {
            // Unparseable file: keep defaults for this session (per-key reads above handle single bad keys).
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("hypixelOnly", hypixelOnly);
            obj.addProperty("backgroundOpacity", backgroundOpacity);
            obj.addProperty("useCustomAccent", useCustomAccent);
            obj.addProperty("customAccentColor", customAccentColor);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Same mod-wide "Skyblock Only" gate every other feature's {@code isEnabled()} respects. */
    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isHypixelOnly() {
        return hypixelOnly;
    }

    public void setHypixelOnly(boolean hypixelOnly) {
        this.hypixelOnly = hypixelOnly;
    }

    public float getBackgroundOpacity() {
        return backgroundOpacity;
    }

    public void setBackgroundOpacity(float backgroundOpacity) {
        this.backgroundOpacity = clampOpacity(backgroundOpacity);
    }

    private static float clampOpacity(float value) {
        return Math.max(MIN_OPACITY, Math.min(MAX_OPACITY, value));
    }

    public boolean isUseCustomAccent() {
        return useCustomAccent;
    }

    public void setUseCustomAccent(boolean useCustomAccent) {
        this.useCustomAccent = useCustomAccent;
    }

    public int getCustomAccentColor() {
        return customAccentColor;
    }

    public void setCustomAccentColor(int customAccentColor) {
        this.customAccentColor = customAccentColor;
    }

    /** The color everything this feature draws (panel outline, slot backdrops, hover highlight, title
     *  text) actually uses - the custom override once set, otherwise {@link #THEME_ACCENT} so it follows
     *  the mod's own Amber theme by default. */
    public int getAccentColor() {
        return useCustomAccent ? customAccentColor : THEME_ACCENT;
    }
}

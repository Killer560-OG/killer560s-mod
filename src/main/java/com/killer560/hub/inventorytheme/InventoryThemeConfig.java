package com.killer560.hub.inventorytheme;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.gui.PanelTheme;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;

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
            ModPaths.config("killer560smod-inventorytheme.json");

    /** Amber accent (matches {@code ModScreen}'s own header/underline color) - the accent color's
     *  "follow the mod theme" default. */
    public static final int THEME_ACCENT = 0xFFCC6600;

    public static final float MIN_OPACITY = 0.2f;
    public static final float MAX_OPACITY = 1.0f;

    /** Border / grid line width in GUI units (killer560, 2026-10-07: "a line width slider"). 1 is the look before
     *  the slider existed; 0 draws no lines at all. The tab's slider spans exactly this range. */
    public static final int MIN_LINE_WIDTH = 0;
    public static final int MAX_LINE_WIDTH = 4;
    /** Hotbar scale (killer560, 2026-10-07: "a custom scale option"), about the hotbar's bottom centre. */
    public static final float MIN_HOTBAR_SCALE = 0.5f;
    public static final float MAX_HOTBAR_SCALE = 2.0f;

    private static volatile InventoryThemeConfig instance;

    private boolean enabled = false;
    /** Per killer560's brief: "whether to theme Hypixel menus only or every container" - defaults to
     *  the narrower, safer scope (only hypixel.net/p3sim.net) rather than reskinning every vanilla
     *  singleplayer/creative chest the moment this is turned on. */
    private boolean hypixelOnly = true;
    private float backgroundOpacity = 0.85f;
    private boolean useCustomAccent = false;
    private int customAccentColor = THEME_ACCENT;
    /** killer560: "Add a setting to hide the effects in your inventory" - independent of {@link #enabled}
     *  (the reskin itself), so it keeps working with the reskin off. OFF by default so nothing changes
     *  for anyone until they turn it on. */
    private boolean hidePotionEffects = false;
    /** Amber (the look before themes existed), Dark or Light - the same three the Storage Overlay offers. */
    private PanelTheme theme = PanelTheme.AMBER;
    /** killer560, 2026-10-07: "Make the custom inventory also apply to my normal toolbar." ON by default, but only
     *  ever acts while the Inventory Theme itself is on (which is OFF by default). */
    private boolean themeHotbar = true;
    private float hotbarScale = 1.0f;
    private int lineWidth = 1;
    /** Slot backdrop recolour (killer560, 2026-10-07: "an option to recolor it"): off follows the theme. */
    private boolean useCustomSlotColor = false;
    private int customSlotColor = PanelTheme.AMBER.invSlotBg;

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
                cfg.hidePotionEffects = ConfigJson.getBool(obj, "hidePotionEffects", cfg.hidePotionEffects);
                cfg.theme = PanelTheme.parse(ConfigJson.getString(obj, "theme", null), cfg.theme);
                cfg.themeHotbar = ConfigJson.getBool(obj, "themeHotbar", cfg.themeHotbar);
                cfg.hotbarScale = clampHotbarScale(ConfigJson.getFloat(obj, "hotbarScale", cfg.hotbarScale));
                cfg.lineWidth = clampLineWidth(ConfigJson.getInt(obj, "lineWidth", cfg.lineWidth));
                cfg.useCustomSlotColor = ConfigJson.getBool(obj, "useCustomSlotColor", cfg.useCustomSlotColor);
                cfg.customSlotColor = ConfigJson.getInt(obj, "customSlotColor", cfg.customSlotColor);
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
            obj.addProperty("hidePotionEffects", hidePotionEffects);
            obj.addProperty("theme", theme.name());
            obj.addProperty("themeHotbar", themeHotbar);
            obj.addProperty("hotbarScale", hotbarScale);
            obj.addProperty("lineWidth", lineWidth);
            obj.addProperty("useCustomSlotColor", useCustomSlotColor);
            obj.addProperty("customSlotColor", customSlotColor);
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

    /** Same mod-wide "Skyblock Only" gate every other feature's gated getter respects. */
    public boolean isHidePotionEffects() {
        return hidePotionEffects && SkyblockGate.allows();
    }

    public void setHidePotionEffects(boolean hidePotionEffects) {
        this.hidePotionEffects = hidePotionEffects;
    }

    /** The color everything this feature draws (panel outline, slot outlines, hover highlight, selected hotbar slot,
     *  title text) actually uses - the custom override once set, otherwise the theme's own accent ({@link #THEME_ACCENT}
     *  for Amber, the default). */
    public int getAccentColor() {
        return useCustomAccent ? customAccentColor : theme.invAccent;
    }

    public PanelTheme getTheme() {
        return theme;
    }

    public void setTheme(PanelTheme theme) {
        this.theme = theme == null ? PanelTheme.AMBER : theme;
    }

    public boolean isThemeHotbar() {
        return themeHotbar;
    }

    public void setThemeHotbar(boolean themeHotbar) {
        this.themeHotbar = themeHotbar;
    }

    public float getHotbarScale() {
        return hotbarScale;
    }

    public void setHotbarScale(float hotbarScale) {
        this.hotbarScale = clampHotbarScale(hotbarScale);
    }

    private static float clampHotbarScale(float value) {
        if (Float.isNaN(value)) {
            return 1.0f;
        }
        return Math.max(MIN_HOTBAR_SCALE, Math.min(MAX_HOTBAR_SCALE, value));
    }

    public int getLineWidth() {
        return lineWidth;
    }

    public void setLineWidth(int lineWidth) {
        this.lineWidth = clampLineWidth(lineWidth);
    }

    private static int clampLineWidth(int value) {
        return Math.max(MIN_LINE_WIDTH, Math.min(MAX_LINE_WIDTH, value));
    }

    public boolean isUseCustomSlotColor() {
        return useCustomSlotColor;
    }

    public void setUseCustomSlotColor(boolean useCustomSlotColor) {
        this.useCustomSlotColor = useCustomSlotColor;
    }

    public int getCustomSlotColor() {
        return customSlotColor;
    }

    public void setCustomSlotColor(int customSlotColor) {
        this.customSlotColor = customSlotColor;
    }

    /** The slot backdrop colour actually drawn: the custom one when set, else the theme's. */
    public int getSlotColor() {
        return useCustomSlotColor ? customSlotColor : theme.invSlotBg;
    }

    /** The panel fill colour (opaque; Background Opacity supplies the alpha). */
    public int getPanelColor() {
        return theme.invPanelBg;
    }
}

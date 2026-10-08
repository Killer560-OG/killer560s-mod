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

    /** Border / grid line width in real SCREEN PIXELS (killer560, 2026-10-07: "right now 1 is far too large, I want to
     *  be able to make it thinner"), so 1 is one physical pixel at any GUI scale; 0 draws no lines at all. It was GUI
     *  units until then (0-4, so 1 drew 2 pixels at GUI 2): a file with only the old key is migrated by
     *  {@link #getLineWidth()} at the GUI scale it is first drawn at, so its look does not change. The tab's slider spans
     *  exactly this range. */
    public static final int MIN_LINE_WIDTH = 0;
    public static final int MAX_LINE_WIDTH = 12;
    /** New installs: two pixels, the old default (one unit) at GUI 2. */
    public static final int DEFAULT_LINE_WIDTH = 2;
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
    private int lineWidth = DEFAULT_LINE_WIDTH;
    /** A width in GUI units from a file written before pixels ({@code lineWidth} without {@code lineWidthPx}), waiting
     *  for a GUI scale to convert it; -1 when there is none. */
    private int legacyLineUnits = -1;
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
                if (obj.has("lineWidthPx")) {
                    cfg.lineWidth = clampLineWidth(ConfigJson.getInt(obj, "lineWidthPx", cfg.lineWidth));
                } else if (obj.has("lineWidth")) {
                    // Saved in GUI units by a jar from before pixels: converted on first use (getLineWidth), at the
                    // GUI scale it is drawn at, so 1 unit at GUI 3 stays 3 pixels.
                    cfg.legacyLineUnits = Math.max(0, Math.min(4, ConfigJson.getInt(obj, "lineWidth", 1)));
                }
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
            int gs = currentGuiScale();
            if (legacyLineUnits >= 0 && gs <= 0) {
                // Still unconverted and no GUI scale to convert at: keep the old units as they were.
                obj.addProperty("lineWidth", legacyLineUnits);
            } else {
                int px = getLineWidth();
                obj.addProperty("lineWidthPx", px);
                // The old key, in GUI units at the current scale, for an older jar reading this file (it clamps to 0-4).
                obj.addProperty("lineWidth", px == 0 ? 0 : Math.max(1, Math.round(px / (float) Math.max(1, gs))));
            }
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

    /** Line Width in screen pixels. A width still in old GUI units is converted here, once, at the current GUI scale
     *  (the pixels it was drawing at), and saved. */
    public int getLineWidth() {
        if (legacyLineUnits >= 0) {
            int gs = currentGuiScale();
            if (gs > 0) {
                lineWidth = clampLineWidth(legacyLineUnits * gs);
                legacyLineUnits = -1;
                save();
            } else {
                return clampLineWidth(legacyLineUnits * 2);
            }
        }
        return lineWidth;
    }

    public void setLineWidth(int lineWidth) {
        this.legacyLineUnits = -1;
        this.lineWidth = clampLineWidth(lineWidth);
    }

    /** The window's GUI scale, or 0 before there is a window (or before it has one). */
    private static int currentGuiScale() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            return mc == null || mc.getWindow() == null ? 0 : Math.max(0, mc.getWindow().getGuiScale());
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
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

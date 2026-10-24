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
 *  shape every neighboring {@code XyzConfig} in this mod uses, so a single malformed/missing key never
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
    /** Hotbar scale (killer560, 2026-10-07: "a custom scale option"), about the hotbar's bottom center. */
    public static final float MIN_HOTBAR_SCALE = 0.5f;
    public static final float MAX_HOTBAR_SCALE = 2.0f;

    /**
     * The presets of 2026-10-12 ("Default ... the normal vanilla inventory colours", "Dark", "Mod Theme", "Custom"). Since
     * 2026-10-18 (killer560) the look is ONE Theme choice stored under {@link #CHOICE_ID} in {@code ScreenThemes}; this enum
     * is only read, once, to carry a saved preset over to it (Default was vanilla then, so it becomes Vanilla).
     */
    public enum Preset {
        DEFAULT("Default"), DARK("Dark"), MOD_THEME("Mod Theme"), CUSTOM("Custom");

        public final String label;

        Preset(String label) {
            this.label = label;
        }
    }

    /** The Theme choice's id in {@code ScreenThemes}/{@code OverlayThemes} (the Inventory Theme's own section). */
    public static final String CHOICE_ID = com.killer560.hub.gui.OverlayThemes.INVENTORY_THEME;
    /** Theme choice: vanilla's own grays. */
    public static final String VANILLA_CHOICE = "VANILLA";
    /** Theme choice: Custom - the Customize screen's colors. */
    public static final String CUSTOM_CHOICE = "INVENTORY_CUSTOM";

    private static volatile InventoryThemeConfig instance;

    private boolean enabled = false;
    /** Per killer560's brief: "whether to theme Hypixel menus only or every container" - defaults to
     *  the narrower, safer scope (only hypixel.net/p3sim.net) rather than reskinning every vanilla
     *  singleplayer/creative chest the moment this is turned on. */
    private boolean hypixelOnly = true;
    private float backgroundOpacity = 0.85f;
    private boolean useCustomAccent = false;
    private int customAccentColor = THEME_ACCENT;
    // Hide Potion Effects stopped being a setting on 2026-10-18 (killer560: "no longer needs to exist just make it always
    // on"): the effects panel is hidden whenever the Inventory Theme is on. A "hidePotionEffects" key in an old file is ignored.
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
    /** Slot backdrop recolor (killer560, 2026-10-07: "an option to recolor it"): off follows the theme. */
    private boolean useCustomSlotColor = false;
    private int customSlotColor = PanelTheme.AMBER.invSlotBg;
    /** True once the old preset has been carried over to the Theme choice (written to the file as themeChoiceMigrated). */
    private boolean choiceMigrated = false;

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
        cfg.choiceMigrated = true; // a new install has nothing to carry over
        boolean migrate = false;
        try {
            if (Files.exists(CONFIG_PATH)) {
                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
                cfg.hypixelOnly = ConfigJson.getBool(obj, "hypixelOnly", cfg.hypixelOnly);
                cfg.backgroundOpacity = clampOpacity(ConfigJson.getFloat(obj, "backgroundOpacity", cfg.backgroundOpacity));
                cfg.useCustomAccent = ConfigJson.getBool(obj, "useCustomAccent", cfg.useCustomAccent);
                cfg.customAccentColor = ConfigJson.getInt(obj, "customAccentColor", cfg.customAccentColor);
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
                cfg.choiceMigrated = ConfigJson.getBool(obj, "themeChoiceMigrated", false);
                if (!cfg.choiceMigrated) {
                    // A file from before the Theme choice: carry its preset over, unless a Menu Theme was already picked
                    // (that one won in the old getPalette too). Default meant vanilla colors then.
                    if (com.killer560.hub.gui.ScreenThemes.DEFAULT.equals(com.killer560.hub.gui.ScreenThemes.get(CHOICE_ID))) {
                        Preset legacy = ConfigJson.getEnum(obj, "preset", Preset.class, Preset.DEFAULT);
                        String mapped = switch (legacy) {
                            case DEFAULT -> VANILLA_CHOICE;
                            case DARK -> com.killer560.hub.gui.MenuTheme.DARK.name();
                            case MOD_THEME -> com.killer560.hub.gui.MenuTheme.AMBER.name();
                            case CUSTOM -> CUSTOM_CHOICE;
                        };
                        com.killer560.hub.gui.ScreenThemes.set(CHOICE_ID, mapped);
                        com.killer560.hub.gui.ScreenThemes.save();
                    }
                    cfg.choiceMigrated = true;
                    migrate = true;
                }
            }
        } catch (Exception ignored) {
            // Unparseable file: keep defaults for this session (per-key reads above handle single bad keys).
        }
        instance = cfg;
        if (migrate) {
            cfg.save();
        }
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
            obj.addProperty("themeChoiceMigrated", true);
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
        this.paletteMemo = null;
    }

    public int getCustomAccentColor() {
        return customAccentColor;
    }

    public void setCustomAccentColor(int customAccentColor) {
        this.customAccentColor = customAccentColor;
        this.paletteMemo = null;
    }

    /** Always on while the Inventory Theme is (killer560, 2026-10-18); the same Skyblock Only gate as {@link #isEnabled()}. */
    public boolean isHidePotionEffects() {
        return isEnabled();
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
        this.paletteMemo = null;
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
        this.paletteMemo = null;
    }

    public int getCustomSlotColor() {
        return customSlotColor;
    }

    public void setCustomSlotColor(int customSlotColor) {
        this.customSlotColor = customSlotColor;
        this.paletteMemo = null;
    }

    /** The slot backdrop color actually drawn: the custom one when set, else the theme's. */
    public int getSlotColor() {
        return useCustomSlotColor ? customSlotColor : theme.invSlotBg;
    }

    /** The Theme choice: Default (the mod menu's theme), one of the menu's themes, Vanilla or Custom. */
    public String getChoice() {
        return com.killer560.hub.gui.ScreenThemes.get(CHOICE_ID);
    }

    public void setChoice(String choice) {
        com.killer560.hub.gui.ScreenThemes.set(CHOICE_ID, choice);
        com.killer560.hub.gui.ScreenThemes.save();
    }

    public boolean isVanilla() {
        return VANILLA_CHOICE.equals(getChoice());
    }

    public boolean isCustom() {
        return CUSTOM_CHOICE.equals(getChoice());
    }

    /** The menu's Dark theme is chosen (the hotbar's selection ring is toned down on it). */
    public boolean isDarkChoice() {
        return com.killer560.hub.gui.MenuTheme.DARK.name().equals(getChoice());
    }

    /** The shades everything is drawn in, from the Theme choice (killer560, 2026-10-18): Default follows the mod menu's
     *  theme, a named theme is that theme's colors, Vanilla is vanilla's grays, Custom is what the Customize screen
     *  sets (its Base / Slot Color / Accent Color). The Slot and Accent overrides apply to Custom only. */
    public InventoryPalette getPalette() {
        // Asked once per slot per frame: remembered until the Theme choice, the menu's colors or one of this config's own
        // color settings changes (the versions move on every such change), instead of four locked lookups and a new
        // palette (a record plus its derived shades) for each of ~90 slots.
        PaletteMemo memo = paletteMemo;
        int sv = com.killer560.hub.gui.ScreenThemes.version();
        int mv = com.killer560.hub.gui.MenuConfig.version();
        if (memo != null && memo.screenVersion == sv && memo.menuVersion == mv) {
            return memo.palette;
        }
        InventoryPalette p = computePalette();
        paletteMemo = new PaletteMemo(sv, mv, p);
        return p;
    }

    private record PaletteMemo(int screenVersion, int menuVersion, InventoryPalette palette) {
    }

    private volatile PaletteMemo paletteMemo;

    private InventoryPalette computePalette() {
        if (isVanilla()) {
            return InventoryPalette.DEFAULT;
        }
        if (isCustom()) {
            // Built from the Base's colors with the custom accent / slot color taking their place, so the accent also
            // colors the bevels.
            return InventoryPalette.custom(theme.invPanelBg, getSlotColor(), getAccentColor());
        }
        com.killer560.hub.gui.OverlayPalette menu = com.killer560.hub.gui.OverlayThemes.of(CHOICE_ID);
        if (menu == null) {
            menu = com.killer560.hub.gui.OverlayThemes.menu(); // Default: whatever the mod menu's theme is
        }
        return InventoryPalette.fromMenu(menu);
    }
}

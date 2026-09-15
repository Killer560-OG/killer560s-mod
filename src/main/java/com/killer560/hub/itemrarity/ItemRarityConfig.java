package com.killer560.hub.itemrarity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted "Item Rarity Backgrounds" settings - see {@link ItemRarityFeature}. Ships disabled by default.
 *  Visual only, so available on both the legit and cheat builds. */
public final class ItemRarityConfig {

    public enum Style {
        SQUARE("Square"),
        CIRCLE("Circle"),
        OUTLINE("Outline");

        public final String label;

        Style(String label) {
            this.label = label;
        }

        public Style next() {
            Style[] v = values();
            return v[(ordinal() + 1) % v.length];
        }

        static Style parse(String s) {
            for (Style st : values()) {
                if (st.name().equalsIgnoreCase(s)) {
                    return st;
                }
            }
            return SQUARE;
        }
    }

    public static final int MIN_OPACITY = 10;
    public static final int MAX_OPACITY = 100;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-itemrarity.json");

    private static ItemRarityConfig instance;

    private boolean enabled = false;
    private Style style = Style.SQUARE;
    /** Percent, {@link #MIN_OPACITY}..{@link #MAX_OPACITY}. */
    private int opacity = 50;
    private boolean showInHotbar = true;
    private boolean skyblockOnly = true;

    private ItemRarityConfig() {
    }

    public static ItemRarityConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new ItemRarityConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            ItemRarityConfig cfg = new ItemRarityConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.style = obj.has("style") ? Style.parse(obj.get("style").getAsString()) : Style.SQUARE;
            cfg.opacity = obj.has("opacity") ? clampOpacity(obj.get("opacity").getAsInt()) : 50;
            cfg.showInHotbar = !obj.has("showInHotbar") || obj.get("showInHotbar").getAsBoolean();
            cfg.skyblockOnly = !obj.has("skyblockOnly") || obj.get("skyblockOnly").getAsBoolean();
            instance = cfg;
        } catch (Exception e) {
            instance = new ItemRarityConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("style", style.name());
            obj.addProperty("opacity", opacity);
            obj.addProperty("showInHotbar", showInHotbar);
            obj.addProperty("skyblockOnly", skyblockOnly);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static int clampOpacity(int v) {
        return Math.max(MIN_OPACITY, Math.min(MAX_OPACITY, v));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Style getStyle() {
        return style;
    }

    public void setStyle(Style style) {
        this.style = style == null ? Style.SQUARE : style;
    }

    public int getOpacity() {
        return opacity;
    }

    public void setOpacity(int opacity) {
        this.opacity = clampOpacity(opacity);
    }

    public boolean isShowInHotbar() {
        return showInHotbar;
    }

    public void setShowInHotbar(boolean showInHotbar) {
        this.showInHotbar = showInHotbar;
    }

    public boolean isSkyblockOnly() {
        return skyblockOnly;
    }

    public void setSkyblockOnly(boolean skyblockOnly) {
        this.skyblockOnly = skyblockOnly;
    }
}

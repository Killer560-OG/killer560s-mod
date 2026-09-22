package com.killer560.hub.itemrarity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
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
    /** Outline style only: line thickness in GUI pixels (killer560, 2026-09-21). */
    private int outlineWidth = 1;
    public static final int MIN_OUTLINE_WIDTH = 1;
    public static final int MAX_OUTLINE_WIDTH = 4;
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
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.style = ConfigJson.getEnum(obj, "style", Style.class, Style.SQUARE);
            cfg.opacity = clampOpacity(ConfigJson.getInt(obj, "opacity", 50));
            cfg.showInHotbar = ConfigJson.getBool(obj, "showInHotbar", true);
            cfg.setOutlineWidth(ConfigJson.getInt(obj, "outlineWidth", 1));
            cfg.skyblockOnly = ConfigJson.getBool(obj, "skyblockOnly", true);
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
            obj.addProperty("outlineWidth", outlineWidth);
            obj.addProperty("skyblockOnly", skyblockOnly);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static int clampOpacity(int v) {
        return Math.max(MIN_OPACITY, Math.min(MAX_OPACITY, v));
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
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

    public int getOutlineWidth() {
        return outlineWidth;
    }

    public void setOutlineWidth(int v) {
        outlineWidth = Math.max(MIN_OUTLINE_WIDTH, Math.min(MAX_OUTLINE_WIDTH, v));
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

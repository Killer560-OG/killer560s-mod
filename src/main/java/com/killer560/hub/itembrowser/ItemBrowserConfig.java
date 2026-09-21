package com.killer560.hub.itembrowser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Item Browser settings - see {@link ItemBrowserFeature}. Ships disabled by default. */
public final class ItemBrowserConfig {

    /** Horizontal screen anchor for the panel - killer560 (2026-09-21): "make it so I can adjust... how
     *  it is centered as well". The panel is always full screen height (see {@link ItemBrowserFeature}),
     *  so only the horizontal anchor is meaningful. */
    public enum HorizontalAlign {
        LEFT("Left"), CENTER("Center"), RIGHT("Right");

        private final String label;

        HorizontalAlign(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public HorizontalAlign next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-itembrowser.json");

    public static final int MIN_COLUMNS = 3;
    public static final int MAX_COLUMNS = 20;
    public static final float MIN_SCALE = 0.5f;
    public static final float MAX_SCALE = 2.0f;

    private static ItemBrowserConfig instance;

    private boolean enabled = false;
    private int columns = 5;
    private float scale = 1.0f;
    /** Fill order of the grid - killer560 (2026-09-21): "make it so I can adjust if it is horizontal or
     *  vertical". True = fills left-to-right then wraps to the next row (row-major, scrolls a row at a
     *  time - the original behavior). False = fills top-to-bottom then wraps to the next column
     *  (column-major, scrolls a column at a time). Either way the grid is always {@link #columns} wide
     *  and always fills the full available screen height. */
    private boolean horizontal = true;
    private HorizontalAlign align = HorizontalAlign.RIGHT;

    private ItemBrowserConfig() {
    }

    public static ItemBrowserConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new ItemBrowserConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            ItemBrowserConfig cfg = new ItemBrowserConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            // Through the clamping setters so a hand-edited out-of-range value can't break the panel layout.
            cfg.setColumns(ConfigJson.getInt(obj, "columns", 5));
            cfg.setScale(ConfigJson.getFloat(obj, "scale", 1.0f));
            cfg.horizontal = ConfigJson.getBool(obj, "horizontal", true);
            cfg.align = ConfigJson.getEnum(obj, "align", HorizontalAlign.class, HorizontalAlign.RIGHT);
            instance = cfg;
        } catch (Exception e) {
            instance = new ItemBrowserConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("columns", columns);
            obj.addProperty("scale", scale);
            obj.addProperty("horizontal", horizontal);
            obj.addProperty("align", align.name());
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getColumns() {
        return columns;
    }

    public void setColumns(int columns) {
        this.columns = Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, columns));
    }

    public float getScale() {
        return scale;
    }

    public void setScale(float scale) {
        this.scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    public boolean isHorizontal() {
        return horizontal;
    }

    public void setHorizontal(boolean horizontal) {
        this.horizontal = horizontal;
    }

    public HorizontalAlign getAlign() {
        return align;
    }

    public void setAlign(HorizontalAlign align) {
        this.align = align == null ? HorizontalAlign.RIGHT : align;
    }
}

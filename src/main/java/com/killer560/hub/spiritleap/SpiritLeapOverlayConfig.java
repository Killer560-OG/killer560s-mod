package com.killer560.hub.spiritleap;

import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Custom Leap Menu settings - see {@link SpiritLeapOverlayFeature}'s class doc for the real
 *  Odin-ported overlay this is built on. Ships disabled by default, same as every other new feature in
 *  this mod. */
public final class SpiritLeapOverlayConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-spiritleapoverlay.json");

    /** Scale range of the custom leap menu (2026-09-15, killer560: "let the scale go up to 400%"). The renderer
     *  still shrinks the boxes to fit the window, so a big scale never pushes them off-screen. */
    public static final float MIN_SCALE = 0.5f;
    public static final float MAX_SCALE = 4.0f;

    private static SpiritLeapOverlayConfig instance;

    private boolean enabled = false;
    private boolean useClassColors = true;
    private float scale = 1.0f;
    /** Each box shows the teammate's face (2026-10-11, killer560: "leap menu should have an option to show their
     *  player head"). */
    private boolean showHeads = true;
    /** Boxes everywhere, or the dungeon map with clickable heads during the clear (boxes again in boss). */
    private LeapStyle style = LeapStyle.BOXES;

    /** How the custom leap menu lays out its targets. */
    public enum LeapStyle {
        BOXES("Boxes"),
        MAP_IN_CLEAR("Map in Clear");

        private final String label;

        LeapStyle(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private SpiritLeapOverlayConfig() {
    }

    public static SpiritLeapOverlayConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new SpiritLeapOverlayConfig();
            return;
        }
        JsonObject obj;
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            obj = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            // Only a file that does not parse at all lands here: keep a copy before a save writes defaults over it.
            ConfigJson.backupBroken(CONFIG_PATH);
            instance = new SpiritLeapOverlayConfig();
            return;
        }
        // Each key on its own (ConfigJson): one malformed value falls back to its own default, the rest still load.
        SpiritLeapOverlayConfig cfg = new SpiritLeapOverlayConfig();
        cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
        cfg.useClassColors = ConfigJson.getBool(obj, "useClassColors", true);
        cfg.setScale(ConfigJson.getFloat(obj, "scale", 1.0f));
        cfg.showHeads = ConfigJson.getBool(obj, "showHeads", true);
        cfg.style = ConfigJson.getEnum(obj, "style", LeapStyle.class, cfg.style);
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("useClassColors", useClassColors);
            obj.addProperty("scale", scale);
            obj.addProperty("showHeads", showHeads);
            obj.addProperty("style", style.name());
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

    public boolean isUseClassColors() {
        return useClassColors;
    }

    public void setUseClassColors(boolean useClassColors) {
        this.useClassColors = useClassColors;
    }

    public boolean isShowHeads() {
        return showHeads;
    }

    public void setShowHeads(boolean showHeads) {
        this.showHeads = showHeads;
    }

    public LeapStyle getStyle() {
        return style;
    }

    public void setStyle(LeapStyle style) {
        this.style = style == null ? LeapStyle.BOXES : style;
    }

    public float getScale() {
        return scale;
    }

    public void setScale(float scale) {
        this.scale = Float.isFinite(scale) ? Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale)) : 1.0f;
    }
}

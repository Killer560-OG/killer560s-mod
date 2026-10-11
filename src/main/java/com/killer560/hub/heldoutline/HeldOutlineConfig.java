package com.killer560.hub.heldoutline;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Held Item Outline (killer560, 2026-10-09): "outline the item you are currently holding in your hand, not in the HUD,
 * not on your scrollbar, but the actual item (first-person held item model). It should have an option for outline,
 * fill, or filled outline, where you can set the color and whatnot." Purely visual, legit, OFF by default. Drawn by
 * {@link HeldOutlineRenderer}.
 */
public final class HeldOutlineConfig {

    public enum Style {
        OUTLINE("Outline", true, false),
        FILL("Fill", false, true),
        FILLED_OUTLINE("Filled Outline", true, true);

        public final String label;
        public final boolean outline;
        public final boolean fill;

        Style(String label, boolean outline, boolean fill) {
            this.label = label;
            this.outline = outline;
            this.fill = fill;
        }

        public Style next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /**
     * Which face of the held item the outline follows (killer560, 2026-10-11: "the outline is kind of on the far side of
     * the item. Can I have an option for close side far side or both with lines between the two at points"). A flat
     * item is an extruded sprite: a near face, a far face, and the thin walls between them.
     */
    public enum Side {
        NEAR("Near"),
        FAR("Far"),
        BOTH("Both");

        public final String label;

        Side(String label) {
            this.label = label;
        }

        public Side next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public static final int DEFAULT_OUTLINE_COLOR = 0xFFFF8C1A;
    public static final int DEFAULT_FILL_COLOR = 0x55FF8C1A;
    /** Outline width in screen (framebuffer) pixels. */
    public static final float MIN_WIDTH = 1.0f;
    public static final float MAX_WIDTH = 10.0f;
    public static final float DEFAULT_WIDTH = 3.0f;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-heldoutline.json");

    private static HeldOutlineConfig instance;

    private boolean enabled = false;
    private Style style = Style.OUTLINE;
    private Side side = Side.NEAR;
    private int outlineColor = DEFAULT_OUTLINE_COLOR;
    private int fillColor = DEFAULT_FILL_COLOR;
    private float width = DEFAULT_WIDTH;
    private boolean mainHand = true;
    private boolean offHand = false;

    private HeldOutlineConfig() {
    }

    public static HeldOutlineConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    /** The saved toggle alone. NOT gated by "Skyblock Only" (killer560, 2026-10-11: "It should ALWAYS show when it's
     *  on"): it is a cosmetic on your own screen, so it draws on any server or in singleplayer. */
    public boolean isActive() {
        return enabled;
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public Style getStyle() {
        return style;
    }

    public void setStyle(Style v) {
        style = v == null ? Style.OUTLINE : v;
    }

    public Side getSide() {
        return side;
    }

    public void setSide(Side v) {
        side = v == null ? Side.NEAR : v;
    }

    public int getOutlineColor() {
        return outlineColor;
    }

    public void setOutlineColor(int v) {
        outlineColor = v;
    }

    public int getFillColor() {
        return fillColor;
    }

    public void setFillColor(int v) {
        fillColor = v;
    }

    public float getWidth() {
        return width;
    }

    public void setWidth(float v) {
        width = clampWidth(v);
    }

    public boolean isMainHand() {
        return mainHand;
    }

    public void setMainHand(boolean v) {
        mainHand = v;
    }

    public boolean isOffHand() {
        return offHand;
    }

    public void setOffHand(boolean v) {
        offHand = v;
    }

    private static float clampWidth(float v) {
        if (!Float.isFinite(v)) {
            return DEFAULT_WIDTH;
        }
        return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, v));
    }

    public static void load() {
        HeldOutlineConfig cfg = new HeldOutlineConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(o, "enabled", cfg.enabled);
                String s = ConfigJson.getString(o, "style", cfg.style.name());
                for (Style st : Style.values()) {
                    if (st.name().equalsIgnoreCase(s)) {
                        cfg.style = st;
                    }
                }
                String sd = ConfigJson.getString(o, "side", cfg.side.name());
                for (Side v : Side.values()) {
                    if (v.name().equalsIgnoreCase(sd)) {
                        cfg.side = v;
                    }
                }
                cfg.outlineColor = ConfigJson.getInt(o, "outlineColor", cfg.outlineColor);
                cfg.fillColor = ConfigJson.getInt(o, "fillColor", cfg.fillColor);
                cfg.width = clampWidth(ConfigJson.getFloat(o, "width", cfg.width));
                cfg.mainHand = ConfigJson.getBool(o, "mainHand", cfg.mainHand);
                cfg.offHand = ConfigJson.getBool(o, "offHand", cfg.offHand);
            } catch (Exception e) {
                cfg = new HeldOutlineConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("style", style.name());
            o.addProperty("side", side.name());
            o.addProperty("outlineColor", outlineColor);
            o.addProperty("fillColor", fillColor);
            o.addProperty("width", width);
            o.addProperty("mainHand", mainHand);
            o.addProperty("offHand", offHand);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}

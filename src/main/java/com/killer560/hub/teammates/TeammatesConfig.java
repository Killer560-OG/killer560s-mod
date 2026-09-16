package com.killer560.hub.teammates;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Teammate Highlight settings - see {@link TeammatesFeature}. Ships disabled by default, and every
 * sub-toggle off/neutral, so nothing changes until killer560 turns it on.
 * <p>
 * Layout copied from {@code mobesp.MobEspConfig} (same style enum shape, same line-width/range sliders, same
 * {@code ...Raw()} getters for tab labels so a cheat-only setting still shows its stored value on the legit jar).
 */
public final class TeammatesConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-teammates.json");

    /** No Glow option: vanilla's entity outline is driven by the {@code cheatutils} WitherGlow mixins, which only ask
     *  {@code MobEspFeature}. Adding Glow here needs a one-line hook in those mixins - see this feature's report. */
    public enum Style {
        OUTLINE("Outline Box"), FILLED("Filled Box");

        public final String label;

        Style(String label) {
            this.label = label;
        }
    }

    public static final int DEFAULT_UNKNOWN_COLOR = 0xFFFFFFFF;
    public static final float MIN_LINE_WIDTH = 1.0f;
    public static final float MAX_LINE_WIDTH = 10.0f;
    public static final double MIN_RANGE = 5.0;
    public static final double MAX_RANGE = 128.0;

    private static TeammatesConfig instance;

    private boolean enabled = false;
    private Style style = Style.OUTLINE;
    private float lineWidth = 2.0f;
    private double range = 64.0;
    /** Devonian HighlightTeammates SETTING_SELF ("None"/"Glow"/"Box") - kept as a plain on/off here since there is
     *  no Glow style; off by default, like Devonian's own default of not boxing yourself. */
    private boolean highlightSelf = false;
    private boolean showName = false;
    private boolean showDistance = false;
    /** Colour used for a teammate whose dungeon class isn't known yet (tab list not parsed, or outside a dungeon). */
    private int unknownColor = DEFAULT_UNKNOWN_COLOR;
    /** Tab list shows them as "(DEAD)" - {@code PartyTracker.isDead}. On by default: a dead teammate is a ghost. */
    private boolean skipDead = true;
    /** Cheat build only - see {@link #isThroughWalls()}. */
    private boolean throughWalls = false;

    private TeammatesConfig() {
    }

    public static TeammatesConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new TeammatesConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            TeammatesConfig cfg = new TeammatesConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.style = ConfigJson.getEnum(obj, "style", Style.class, Style.OUTLINE);
            cfg.lineWidth = ConfigJson.getFloat(obj, "lineWidth", 2.0f);
            cfg.range = ConfigJson.getDouble(obj, "range", 64.0);
            cfg.highlightSelf = ConfigJson.getBool(obj, "highlightSelf", false);
            cfg.showName = ConfigJson.getBool(obj, "showName", false);
            cfg.showDistance = ConfigJson.getBool(obj, "showDistance", false);
            cfg.unknownColor = ConfigJson.getInt(obj, "unknownColor", DEFAULT_UNKNOWN_COLOR);
            cfg.skipDead = ConfigJson.getBool(obj, "skipDead", true);
            cfg.throughWalls = ConfigJson.getBool(obj, "throughWalls", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new TeammatesConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("style", style.name());
            obj.addProperty("lineWidth", lineWidth);
            obj.addProperty("range", range);
            obj.addProperty("highlightSelf", highlightSelf);
            obj.addProperty("showName", showName);
            obj.addProperty("showDistance", showDistance);
            obj.addProperty("unknownColor", unknownColor);
            obj.addProperty("skipDead", skipDead);
            obj.addProperty("throughWalls", throughWalls);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Gated on Skyblock like every other feature ({@code SkyblockGate.allows()}). */
    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    /** The stored value, ungated - for the settings tab label. */
    public boolean getEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Style getStyle() {
        return style;
    }

    public void cycleStyle() {
        Style[] all = Style.values();
        style = all[(style.ordinal() + 1) % all.length];
    }

    public float getLineWidth() {
        return lineWidth;
    }

    public void setLineWidth(float lineWidth) {
        this.lineWidth = Math.max(MIN_LINE_WIDTH, Math.min(MAX_LINE_WIDTH, lineWidth));
    }

    public double getRange() {
        return range;
    }

    public void setRange(double range) {
        this.range = Math.max(MIN_RANGE, Math.min(MAX_RANGE, range));
    }

    public boolean isHighlightSelf() {
        return highlightSelf;
    }

    public void setHighlightSelf(boolean highlightSelf) {
        this.highlightSelf = highlightSelf;
    }

    public boolean isShowName() {
        return showName;
    }

    public void setShowName(boolean showName) {
        this.showName = showName;
    }

    public boolean isShowDistance() {
        return showDistance;
    }

    public void setShowDistance(boolean showDistance) {
        this.showDistance = showDistance;
    }

    public int getUnknownColor() {
        return unknownColor;
    }

    public void setUnknownColor(int unknownColor) {
        this.unknownColor = unknownColor;
    }

    public boolean isSkipDead() {
        return skipDead;
    }

    public void setSkipDead(boolean skipDead) {
        this.skipDead = skipDead;
    }

    /** Legit (false / legit jar): a teammate is only highlighted while there is a clear line of sight to them right
     *  now and boxes are depth-tested. True: no line-of-sight check, boxes drawn without depth test. Cheat build
     *  only, exactly like {@code MobEspConfig.isThroughWalls()}. */
    public boolean isThroughWalls() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && throughWalls;
    }

    public boolean getThroughWallsRaw() {
        return throughWalls;
    }

    public void setThroughWalls(boolean throughWalls) {
        this.throughWalls = throughWalls;
    }
}

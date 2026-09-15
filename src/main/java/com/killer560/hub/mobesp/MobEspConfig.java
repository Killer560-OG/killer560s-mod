package com.killer560.hub.mobesp;

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
 * Persisted Dungeon ESP settings - see {@link MobEspFeature}. File name kept as {@code killer560smod-mobesp.json}
 * (the old "Mob Hitbox ESP" file) so existing saves carry over. Every target ships disabled by default.
 * <p>
 * Migration (one-shot, only for keys the file doesn't have yet, then saved straight away):
 * <ul>
 * <li>old mobesp {@code enabled} -> {@code starredMobs}; {@code colorHex} -> {@code starredColor};
 *     {@code cheatMode} -> {@code throughWalls}; an old file with no {@code style} keeps the Glow look it had.
 * <li>old {@code killer560smod-cheatutils.json} Wither ESP: {@code witherEspEnabled} -> {@code withers}, and the
 *     colour of the phase it was filtered to (Maxor/Storm/Goldor/Necron; "all phases" -> Maxor) ->
 *     {@code witherColor}. CheatUtilsConfig no longer reads or writes those keys.
 * </ul>
 */
public final class MobEspConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-mobesp.json");
    private static final Path OLD_CHEATUTILS_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-cheatutils.json");

    public enum Style {
        OUTLINE("Outline Box"), FILLED("Filled Box"), GLOW("Glow");

        public final String label;

        Style(String label) {
            this.label = label;
        }
    }

    public static final int DEFAULT_STARRED_COLOR = 0xFFFFD700;
    public static final int DEFAULT_BAT_COLOR = 0xFF55FF55;
    public static final int DEFAULT_WITHER_COLOR = 0xFFFF0000;
    public static final float MIN_LINE_WIDTH = 1.0f;
    public static final float MAX_LINE_WIDTH = 10.0f;
    public static final double MIN_RANGE = 5.0;
    public static final double MAX_RANGE = 128.0;

    private static MobEspConfig instance;

    private boolean starredMobs = false;
    private boolean bats = false;
    /** Cheat build only (was Cheat Utils' Wither ESP) - see {@link #isWithersEnabled()}. */
    private boolean withers = false;
    private int starredColor = DEFAULT_STARRED_COLOR;
    private int batColor = DEFAULT_BAT_COLOR;
    private int witherColor = DEFAULT_WITHER_COLOR;
    private Style style = Style.OUTLINE;
    private float lineWidth = 2.0f;
    /** Legit (false / legit jar): a target is only highlighted while there's a clear line of sight to it and boxes are
     *  depth-tested. True: no line-of-sight check, boxes drawn without depth test. Cheat build only. */
    private boolean throughWalls = false;
    /** Starred mobs + bats only; the F7/M7 withers ignore it. */
    private double range = 40.0;

    private MobEspConfig() {
    }

    public static MobEspConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        MobEspConfig cfg = new MobEspConfig();
        boolean migrated = false;
        JsonObject obj = new JsonObject();
        if (Files.exists(CONFIG_PATH)) {
            try {
                obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                obj = new JsonObject();
            }
        }
        try {
            if (obj.has("starredMobs")) {
                cfg.starredMobs = ConfigJson.getBool(obj, "starredMobs", false);
            } else if (obj.has("enabled")) {
                cfg.starredMobs = ConfigJson.getBool(obj, "enabled", false);
                migrated = true;
            }
            cfg.bats = ConfigJson.getBool(obj, "bats", false);

            if (obj.has("starredColor")) {
                cfg.starredColor = ConfigJson.getInt(obj, "starredColor", DEFAULT_STARRED_COLOR);
            } else if (obj.has("colorHex")) {
                cfg.starredColor = parseHex(ConfigJson.getString(obj, "colorHex", "FFD700"), DEFAULT_STARRED_COLOR);
                migrated = true;
            }
            cfg.batColor = ConfigJson.getInt(obj, "batColor", DEFAULT_BAT_COLOR);

            if (obj.has("throughWalls")) {
                cfg.throughWalls = ConfigJson.getBool(obj, "throughWalls", false);
            } else if (obj.has("cheatMode")) {
                cfg.throughWalls = ConfigJson.getBool(obj, "cheatMode", false);
                migrated = true;
            }

            if (obj.has("style")) {
                cfg.style = ConfigJson.getEnum(obj, "style", Style.class, Style.OUTLINE);
            } else if (obj.has("enabled") || obj.has("colorHex")) {
                // Old Mob Hitbox ESP was glow-only - keep that look for anyone upgrading.
                cfg.style = Style.GLOW;
                migrated = true;
            }
            cfg.setLineWidth(ConfigJson.getFloat(obj, "lineWidth", 2.0f));
            cfg.setRange(ConfigJson.getDouble(obj, "range", 40.0));

            if (obj.has("withers")) {
                cfg.withers = ConfigJson.getBool(obj, "withers", false);
                cfg.witherColor = ConfigJson.getInt(obj, "witherColor", DEFAULT_WITHER_COLOR);
            } else {
                migrated |= migrateWitherEsp(cfg);
            }
        } catch (Exception e) {
            cfg = new MobEspConfig();
            migrated = false;
        }
        instance = cfg;
        if (migrated) {
            cfg.save();
        }
    }

    /** Pulls the old Cheat Utils Wither ESP enabled flag + colour, if that file still has them. */
    private static boolean migrateWitherEsp(MobEspConfig cfg) {
        if (!Files.exists(OLD_CHEATUTILS_PATH)) {
            return false;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(OLD_CHEATUTILS_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!o.has("witherEspEnabled")) {
                return false;
            }
            cfg.withers = ConfigJson.getBool(o, "witherEspEnabled", false);
            String colorKey = switch (ConfigJson.getString(o, "witherPhaseFilter", "P3")) {
                case "P2" -> "stormColor";
                case "P3" -> "goldorColor";
                case "P4" -> "necronColor";
                default -> "maxorColor";
            };
            if (o.has(colorKey)) {
                cfg.witherColor = ConfigJson.getInt(o, colorKey, DEFAULT_WITHER_COLOR);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int parseHex(String hex, int def) {
        try {
            String h = hex == null ? "" : hex.trim().replace("#", "");
            int rgb = (int) Long.parseLong(h, 16);
            return h.length() > 6 ? rgb : 0xFF000000 | rgb;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("starredMobs", starredMobs);
            obj.addProperty("bats", bats);
            obj.addProperty("withers", withers);
            obj.addProperty("starredColor", starredColor);
            obj.addProperty("batColor", batColor);
            obj.addProperty("witherColor", witherColor);
            obj.addProperty("style", style.name());
            obj.addProperty("lineWidth", lineWidth);
            obj.addProperty("throughWalls", throughWalls);
            obj.addProperty("range", range);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static boolean cheat() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED;
    }

    // ---- Targets (gated getters for the feature, raw getters for the tab's toggle labels) ----
    public boolean isStarredMobsEnabled() { return starredMobs && com.killer560.hub.util.SkyblockGate.allows(); }
    public boolean getStarredMobsRaw() { return starredMobs; }
    public void setStarredMobs(boolean v) { starredMobs = v; }

    public boolean isBatsEnabled() { return bats && com.killer560.hub.util.SkyblockGate.allows(); }
    public boolean getBatsRaw() { return bats; }
    public void setBats(boolean v) { bats = v; }

    /** Cheat build only, same as the Cheat Utils Wither ESP it replaces - a legit jar never reports true. */
    public boolean isWithersEnabled() { return cheat() && withers && com.killer560.hub.util.SkyblockGate.allows(); }
    public boolean getWithersRaw() { return withers; }
    public void setWithers(boolean v) { withers = v; }

    public boolean isAnyEnabled() {
        return isStarredMobsEnabled() || isBatsEnabled() || isWithersEnabled();
    }

    // ---- Colours ----
    public int getStarredColor() { return starredColor; }
    public void setStarredColor(int v) { starredColor = v; }
    public int getBatColor() { return batColor; }
    public void setBatColor(int v) { batColor = v; }
    public int getWitherColor() { return witherColor; }
    public void setWitherColor(int v) { witherColor = v; }

    // ---- Render ----
    public Style getStyle() { return style; }
    public void cycleStyle() {
        Style[] all = Style.values();
        style = all[(style.ordinal() + 1) % all.length];
    }

    public float getLineWidth() { return lineWidth; }
    public void setLineWidth(float v) {
        lineWidth = Math.max(MIN_LINE_WIDTH, Math.min(MAX_LINE_WIDTH, Math.round(v * 2.0f) / 2.0f));
    }

    /** Gated the same single-source-of-truth way as {@code ExperimentsConfig#isAutonomousMode} - the legit jar can never
     *  report true here even from a copied cheat-build config file. */
    public boolean isThroughWalls() { return cheat() && throughWalls; }
    public boolean getThroughWallsRaw() { return throughWalls; }
    public void setThroughWalls(boolean v) { throughWalls = v; }

    public double getRange() { return range; }
    public void setRange(double v) { range = Math.max(MIN_RANGE, Math.min(MAX_RANGE, Math.round(v))); }
}

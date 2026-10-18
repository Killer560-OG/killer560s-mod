package com.killer560.hub.pathfinding;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.SkyblockGate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Fairy Souls / Auto Fairy Souls settings ({@code killer560smod-pathfinding.json}). Both features ship OFF.
 * <p>
 * File version 2 (2026-10-17, killer560's Fairy Souls rework): the separate "Fairy Souls" switch under the old
 * "Pathfinding" one is gone (the tab switch is the feature; an old file with it OFF keeps its soul waypoints off),
 * "Dim Whole Route" is {@code dimPath}, Turn Speed is gone, Auto Walk is a part of Auto Fairy Souls that defaults ON,
 * and the Pearls Only mode is Walk Only plus Allow Ender Pearls.
 */
public final class PathfindingConfig {

    /** How auto fairy souls gets from soul to soul (cheat build only). */
    public enum AutoMode {
        /** Walking only: it never etherwarps (an Ender Pearl escape still needs Allow Ender Pearls). */
        WALK("Walk Only"),
        ETHERWARP("Etherwarp"),
        FAST_ETHERWARP("Fast Etherwarp");

        public final String label;

        AutoMode(String label) {
            this.label = label;
        }

        /** Etherwarps (shortcuts, chains and escapes) are allowed in this mode. */
        public boolean etherwarps() {
            return this != WALK;
        }
    }

    /** What the fairy soul guide points at. */
    public enum SoulMode {
        NEAREST("Nearest"),
        ROUTE("Whole Island");

        public final String label;

        SoulMode(String label) {
            this.label = label;
        }
    }

    public static final int DEFAULT_PATH_COLOR = 0xFFCC6600;
    public static final int DEFAULT_TARGET_COLOR = 0xFFFFA040;
    static final int FILE_VERSION = 2;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-pathfinding.json");

    private static PathfindingConfig instance;

    // Fairy Souls (legit)
    private boolean enabled = false;
    private boolean soulWaypoints = true;
    private boolean showPath = true;
    private SoulMode soulMode = SoulMode.ROUTE;
    private boolean dimPath = false;
    private int pathColor = DEFAULT_PATH_COLOR;
    private int visiblePathLength = 24;
    private float lineThickness = 3.0f;
    private double recalcDistance = 7.0;
    private float textScale = 1.0f;
    private boolean soulHud = true;
    private boolean autoStartOnIsland = false;
    private boolean showTargetLabel = true;
    private int targetColor = DEFAULT_TARGET_COLOR;
    private boolean chatFeedback = true;
    private double arriveDistance = 3.0;

    // Auto Fairy Souls (cheat build only)
    private boolean autoSouls = false;
    private boolean autoWalk = true;
    private boolean autoCollect = true;
    private AutoMode autoMode = AutoMode.WALK;
    private boolean allowPearls = false;
    private boolean autoSprint = true;
    private int resumeKeyCode = KeyUtil.NONE;

    private PathfindingConfig() {
    }

    public static PathfindingConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    /** Loads, then tells FeatureBits a gated setting may have changed (profile apply reloads every config). */
    public static void load() {
        load0();
        com.killer560.hub.util.FeatureBits.changed();
    }

    private static void load0() {
        PathfindingConfig cfg = new PathfindingConfig();
        instance = cfg;
        if (!Files.exists(CONFIG_PATH)) {
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            cfg.readFrom(obj);
        } catch (Exception ignored) {
            // per-key readers already fall back one key at a time; this only catches an unreadable file
        }
    }

    /** Reads every key, migrating a version-1 file (see the class doc). */
    void readFrom(JsonObject obj) {
        int version = ConfigJson.getInt(obj, "version", 1);
        enabled = ConfigJson.getBool(obj, "enabled", false);
        soulWaypoints = ConfigJson.getBool(obj, "soulWaypoints", true);
        showPath = ConfigJson.getBool(obj, "showPath", true);
        soulMode = ConfigJson.getEnum(obj, "soulMode", SoulMode.class, SoulMode.ROUTE);
        dimPath = obj.has("dimPath") ? ConfigJson.getBool(obj, "dimPath", false)
                : ConfigJson.getBool(obj, "showWholePath", false);
        pathColor = ConfigJson.getInt(obj, "pathColor", DEFAULT_PATH_COLOR);
        setVisiblePathLength(ConfigJson.getInt(obj, "visiblePathLength", 24));
        setLineThickness(ConfigJson.getFloat(obj, "lineThickness", 3.0f));
        setRecalcDistance(ConfigJson.getDouble(obj, "recalcDistance", 7.0));
        setTextScale(ConfigJson.getFloat(obj, "textScale", 1.0f));
        soulHud = ConfigJson.getBool(obj, "soulHud", true);
        autoStartOnIsland = ConfigJson.getBool(obj, "autoStartOnIsland", false);
        showTargetLabel = ConfigJson.getBool(obj, "showTargetLabel", true);
        targetColor = ConfigJson.getInt(obj, "targetColor", DEFAULT_TARGET_COLOR);
        chatFeedback = ConfigJson.getBool(obj, "chatFeedback", true);
        setArriveDistance(ConfigJson.getDouble(obj, "arriveDistance", 3.0));
        autoSouls = ConfigJson.getBool(obj, "autoSouls", false);
        autoWalk = ConfigJson.getBool(obj, "autoWalk", true);
        autoCollect = ConfigJson.getBool(obj, "autoCollect", true);
        allowPearls = ConfigJson.getBool(obj, "allowPearls", false);
        if ("PEARLS".equals(ConfigJson.getString(obj, "autoMode", "WALK"))) {
            autoMode = AutoMode.WALK;
            allowPearls = true;
        } else {
            autoMode = ConfigJson.getEnum(obj, "autoMode", AutoMode.class, AutoMode.WALK);
        }
        autoSprint = ConfigJson.getBool(obj, "autoSprint", true);
        resumeKeyCode = KeyUtil.sanitize(ConfigJson.getInt(obj, "resumeKeyCode", KeyUtil.NONE));
        if (version < 2) {
            // The "Fairy Souls" switch under the old "Pathfinding" one: OFF meant no soul waypoints were drawn.
            if (obj.has("fairySouls") && !ConfigJson.getBool(obj, "fairySouls", false)) {
                soulWaypoints = false;
            }
            // Auto Walk Path was its own switch that did nothing without Auto Fairy Souls; it is a part now, ON.
            autoWalk = true;
        }
    }

    /** Every key, as {@link #save} writes it. */
    JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("version", FILE_VERSION);
        obj.addProperty("enabled", enabled);
        obj.addProperty("soulWaypoints", soulWaypoints);
        obj.addProperty("showPath", showPath);
        obj.addProperty("soulMode", soulMode.name());
        obj.addProperty("dimPath", dimPath);
        obj.addProperty("pathColor", pathColor);
        obj.addProperty("visiblePathLength", visiblePathLength);
        obj.addProperty("lineThickness", lineThickness);
        obj.addProperty("recalcDistance", recalcDistance);
        obj.addProperty("textScale", textScale);
        obj.addProperty("soulHud", soulHud);
        obj.addProperty("autoStartOnIsland", autoStartOnIsland);
        obj.addProperty("showTargetLabel", showTargetLabel);
        obj.addProperty("targetColor", targetColor);
        obj.addProperty("chatFeedback", chatFeedback);
        obj.addProperty("arriveDistance", arriveDistance);
        obj.addProperty("autoSouls", autoSouls);
        obj.addProperty("autoWalk", autoWalk);
        obj.addProperty("autoCollect", autoCollect);
        obj.addProperty("autoMode", autoMode.name());
        obj.addProperty("allowPearls", allowPearls);
        obj.addProperty("autoSprint", autoSprint);
        obj.addProperty("resumeKeyCode", resumeKeyCode);
        return obj;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(toJson()), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** The settings file (the testkit's save-and-reload case reads it). */
    public static Path path() {
        return CONFIG_PATH;
    }

    private static boolean cheatGate() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && SkyblockGate.allows();
    }

    // ---- Fairy Souls ----

    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        this.enabled = v;
        com.killer560.hub.util.FeatureBits.changed();
    }

    /** The Fairy Souls feature (the tab's switch) is on and allowed here. */
    public boolean isFairySouls() {
        return isEnabled();
    }

    public boolean isSoulWaypoints() {
        return soulWaypoints;
    }

    public void setSoulWaypoints(boolean v) {
        this.soulWaypoints = v;
    }

    public boolean isShowPath() {
        return showPath;
    }

    public void setShowPath(boolean v) {
        this.showPath = v;
    }

    public SoulMode getSoulMode() {
        return soulMode;
    }

    public void setSoulMode(SoulMode v) {
        this.soulMode = v;
    }

    public boolean isDimPath() {
        return dimPath;
    }

    public void setDimPath(boolean v) {
        this.dimPath = v;
    }

    public int getPathColor() {
        return pathColor;
    }

    public void setPathColor(int v) {
        this.pathColor = v;
    }

    public int getVisiblePathLength() {
        return visiblePathLength;
    }

    public void setVisiblePathLength(int v) {
        this.visiblePathLength = Math.max(8, Math.min(96, v));
    }

    public float getLineThickness() {
        return lineThickness;
    }

    public void setLineThickness(float v) {
        this.lineThickness = Math.max(1.0f, Math.min(8.0f, v));
    }

    public double getRecalcDistance() {
        return recalcDistance;
    }

    public void setRecalcDistance(double v) {
        this.recalcDistance = Math.max(3.0, Math.min(25.0, v));
    }

    public float getTextScale() {
        return textScale;
    }

    public void setTextScale(float v) {
        this.textScale = Math.max(0.5f, Math.min(3.0f, v));
    }

    public boolean isSoulHud() {
        return soulHud;
    }

    public void setSoulHud(boolean v) {
        this.soulHud = v;
    }

    public boolean isAutoStartOnIsland() {
        return autoStartOnIsland;
    }

    public void setAutoStartOnIsland(boolean v) {
        this.autoStartOnIsland = v;
    }

    public boolean isShowTargetLabel() {
        return showTargetLabel;
    }

    public void setShowTargetLabel(boolean v) {
        this.showTargetLabel = v;
    }

    public int getTargetColor() {
        return targetColor;
    }

    public void setTargetColor(int v) {
        this.targetColor = v;
    }

    public boolean isChatFeedback() {
        return chatFeedback;
    }

    public void setChatFeedback(boolean v) {
        this.chatFeedback = v;
    }

    public double getArriveDistance() {
        return arriveDistance;
    }

    public void setArriveDistance(double v) {
        this.arriveDistance = Math.max(1.0, Math.min(8.0, v));
    }

    // ---- Auto Fairy Souls (cheat build) ----

    /** Auto Fairy Souls is on (the cheat tab's switch). Works with the Fairy Souls tab's own switch off. */
    public boolean isAutoSouls() {
        return autoSouls && cheatGate();
    }

    public boolean isAutoSoulsRaw() {
        return autoSouls;
    }

    public void setAutoSouls(boolean v) {
        this.autoSouls = v;
    }

    /** Auto Fairy Souls walks him (a part of it, ON by default). */
    public boolean isAutoWalk() {
        return autoWalk && cheatGate();
    }

    public boolean isAutoWalkRaw() {
        return autoWalk;
    }

    public void setAutoWalk(boolean v) {
        this.autoWalk = v;
    }

    public boolean isAutoCollect() {
        return autoCollect;
    }

    public void setAutoCollect(boolean v) {
        this.autoCollect = v;
    }

    public AutoMode getAutoMode() {
        return autoMode;
    }

    public void setAutoMode(AutoMode v) {
        this.autoMode = v;
    }

    /** Ender Pearls may be thrown to escape somewhere it cannot walk out of. */
    public boolean isAllowPearls() {
        return allowPearls;
    }

    public void setAllowPearls(boolean v) {
        this.allowPearls = v;
    }

    public boolean isAutoSprint() {
        return autoSprint;
    }

    public void setAutoSprint(boolean v) {
        this.autoSprint = v;
    }

    public int getResumeKeyCode() {
        return resumeKeyCode;
    }

    public void setResumeKeyCode(int v) {
        this.resumeKeyCode = KeyUtil.sanitize(v);
    }

    /** Orange-theme chat prefix used by every message this feature sends. */
    public static String chatName() {
        return "Pathfinding";
    }

    public static int accent() {
        return ModChat.ORANGE;
    }
}

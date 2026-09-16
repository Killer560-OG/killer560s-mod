package com.killer560.hub.pathfinding;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Pathfinding settings ({@code killer560smod-pathfinding.json}). Everything ships OFF. */
public final class PathfindingConfig {

    /** How auto fairy souls gets from soul to soul (cheat build only). */
    public enum AutoMode {
        WALK("Walk (minimal etherwarp)"),
        ETHERWARP("Etherwarp"),
        FAST_ETHERWARP("Fast Etherwarp");

        public final String label;

        AutoMode(String label) {
            this.label = label;
        }
    }

    /** What the fairy soul guide points at. */
    public enum SoulMode {
        NEAREST("Nearest Soul"),
        ROUTE("Route Whole Island");

        public final String label;

        SoulMode(String label) {
            this.label = label;
        }
    }

    public static final int DEFAULT_PATH_COLOR = 0xFFCC6600;
    public static final int DEFAULT_TARGET_COLOR = 0xFFFFA040;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-pathfinding.json");

    private static PathfindingConfig instance;

    // display
    private boolean enabled = false;
    private boolean showPath = true;
    private boolean showWholePath = false;
    private int visiblePathLength = 24;
    private float lineThickness = 3.0f;
    private int pathColor = DEFAULT_PATH_COLOR;
    private int targetColor = DEFAULT_TARGET_COLOR;
    private boolean showTargetLabel = true;
    private float textScale = 1.0f;
    private boolean chatFeedback = true;
    private double recalcDistance = 7.0;
    private double arriveDistance = 3.0;

    // fairy souls
    private boolean fairySouls = false;
    private SoulMode soulMode = SoulMode.ROUTE;
    private boolean soulWaypoints = true;
    private boolean soulHud = true;
    private boolean autoStartOnIsland = false;

    // cheat build only
    private boolean autoWalk = false;
    private boolean autoSouls = false;
    private AutoMode autoMode = AutoMode.WALK;
    private boolean autoCollect = true;
    private boolean autoSprint = true;
    private float rotationSpeed = 12.0f;
    private int resumeKeyCode = KeyUtil.NONE;

    private PathfindingConfig() {
    }

    public static PathfindingConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        PathfindingConfig cfg = new PathfindingConfig();
        instance = cfg;
        if (!Files.exists(CONFIG_PATH)) {
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showPath = ConfigJson.getBool(obj, "showPath", true);
            cfg.showWholePath = ConfigJson.getBool(obj, "showWholePath", false);
            cfg.setVisiblePathLength(ConfigJson.getInt(obj, "visiblePathLength", 24));
            cfg.setLineThickness(ConfigJson.getFloat(obj, "lineThickness", 3.0f));
            cfg.pathColor = ConfigJson.getInt(obj, "pathColor", DEFAULT_PATH_COLOR);
            cfg.targetColor = ConfigJson.getInt(obj, "targetColor", DEFAULT_TARGET_COLOR);
            cfg.showTargetLabel = ConfigJson.getBool(obj, "showTargetLabel", true);
            cfg.setTextScale(ConfigJson.getFloat(obj, "textScale", 1.0f));
            cfg.chatFeedback = ConfigJson.getBool(obj, "chatFeedback", true);
            cfg.setRecalcDistance(ConfigJson.getDouble(obj, "recalcDistance", 7.0));
            cfg.setArriveDistance(ConfigJson.getDouble(obj, "arriveDistance", 3.0));
            cfg.fairySouls = ConfigJson.getBool(obj, "fairySouls", false);
            cfg.soulMode = ConfigJson.getEnum(obj, "soulMode", SoulMode.class, SoulMode.ROUTE);
            cfg.soulWaypoints = ConfigJson.getBool(obj, "soulWaypoints", true);
            cfg.soulHud = ConfigJson.getBool(obj, "soulHud", true);
            cfg.autoStartOnIsland = ConfigJson.getBool(obj, "autoStartOnIsland", false);
            cfg.autoWalk = ConfigJson.getBool(obj, "autoWalk", false);
            cfg.autoSouls = ConfigJson.getBool(obj, "autoSouls", false);
            cfg.autoMode = ConfigJson.getEnum(obj, "autoMode", AutoMode.class, AutoMode.WALK);
            cfg.autoCollect = ConfigJson.getBool(obj, "autoCollect", true);
            cfg.autoSprint = ConfigJson.getBool(obj, "autoSprint", true);
            cfg.setRotationSpeed(ConfigJson.getFloat(obj, "rotationSpeed", 12.0f));
            cfg.resumeKeyCode = KeyUtil.sanitize(ConfigJson.getInt(obj, "resumeKeyCode", KeyUtil.NONE));
        } catch (Exception ignored) {
            // per-key readers above already fall back one key at a time; this only catches an unreadable file
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showPath", showPath);
            obj.addProperty("showWholePath", showWholePath);
            obj.addProperty("visiblePathLength", visiblePathLength);
            obj.addProperty("lineThickness", lineThickness);
            obj.addProperty("pathColor", pathColor);
            obj.addProperty("targetColor", targetColor);
            obj.addProperty("showTargetLabel", showTargetLabel);
            obj.addProperty("textScale", textScale);
            obj.addProperty("chatFeedback", chatFeedback);
            obj.addProperty("recalcDistance", recalcDistance);
            obj.addProperty("arriveDistance", arriveDistance);
            obj.addProperty("fairySouls", fairySouls);
            obj.addProperty("soulMode", soulMode.name());
            obj.addProperty("soulWaypoints", soulWaypoints);
            obj.addProperty("soulHud", soulHud);
            obj.addProperty("autoStartOnIsland", autoStartOnIsland);
            obj.addProperty("autoWalk", autoWalk);
            obj.addProperty("autoSouls", autoSouls);
            obj.addProperty("autoMode", autoMode.name());
            obj.addProperty("autoCollect", autoCollect);
            obj.addProperty("autoSprint", autoSprint);
            obj.addProperty("rotationSpeed", rotationSpeed);
            obj.addProperty("resumeKeyCode", resumeKeyCode);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static boolean cheatGate() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && SkyblockGate.allows();
    }

    // ---- display ----

    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        this.enabled = v;
    }

    public boolean isShowPath() {
        return showPath;
    }

    public void setShowPath(boolean v) {
        this.showPath = v;
    }

    public boolean isShowWholePath() {
        return showWholePath;
    }

    public void setShowWholePath(boolean v) {
        this.showWholePath = v;
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

    public int getPathColor() {
        return pathColor;
    }

    public void setPathColor(int v) {
        this.pathColor = v;
    }

    public int getTargetColor() {
        return targetColor;
    }

    public void setTargetColor(int v) {
        this.targetColor = v;
    }

    public boolean isShowTargetLabel() {
        return showTargetLabel;
    }

    public void setShowTargetLabel(boolean v) {
        this.showTargetLabel = v;
    }

    public float getTextScale() {
        return textScale;
    }

    public void setTextScale(float v) {
        this.textScale = Math.max(0.5f, Math.min(3.0f, v));
    }

    public boolean isChatFeedback() {
        return chatFeedback;
    }

    public void setChatFeedback(boolean v) {
        this.chatFeedback = v;
    }

    public double getRecalcDistance() {
        return recalcDistance;
    }

    public void setRecalcDistance(double v) {
        this.recalcDistance = Math.max(3.0, Math.min(25.0, v));
    }

    public double getArriveDistance() {
        return arriveDistance;
    }

    public void setArriveDistance(double v) {
        this.arriveDistance = Math.max(1.0, Math.min(8.0, v));
    }

    // ---- fairy souls ----

    public boolean isFairySouls() {
        return fairySouls && isEnabled();
    }

    public boolean isFairySoulsRaw() {
        return fairySouls;
    }

    public void setFairySouls(boolean v) {
        this.fairySouls = v;
    }

    public SoulMode getSoulMode() {
        return soulMode;
    }

    public void setSoulMode(SoulMode v) {
        this.soulMode = v;
    }

    public boolean isSoulWaypoints() {
        return soulWaypoints;
    }

    public void setSoulWaypoints(boolean v) {
        this.soulWaypoints = v;
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

    // ---- cheat build ----

    public boolean isAutoWalk() {
        return autoWalk && cheatGate() && isEnabled();
    }

    public boolean isAutoWalkRaw() {
        return autoWalk;
    }

    public void setAutoWalk(boolean v) {
        this.autoWalk = v;
    }

    public boolean isAutoSouls() {
        return autoSouls && cheatGate() && isFairySouls();
    }

    public boolean isAutoSoulsRaw() {
        return autoSouls;
    }

    public void setAutoSouls(boolean v) {
        this.autoSouls = v;
    }

    public AutoMode getAutoMode() {
        return autoMode;
    }

    public void setAutoMode(AutoMode v) {
        this.autoMode = v;
    }

    public boolean isAutoCollect() {
        return autoCollect;
    }

    public void setAutoCollect(boolean v) {
        this.autoCollect = v;
    }

    public boolean isAutoSprint() {
        return autoSprint;
    }

    public void setAutoSprint(boolean v) {
        this.autoSprint = v;
    }

    public float getRotationSpeed() {
        return rotationSpeed;
    }

    public void setRotationSpeed(float v) {
        this.rotationSpeed = Math.max(3.0f, Math.min(35.0f, v));
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

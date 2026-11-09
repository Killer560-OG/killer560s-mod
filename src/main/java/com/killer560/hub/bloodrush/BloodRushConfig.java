package com.killer560.hub.bloodrush;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Blood Rush Helper settings ({@link BloodRushHelperFeature}). Off by default. The two cheat-only settings (Through Walls,
 * Whole Rush Path) have gated getters, so a legit jar folds them to false whatever the file says.
 */
public final class BloodRushConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-bloodrush.json");

    public static final int MAX_KILLERS = 4;
    public static final int DEFAULT_MINE_COLOR = 0xFF55FF55;
    public static final int DEFAULT_OTHERS_COLOR = 0xFFAAAAAA;
    public static final int DEFAULT_LEARNED_COLOR = 0xFFAA00AA;
    public static final int MIN_FILL = 0;
    public static final int MAX_FILL = 60;
    /** The default box face opacity (12 until bloodrush2-1104: too faint to see in a lit room). */
    public static final int DEFAULT_FILL = 25;
    public static final int MIN_OUTLINE = 1;
    public static final int MAX_OUTLINE = 10;
    /** The default outline width (2 until bloodrush2-1104). */
    public static final int DEFAULT_OUTLINE = 4;

    private static BloodRushConfig instance;

    private boolean enabled = false;
    private int killers = 1;
    private int role = 1;
    private boolean showOthers = true;
    private boolean learnSpots = false;
    private boolean showLearned = true;
    private boolean allRooms = false;
    private int mineColor = DEFAULT_MINE_COLOR;
    private int othersColor = DEFAULT_OTHERS_COLOR;
    private int learnedColor = DEFAULT_LEARNED_COLOR;
    private int fillOpacity = DEFAULT_FILL;
    private boolean filled = true;
    private int outlineWidth = DEFAULT_OUTLINE;
    private boolean throughWalls = false;
    private boolean wholePath = false;
    private boolean hideSecrets = false;
    private DoorKills doorKills = DoorKills.NEVER;
    private boolean dropBottom = false;

    /** adm's Door Kills: when the door runner kills too (one more role, the last). */
    public enum DoorKills {
        NEVER("Never"), ALWAYS("Always"), AFTER_FAIRY("After Fairy");

        public final String label;

        DoorKills(String label) {
            this.label = label;
        }
    }

    private BloodRushConfig() {
    }

    public static BloodRushConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        BloodRushConfig c = new BloodRushConfig();
        JsonObject o = ConfigJson.read(CONFIG_PATH);
        if (o != null) {
            c.enabled = ConfigJson.getBool(o, "enabled", false);
            c.setKillers(ConfigJson.getInt(o, "killers", 1));
            c.setRole(ConfigJson.getInt(o, "role", 1));
            c.showOthers = ConfigJson.getBool(o, "showOthers", true);
            c.learnSpots = ConfigJson.getBool(o, "learnSpots", false);
            c.showLearned = ConfigJson.getBool(o, "showLearned", true);
            c.allRooms = ConfigJson.getBool(o, "allRooms", false);
            c.mineColor = ConfigJson.getInt(o, "mineColor", DEFAULT_MINE_COLOR);
            c.othersColor = ConfigJson.getInt(o, "othersColor", DEFAULT_OTHERS_COLOR);
            c.learnedColor = ConfigJson.getInt(o, "learnedColor", DEFAULT_LEARNED_COLOR);
            c.setFillOpacity(ConfigJson.getInt(o, "fillOpacity", DEFAULT_FILL));
            c.filled = ConfigJson.getBool(o, "filled", true);
            c.setOutlineWidth(ConfigJson.getInt(o, "outlineWidth", DEFAULT_OUTLINE));
            c.throughWalls = ConfigJson.getBool(o, "throughWalls", false);
            c.wholePath = ConfigJson.getBool(o, "wholePath", false);
            c.hideSecrets = ConfigJson.getBool(o, "hideSecrets", false);
            c.doorKills = ConfigJson.getEnum(o, "doorKills", DoorKills.class, DoorKills.NEVER);
            c.dropBottom = ConfigJson.getBool(o, "dropBottom", false);
        }
        instance = c;
        BloodRushHelperFeature.invalidate();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("killers", killers);
            o.addProperty("role", role);
            o.addProperty("showOthers", showOthers);
            o.addProperty("learnSpots", learnSpots);
            o.addProperty("showLearned", showLearned);
            o.addProperty("allRooms", allRooms);
            o.addProperty("mineColor", mineColor);
            o.addProperty("othersColor", othersColor);
            o.addProperty("learnedColor", learnedColor);
            o.addProperty("fillOpacity", fillOpacity);
            o.addProperty("filled", filled);
            o.addProperty("outlineWidth", outlineWidth);
            o.addProperty("throughWalls", throughWalls);
            o.addProperty("wholePath", wholePath);
            o.addProperty("hideSecrets", hideSecrets);
            o.addProperty("doorKills", doorKills.name());
            o.addProperty("dropBottom", dropBottom);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        BloodRushHelperFeature.invalidate();
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public int getKillers() {
        return killers;
    }

    public void setKillers(int v) {
        killers = Math.max(1, Math.min(MAX_KILLERS, v));
        role = Math.max(0, Math.min(killers, role));
    }

    public int getRole() {
        return role;
    }

    public void setRole(int v) {
        role = Math.max(0, Math.min(killers, v));
    }

    public boolean isShowOthers() {
        return showOthers;
    }

    public void setShowOthers(boolean v) {
        showOthers = v;
    }

    public boolean isLearnSpots() {
        return learnSpots;
    }

    public void setLearnSpots(boolean v) {
        learnSpots = v;
    }

    public boolean isShowLearned() {
        return showLearned;
    }

    public void setShowLearned(boolean v) {
        showLearned = v;
    }

    public boolean isAllRooms() {
        return allRooms;
    }

    public void setAllRooms(boolean v) {
        allRooms = v;
    }

    public int getMineColor() {
        return mineColor;
    }

    public void setMineColor(int v) {
        mineColor = v;
    }

    public int getOthersColor() {
        return othersColor;
    }

    public void setOthersColor(int v) {
        othersColor = v;
    }

    public int getLearnedColor() {
        return learnedColor;
    }

    public void setLearnedColor(int v) {
        learnedColor = v;
    }

    public int getFillOpacity() {
        return fillOpacity;
    }

    public void setFillOpacity(int v) {
        fillOpacity = Math.max(MIN_FILL, Math.min(MAX_FILL, v));
    }

    /** Whether the boxes' faces are drawn (at {@link #getFillOpacity}); off draws outlines only. */
    public boolean isFilled() {
        return filled;
    }

    public void setFilled(boolean v) {
        filled = v;
    }

    /** The box outline width, {@link #MIN_OUTLINE}-{@link #MAX_OUTLINE}. */
    public int getOutlineWidth() {
        return outlineWidth;
    }

    public void setOutlineWidth(int v) {
        outlineWidth = Math.max(MIN_OUTLINE, Math.min(MAX_OUTLINE, v));
    }

    public DoorKills getDoorKills() {
        return doorKills;
    }

    public void setDoorKills(DoorKills v) {
        doorKills = v == null ? DoorKills.NEVER : v;
    }

    /** With Door Kills After Fairy: the highest numbered killer leaves the rush at fairy to clear. */
    public boolean isDropBottom() {
        return dropBottom;
    }

    public void setDropBottom(boolean v) {
        dropBottom = v;
    }

    /** Cheat build only: boxes and numbers through walls. */
    public boolean isThroughWalls() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && throughWalls;
    }

    public boolean isThroughWallsRaw() {
        return throughWalls;
    }

    public void setThroughWalls(boolean v) {
        throughWalls = v;
    }

    /** Cheat build only: the rush path from the scanned dungeon, every room on it at once. */
    public boolean isWholePath() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && wholePath;
    }

    public boolean isHideSecrets() {
        return hideSecrets;
    }

    public void setHideSecrets(boolean v) {
        hideSecrets = v;
    }

    public boolean isWholePathRaw() {
        return wholePath;
    }

    public void setWholePath(boolean v) {
        wholePath = v;
    }
}

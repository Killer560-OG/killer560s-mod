package com.killer560.hub.bloodrush;

import com.google.gson.JsonObject;
import com.killer560.hub.util.BoolFields;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/**
 * Blood Rush Helper settings ({@link BloodRushHelperFeature}). Off by default. The two cheat-only settings (Through Walls,
 * Whole Rush Path) have gated getters, so a legit jar folds them to false whatever the file says.
 */
public final class BloodRushConfig {

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

    /** The plain on/off settings: key, default, and how to read and write the field. */
    private static final BoolFields<BloodRushConfig> BOOLS = new BoolFields<BloodRushConfig>()
            .add("enabled", false, c -> c.enabled, (c, v) -> c.enabled = v)
            .add("showOthers", true, c -> c.showOthers, (c, v) -> c.showOthers = v)
            .add("learnSpots", false, c -> c.learnSpots, (c, v) -> c.learnSpots = v)
            .add("showLearned", true, c -> c.showLearned, (c, v) -> c.showLearned = v)
            .add("allRooms", false, c -> c.allRooms, (c, v) -> c.allRooms = v)
            .add("filled", true, c -> c.filled, (c, v) -> c.filled = v)
            .add("throughWalls", false, c -> c.throughWalls, (c, v) -> c.throughWalls = v)
            .add("wholePath", false, c -> c.wholePath, (c, v) -> c.wholePath = v)
            .add("hideSecrets", false, c -> c.hideSecrets, (c, v) -> c.hideSecrets = v)
            .add("dropBottom", false, c -> c.dropBottom, (c, v) -> c.dropBottom = v);

    public static void load() {
        BloodRushConfig c = new BloodRushConfig();
        JsonObject o = ConfigJson.read(CONFIG_PATH);
        if (o != null) {
            BOOLS.load(c, o);
            c.setKillers(ConfigJson.getInt(o, "killers", 1));
            c.setRole(ConfigJson.getInt(o, "role", 1));
            c.mineColor = ConfigJson.getInt(o, "mineColor", DEFAULT_MINE_COLOR);
            c.othersColor = ConfigJson.getInt(o, "othersColor", DEFAULT_OTHERS_COLOR);
            c.learnedColor = ConfigJson.getInt(o, "learnedColor", DEFAULT_LEARNED_COLOR);
            c.setFillOpacity(ConfigJson.getInt(o, "fillOpacity", DEFAULT_FILL));
            c.setOutlineWidth(ConfigJson.getInt(o, "outlineWidth", DEFAULT_OUTLINE));
            c.doorKills = ConfigJson.getEnum(o, "doorKills", DoorKills.class, DoorKills.NEVER);
        }
        instance = c;
        BloodRushHelperFeature.invalidate();
    }

    public void save() {
        JsonObject o = new JsonObject();
        BOOLS.save(this, o);
        o.addProperty("killers", killers);
        o.addProperty("role", role);
        o.addProperty("mineColor", mineColor);
        o.addProperty("othersColor", othersColor);
        o.addProperty("learnedColor", learnedColor);
        o.addProperty("fillOpacity", fillOpacity);
        o.addProperty("outlineWidth", outlineWidth);
        o.addProperty("doorKills", doorKills.name());
        ConfigJson.write(CONFIG_PATH, o);
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

package com.killer560.hub.fastleap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.dungeonclass.DungeonClass;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted I4 Leap settings - see {@link I4LeapFeature}. Cheat-gated getters, everything defaults OFF. Saved to
 *  {@code config/killer560smod-i4leap.json}. */
public final class I4LeapConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-i4leap.json");

    public enum TargetType {
        CLASS("Class"), PLAYER("Player"), MELODY("Melody");

        public final String label;

        TargetType(String label) {
            this.label = label;
        }
    }

    /** The Melody backup: a class or a player. */
    public enum BackupType {
        CLASS("Class"), PLAYER("Player");

        public final String label;

        BackupType(String label) {
            this.label = label;
        }
    }

    private static I4LeapConfig instance;

    private boolean enabled = false;
    private boolean auto = false;
    private boolean fastLeap = false;
    private boolean preventInputs = false;
    private TargetType targetType = TargetType.CLASS;
    private DungeonClass targetClass = null;
    private String targetName = "";
    private BackupType backupType = BackupType.CLASS;
    private DungeonClass backupClass = null;
    private String backupName = "";

    private I4LeapConfig() {
    }

    public static I4LeapConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        I4LeapConfig cfg = new I4LeapConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = bool(o, "enabled");
                cfg.auto = bool(o, "auto");
                cfg.fastLeap = bool(o, "fastLeap");
                cfg.preventInputs = bool(o, "preventInputs");
                cfg.targetType = enumOr(TargetType.class, str(o, "targetType"), TargetType.CLASS);
                cfg.targetClass = DungeonClass.byName(str(o, "targetClass"));
                cfg.targetName = str(o, "targetName");
                cfg.backupType = enumOr(BackupType.class, str(o, "backupType"), BackupType.CLASS);
                cfg.backupClass = DungeonClass.byName(str(o, "backupClass"));
                cfg.backupName = str(o, "backupName");
            } catch (Exception e) {
                FastLeapFeature.LOGGER.warn("[I4Leap] Failed to read {} - using defaults", CONFIG_PATH, e);
                cfg = new I4LeapConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("auto", auto);
            o.addProperty("fastLeap", fastLeap);
            o.addProperty("preventInputs", preventInputs);
            o.addProperty("targetType", targetType.name());
            o.addProperty("targetClass", targetClass == null ? "" : targetClass.name());
            o.addProperty("targetName", targetName);
            o.addProperty("backupType", backupType.name());
            o.addProperty("backupClass", backupClass == null ? "" : backupClass.name());
            o.addProperty("backupName", backupName);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            FastLeapFeature.LOGGER.warn("[I4Leap] Failed to save {}", CONFIG_PATH, e);
        }
    }

    private static boolean bool(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private static String str(JsonObject o, String key) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback) {
        for (E e : type.getEnumConstants()) {
            if (e.name().equalsIgnoreCase(name)) {
                return e;
            }
        }
        return fallback;
    }

    public boolean isEnabled() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public boolean isAuto() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && auto;
    }

    public void setAuto(boolean v) {
        auto = v;
    }

    public boolean isFastLeap() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && fastLeap;
    }

    public void setFastLeap(boolean v) {
        fastLeap = v;
    }

    public boolean isPreventInputs() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && preventInputs;
    }

    public void setPreventInputs(boolean v) {
        preventInputs = v;
    }

    public TargetType getTargetType() {
        return targetType;
    }

    public void setTargetType(TargetType v) {
        targetType = v == null ? TargetType.CLASS : v;
    }

    public DungeonClass getTargetClass() {
        return targetClass;
    }

    public void setTargetClass(DungeonClass v) {
        targetClass = v;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String v) {
        targetName = v == null ? "" : v.trim();
    }

    public BackupType getBackupType() {
        return backupType;
    }

    public void setBackupType(BackupType v) {
        backupType = v == null ? BackupType.CLASS : v;
    }

    public DungeonClass getBackupClass() {
        return backupClass;
    }

    public void setBackupClass(DungeonClass v) {
        backupClass = v;
    }

    public String getBackupName() {
        return backupName;
    }

    public void setBackupName(String v) {
        backupName = v == null ? "" : v.trim();
    }
}

package com.killer560.hub.compactorpreview;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted "Compactor Preview" settings - see {@link CompactorPreviewFeature}. Ships disabled. Visual only. */
public final class CompactorPreviewConfig {

    /** When the contents grid is added to the item's tooltip. */
    public enum Mode {
        ON_HOVER("On Hover"),
        WHILE_HOLDING_KEY("While Holding Key");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    /** Left Alt. */
    public static final int DEFAULT_KEY = 342;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-compactorpreview.json");

    private static CompactorPreviewConfig instance;

    private boolean enabled = false;
    private Mode mode = Mode.ON_HOVER;
    private int keyCode = DEFAULT_KEY;
    private boolean skyblockOnly = true;

    private CompactorPreviewConfig() {
    }

    public static CompactorPreviewConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        CompactorPreviewConfig cfg = new CompactorPreviewConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
                cfg.mode = ConfigJson.getEnum(obj, "mode", Mode.class, Mode.ON_HOVER);
                cfg.keyCode = KeyUtil.sanitizeBind(ConfigJson.getInt(obj, "keyCode", DEFAULT_KEY));
                cfg.skyblockOnly = ConfigJson.getBool(obj, "skyblockOnly", true);
            } catch (Exception ignored) {
                cfg = new CompactorPreviewConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("mode", mode.name());
            obj.addProperty("keyCode", keyCode);
            obj.addProperty("skyblockOnly", skyblockOnly);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.ON_HOVER : mode;
    }

    public int getKeyCode() {
        return keyCode;
    }

    public void setKeyCode(int keyCode) {
        this.keyCode = KeyUtil.sanitizeBind(keyCode);
    }

    public boolean isSkyblockOnly() {
        return skyblockOnly;
    }

    public void setSkyblockOnly(boolean skyblockOnly) {
        this.skyblockOnly = skyblockOnly;
    }
}

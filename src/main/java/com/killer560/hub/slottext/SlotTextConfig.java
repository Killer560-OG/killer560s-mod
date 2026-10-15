package com.killer560.hub.slottext;

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
import java.util.EnumMap;
import java.util.Map;

/** Persisted "Slot Text" settings - see {@link SlotTextFeature}. Ships disabled. Visual only, so on both builds. */
public final class SlotTextConfig {

    /** When the text is drawn. The key is {@link #getKeyCode()}. */
    public enum Mode {
        ALWAYS("Always"),
        HOLD_TO_SHOW("Hold Key to Show"),
        HOLD_TO_HIDE("Hold Key to Hide");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    /** Left Alt, the same default Skyblocker's slot text key has. */
    public static final int DEFAULT_KEY = 342;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-slottext.json");

    private static SlotTextConfig instance;

    private boolean enabled = false;
    private Mode mode = Mode.ALWAYS;
    private int keyCode = DEFAULT_KEY;
    private boolean skyblockOnly = true;
    private final Map<SlotTextType, Boolean> types = new EnumMap<>(SlotTextType.class);

    private SlotTextConfig() {
        for (SlotTextType t : SlotTextType.values()) {
            types.put(t, t.defaultOn);
        }
    }

    public static SlotTextConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        SlotTextConfig cfg = new SlotTextConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
                cfg.mode = ConfigJson.getEnum(obj, "mode", Mode.class, Mode.ALWAYS);
                cfg.keyCode = KeyUtil.sanitizeBind(ConfigJson.getInt(obj, "keyCode", DEFAULT_KEY));
                cfg.skyblockOnly = ConfigJson.getBool(obj, "skyblockOnly", true);
                JsonObject t = ConfigJson.getObject(obj, "types");
                if (t != null) {
                    for (SlotTextType type : SlotTextType.values()) {
                        cfg.types.put(type, ConfigJson.getBool(t, type.name(), type.defaultOn));
                    }
                }
            } catch (Exception ignored) {
                cfg = new SlotTextConfig();
            }
        }
        instance = cfg;
        SlotTextFeature.settingsChanged();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("mode", mode.name());
            obj.addProperty("keyCode", keyCode);
            obj.addProperty("skyblockOnly", skyblockOnly);
            JsonObject t = new JsonObject();
            for (SlotTextType type : SlotTextType.values()) {
                t.addProperty(type.name(), isTypeEnabled(type));
            }
            obj.add("types", t);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        SlotTextFeature.settingsChanged();
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.ALWAYS : mode;
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

    public boolean isTypeEnabled(SlotTextType type) {
        return types.getOrDefault(type, type.defaultOn);
    }

    public void setTypeEnabled(SlotTextType type, boolean on) {
        types.put(type, on);
        SlotTextFeature.settingsChanged();
    }
}

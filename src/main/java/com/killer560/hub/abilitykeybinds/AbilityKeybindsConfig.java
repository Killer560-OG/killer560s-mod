package com.killer560.hub.abilitykeybinds;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Ability Keybinds settings - see {@link AbilityKeybindsFeature}'s class doc for the real
 *  Noamm-ported ability-trigger this is built on. Ships disabled by default, same as every other new
 *  feature in this mod. */
public final class AbilityKeybindsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-abilitykeybinds.json");

    /** Bumped to 2 when the Ability/Ultimate packets were un-swapped (2026-09-20) - a version-1 file has its
     *  two saved codes swapped on load so each physical key keeps doing exactly what it did before the fix. */
    private static final int CONFIG_VERSION = 2;

    /** Mouse buttons are stored as {@code MOUSE_CODE_BASE - button} (left -100, right -101, middle -102, ...).
     *  Same encoding as {@code CommandKeybindsConfig}; both are local copies until {@code KeyUtil} itself
     *  learns about mouse binds (patch in this wave's staging notes). */
    public static final int MOUSE_CODE_BASE = -100;
    public static final int MAX_MOUSE_BUTTON = 7;

    private static AbilityKeybindsConfig instance;

    private boolean enabled = false;
    private int abilityKeyCode = -1;
    private int ultimateKeyCode = -1;

    private AbilityKeybindsConfig() {
    }

    public static AbilityKeybindsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static boolean isMouseCode(int code) {
        return code <= MOUSE_CODE_BASE && code >= MOUSE_CODE_BASE - MAX_MOUSE_BUTTON;
    }

    public static int mouseButton(int code) {
        return MOUSE_CODE_BASE - code;
    }

    public static int codeForMouseButton(int button) {
        return MOUSE_CODE_BASE - button;
    }

    /** Like {@code KeyUtil.sanitize}, but keeps mouse-button codes (KeyUtil only accepts keyboard codes). */
    public static int sanitizeBind(int code) {
        return isMouseCode(code) ? code : com.killer560.hub.util.KeyUtil.sanitize(code);
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new AbilityKeybindsConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            AbilityKeybindsConfig cfg = new AbilityKeybindsConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.abilityKeyCode = sanitizeBind(ConfigJson.getInt(obj, "abilityKeyCode", -1));
            cfg.ultimateKeyCode = sanitizeBind(ConfigJson.getInt(obj, "ultimateKeyCode", -1));
            if (ConfigJson.getInt(obj, "version", 1) < CONFIG_VERSION) {
                // killer560 (2026-09-20): "the ultimate keybind does the ability and the ability does the
                // ultimate". The packets are fixed in AbilityKeybindsFeature; swapping the two saved codes
                // here keeps each physical key doing the same in-game thing it did before the fix, with the
                // labels finally telling the truth. Idempotent: re-running it on an unsaved v1 file gives the
                // same result every launch, and the first save writes version 2.
                int previousAbility = cfg.abilityKeyCode;
                cfg.abilityKeyCode = cfg.ultimateKeyCode;
                cfg.ultimateKeyCode = previousAbility;
            }
            instance = cfg;
        } catch (Exception e) {
            instance = new AbilityKeybindsConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("version", CONFIG_VERSION);
            obj.addProperty("enabled", enabled);
            obj.addProperty("abilityKeyCode", abilityKeyCode);
            obj.addProperty("ultimateKeyCode", ultimateKeyCode);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getAbilityKeyCode() {
        return abilityKeyCode;
    }

    public void setAbilityKeyCode(int abilityKeyCode) {
        this.abilityKeyCode = abilityKeyCode;
    }

    public int getUltimateKeyCode() {
        return ultimateKeyCode;
    }

    public void setUltimateKeyCode(int ultimateKeyCode) {
        this.ultimateKeyCode = ultimateKeyCode;
    }
}

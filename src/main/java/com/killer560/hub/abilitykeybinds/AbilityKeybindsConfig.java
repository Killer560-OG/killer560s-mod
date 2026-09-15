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
            cfg.abilityKeyCode = ConfigJson.getInt(obj, "abilityKeyCode", -1);
            cfg.ultimateKeyCode = ConfigJson.getInt(obj, "ultimateKeyCode", -1);
            instance = cfg;
        } catch (Exception e) {
            instance = new AbilityKeybindsConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
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

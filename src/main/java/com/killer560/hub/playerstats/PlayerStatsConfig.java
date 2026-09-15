package com.killer560.hub.playerstats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Player Stats HUD settings - see {@link PlayerStatsFeature}'s class doc for the real
 *  Odin-ported action-bar parsing this is built on. Ships disabled by default, same as every other new
 *  feature in this mod. */
public final class PlayerStatsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-playerstats.json");

    private static PlayerStatsConfig instance;

    private boolean enabled = false;
    private boolean showHealth = true;
    private boolean showMana = true;
    private boolean showDefense = true;

    private PlayerStatsConfig() {
    }

    public static PlayerStatsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new PlayerStatsConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            PlayerStatsConfig cfg = new PlayerStatsConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showHealth = ConfigJson.getBool(obj, "showHealth", true);
            cfg.showMana = ConfigJson.getBool(obj, "showMana", true);
            cfg.showDefense = ConfigJson.getBool(obj, "showDefense", true);
            instance = cfg;
        } catch (Exception e) {
            instance = new PlayerStatsConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showHealth", showHealth);
            obj.addProperty("showMana", showMana);
            obj.addProperty("showDefense", showDefense);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isShowHealth() {
        return showHealth;
    }

    public void setShowHealth(boolean showHealth) {
        this.showHealth = showHealth;
    }

    public boolean isShowMana() {
        return showMana;
    }

    public void setShowMana(boolean showMana) {
        this.showMana = showMana;
    }

    public boolean isShowDefense() {
        return showDefense;
    }

    public void setShowDefense(boolean showDefense) {
        this.showDefense = showDefense;
    }
}

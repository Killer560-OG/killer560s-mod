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

/** Persisted Stat Bars settings - see {@link PlayerStatsFeature}'s class doc for the real Odin-ported
 *  action-bar parsing this is built on. Ships disabled by default, same as every other new feature in
 *  this mod.
 *  <p>
 *  killer560 (2026-09-21): "For the player stats hud rename it to stat bars... it should hide the other
 *  bars. Allow for me to hide hunger, the armor bar, and the hearts. Add a toggle to unhide hearts in the
 *  rift." The five {@code hideVanilla*}/{@code showHeartsInRift} fields below back that - see
 *  {@link PlayerStatsFeature#registerVanillaSuppression()} for the actual hide mechanism.
 *  <p>
 *  <b>Persistence keys that must never change</b> (renaming "Player Stats" to "Stat Bars" only touches
 *  display strings): the JSON file name {@code killer560smod-playerstats.json}, and every key below. */
public final class PlayerStatsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-playerstats.json");

    private static PlayerStatsConfig instance;

    private boolean enabled = false;
    private boolean showHealth = true;
    private boolean showMana = true;
    private boolean showDefense = true;
    // --- Stat Bars: hide the vanilla HUD bars underneath ours (killer560, 2026-09-21) ---
    private boolean hideVanillaHearts = true;
    private boolean hideVanillaHunger = true;
    private boolean hideVanillaArmour = true;
    private boolean hideVanillaAir = true;
    /** Un-hides the vanilla heart bar while in The Rift, where hearts mean something different. */
    private boolean showHeartsInRift = true;

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
            cfg.hideVanillaHearts = ConfigJson.getBool(obj, "hideVanillaHearts", true);
            cfg.hideVanillaHunger = ConfigJson.getBool(obj, "hideVanillaHunger", true);
            cfg.hideVanillaArmour = ConfigJson.getBool(obj, "hideVanillaArmour", true);
            cfg.hideVanillaAir = ConfigJson.getBool(obj, "hideVanillaAir", true);
            cfg.showHeartsInRift = ConfigJson.getBool(obj, "showHeartsInRift", true);
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
            obj.addProperty("hideVanillaHearts", hideVanillaHearts);
            obj.addProperty("hideVanillaHunger", hideVanillaHunger);
            obj.addProperty("hideVanillaArmour", hideVanillaArmour);
            obj.addProperty("hideVanillaAir", hideVanillaAir);
            obj.addProperty("showHeartsInRift", showHeartsInRift);
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

    public boolean isHideVanillaHearts() {
        return hideVanillaHearts;
    }

    public void setHideVanillaHearts(boolean value) {
        this.hideVanillaHearts = value;
    }

    public boolean isHideVanillaHunger() {
        return hideVanillaHunger;
    }

    public void setHideVanillaHunger(boolean value) {
        this.hideVanillaHunger = value;
    }

    public boolean isHideVanillaArmour() {
        return hideVanillaArmour;
    }

    public void setHideVanillaArmour(boolean value) {
        this.hideVanillaArmour = value;
    }

    public boolean isHideVanillaAir() {
        return hideVanillaAir;
    }

    public void setHideVanillaAir(boolean value) {
        this.hideVanillaAir = value;
    }

    /** Whether the vanilla heart bar stays visible in The Rift even while {@link #isHideVanillaHearts()}. */
    public boolean isShowHeartsInRift() {
        return showHeartsInRift;
    }

    public void setShowHeartsInRift(boolean value) {
        this.showHeartsInRift = value;
    }
}

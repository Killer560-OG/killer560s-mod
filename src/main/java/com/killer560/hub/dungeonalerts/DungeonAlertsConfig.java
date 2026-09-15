package com.killer560.hub.dungeonalerts;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** One persisted settings file for the whole Dungeon Alerts pack (Shadow Assassin Alert, Secret Sound,
 *  Terracotta Timer, Spring Boots Overlay, Ragnarock, Class Colors, Room Alerts). Every feature ships OFF.
 *  Every setter is followed by {@link #save()} at the GUI call site (killer560's settings-persistence rule). */
public final class DungeonAlertsConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-dungeonalerts");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-dungeonalerts.json");

    private static DungeonAlertsConfig instance;

    // Shadow Assassin Alert
    public boolean shadowAssassinEnabled = false;
    public boolean shadowAssassinPartyChat = false;

    // Secret Sound
    public boolean secretSoundEnabled = false;
    public String secretSoundId = SecretSound.SoundChoice.EXPERIENCE_ORB.name();
    public float secretSoundVolume = 0.5f;
    public float secretSoundPitch = 1.0f;

    // Terracotta Timer
    public boolean terracottaEnabled = false;

    // Spring Boots Overlay
    public boolean springBootsEnabled = false;
    public boolean springBootsBox = true;

    // Ragnarock
    public boolean ragEnabled = false;
    public boolean ragCastAlert = true;
    public boolean ragCancelAlert = true;
    public boolean ragStrengthMessage = true;
    public boolean ragAnnounceStrength = false;
    public boolean ragBuffTimer = true;
    public boolean ragEndAlert = true;
    public boolean ragM7Alert = false;

    // Class Colors
    public boolean classColorsEnabled = false;
    public boolean classColorsTab = true;
    public boolean classColorsNametags = true;

    // Room Alerts
    public boolean roomAlertsEnabled = false;
    public boolean roomAlertsPuzzles = true;
    public String roomAlertsNames = "";
    public boolean roomAlertsTitle = true;
    public boolean roomAlertsChat = false;
    public float roomAlertsDisplaySeconds = 2.0f;

    private DungeonAlertsConfig() {
    }

    public static DungeonAlertsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        DungeonAlertsConfig cfg = new DungeonAlertsConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.shadowAssassinEnabled = bool(o, "shadowAssassinEnabled", cfg.shadowAssassinEnabled);
                cfg.shadowAssassinPartyChat = bool(o, "shadowAssassinPartyChat", cfg.shadowAssassinPartyChat);
                cfg.secretSoundEnabled = bool(o, "secretSoundEnabled", cfg.secretSoundEnabled);
                cfg.secretSoundId = ConfigJson.getString(o, "secretSoundId", cfg.secretSoundId);
                cfg.secretSoundVolume = clamp(flt(o, "secretSoundVolume", cfg.secretSoundVolume), 0f, 1f);
                cfg.secretSoundPitch = clamp(flt(o, "secretSoundPitch", cfg.secretSoundPitch), 0f, 2f);
                cfg.terracottaEnabled = bool(o, "terracottaEnabled", cfg.terracottaEnabled);
                cfg.springBootsEnabled = bool(o, "springBootsEnabled", cfg.springBootsEnabled);
                cfg.springBootsBox = bool(o, "springBootsBox", cfg.springBootsBox);
                cfg.ragEnabled = bool(o, "ragEnabled", cfg.ragEnabled);
                cfg.ragCastAlert = bool(o, "ragCastAlert", cfg.ragCastAlert);
                cfg.ragCancelAlert = bool(o, "ragCancelAlert", cfg.ragCancelAlert);
                cfg.ragStrengthMessage = bool(o, "ragStrengthMessage", cfg.ragStrengthMessage);
                cfg.ragAnnounceStrength = bool(o, "ragAnnounceStrength", cfg.ragAnnounceStrength);
                cfg.ragBuffTimer = bool(o, "ragBuffTimer", cfg.ragBuffTimer);
                cfg.ragEndAlert = bool(o, "ragEndAlert", cfg.ragEndAlert);
                cfg.ragM7Alert = bool(o, "ragM7Alert", cfg.ragM7Alert);
                cfg.classColorsEnabled = bool(o, "classColorsEnabled", cfg.classColorsEnabled);
                cfg.classColorsTab = bool(o, "classColorsTab", cfg.classColorsTab);
                cfg.classColorsNametags = bool(o, "classColorsNametags", cfg.classColorsNametags);
                cfg.roomAlertsEnabled = bool(o, "roomAlertsEnabled", cfg.roomAlertsEnabled);
                cfg.roomAlertsPuzzles = bool(o, "roomAlertsPuzzles", cfg.roomAlertsPuzzles);
                cfg.roomAlertsNames = ConfigJson.getString(o, "roomAlertsNames", cfg.roomAlertsNames);
                cfg.roomAlertsTitle = bool(o, "roomAlertsTitle", cfg.roomAlertsTitle);
                cfg.roomAlertsChat = bool(o, "roomAlertsChat", cfg.roomAlertsChat);
                cfg.roomAlertsDisplaySeconds = clamp(flt(o, "roomAlertsDisplaySeconds", cfg.roomAlertsDisplaySeconds), 0.5f, 3.0f);
            } catch (Exception e) {
                LOGGER.warn("[DungeonAlerts] Failed to read config, using defaults", e);
                cfg = new DungeonAlertsConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("shadowAssassinEnabled", shadowAssassinEnabled);
            o.addProperty("shadowAssassinPartyChat", shadowAssassinPartyChat);
            o.addProperty("secretSoundEnabled", secretSoundEnabled);
            o.addProperty("secretSoundId", secretSoundId);
            o.addProperty("secretSoundVolume", secretSoundVolume);
            o.addProperty("secretSoundPitch", secretSoundPitch);
            o.addProperty("terracottaEnabled", terracottaEnabled);
            o.addProperty("springBootsEnabled", springBootsEnabled);
            o.addProperty("springBootsBox", springBootsBox);
            o.addProperty("ragEnabled", ragEnabled);
            o.addProperty("ragCastAlert", ragCastAlert);
            o.addProperty("ragCancelAlert", ragCancelAlert);
            o.addProperty("ragStrengthMessage", ragStrengthMessage);
            o.addProperty("ragAnnounceStrength", ragAnnounceStrength);
            o.addProperty("ragBuffTimer", ragBuffTimer);
            o.addProperty("ragEndAlert", ragEndAlert);
            o.addProperty("ragM7Alert", ragM7Alert);
            o.addProperty("classColorsEnabled", classColorsEnabled);
            o.addProperty("classColorsTab", classColorsTab);
            o.addProperty("classColorsNametags", classColorsNametags);
            o.addProperty("roomAlertsEnabled", roomAlertsEnabled);
            o.addProperty("roomAlertsPuzzles", roomAlertsPuzzles);
            o.addProperty("roomAlertsNames", roomAlertsNames);
            o.addProperty("roomAlertsTitle", roomAlertsTitle);
            o.addProperty("roomAlertsChat", roomAlertsChat);
            o.addProperty("roomAlertsDisplaySeconds", roomAlertsDisplaySeconds);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[DungeonAlerts] Failed to save config", e);
        }
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        return ConfigJson.getBool(o, key, def);
    }

    private static float flt(JsonObject o, String key, float def) {
        return ConfigJson.getFloat(o, key, def);
    }

    static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}

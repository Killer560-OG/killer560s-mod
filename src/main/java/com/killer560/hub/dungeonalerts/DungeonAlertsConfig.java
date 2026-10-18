package com.killer560.hub.dungeonalerts;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** One persisted settings file for the whole Dungeon Alerts pack (Shadow Assassin Alert, Secret Sound,
 *  Terracotta Timer, Spring Boots Overlay, Class Colors, Room Alerts). Every feature ships OFF.
 *  <p>Ragnarock moved out of this file in the Rag Axe batch - it now lives in
 *  {@code com.killer560.hub.ragaxe.RagAxeConfig} ({@code killer560smod-ragaxe.json}), which migrates the old
 *  {@code rag*} keys out of this file once. They are deliberately left in place here (unread) so the migration
 *  still works if the new config is ever deleted.
 *  Every setter is followed by {@link #save()} at the GUI call site (killer560's settings-persistence rule). */
public final class DungeonAlertsConfig {

    private static final Logger LOGGER = ModLog.get("killer560smod-dungeonalerts");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-dungeonalerts.json");

    private static DungeonAlertsConfig instance;

    // Shadow Assassin Alert
    public boolean shadowAssassinEnabled = false;

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
    /** Height box colour (ARGB) and style (killer560, 2026-10-11). The default is Odin's red, outline only. */
    public int springBootsColor = DEFAULT_SPRING_BOOTS_COLOR;
    public BoxStyle springBootsBoxStyle = BoxStyle.OUTLINE;

    public static final int DEFAULT_SPRING_BOOTS_COLOR = 0xFFFF5454;

    /** How the Spring Boots height box is drawn. */
    public enum BoxStyle {
        OUTLINE("Outline"),
        FILLED("Filled"),
        FILLED_OUTLINE("Filled Outline");

        public final String label;

        BoxStyle(String label) {
            this.label = label;
        }

        public BoxStyle next() {
            BoxStyle[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    // Class Colors
    public boolean classColorsEnabled = false;
    public boolean classColorsTab = true;
    public boolean classColorsNametags = true;

    // Room Alerts - killer560 (2026-09-27): "Make room alerts only have cleared room alert and secrets in
    // a room done alert" - replaces the old "enter a named/puzzle room" trigger entirely. See RoomAlerts.
    public boolean roomAlertsEnabled = false;
    public boolean roomAlertsRoomCleared = true;
    public boolean roomAlertsSecretsDone = true;
    public float roomAlertsDisplaySeconds = 2.0f;
    /** The alerts' on-screen colours (killer560, 2026-10-18: "an option for room alerts colors ... let me change the color
     *  of the shadow assassin one and by default make it more of a purple"), RGB. */
    public int roomAlertsClearedColor = DEFAULT_ROOM_ALERT_COLOR;
    public int roomAlertsSecretsColor = DEFAULT_ROOM_ALERT_COLOR;
    public int shadowAssassinColor = DEFAULT_SHADOW_ASSASSIN_COLOR;
    public static final int DEFAULT_ROOM_ALERT_COLOR = com.killer560.hub.util.ModChat.LIGHT_ORANGE;
    public static final int DEFAULT_SHADOW_ASSASSIN_COLOR = 0xAA44FF;

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
                cfg.secretSoundEnabled = bool(o, "secretSoundEnabled", cfg.secretSoundEnabled);
                cfg.secretSoundId = ConfigJson.getString(o, "secretSoundId", cfg.secretSoundId);
                cfg.secretSoundVolume = clamp(flt(o, "secretSoundVolume", cfg.secretSoundVolume), 0f, 1f);
                cfg.secretSoundPitch = clamp(flt(o, "secretSoundPitch", cfg.secretSoundPitch), 0f, 2f);
                cfg.terracottaEnabled = bool(o, "terracottaEnabled", cfg.terracottaEnabled);
                cfg.springBootsEnabled = bool(o, "springBootsEnabled", cfg.springBootsEnabled);
                cfg.springBootsBox = bool(o, "springBootsBox", cfg.springBootsBox);
                cfg.springBootsColor = ConfigJson.getInt(o, "springBootsColor", cfg.springBootsColor);
                cfg.springBootsBoxStyle = ConfigJson.getEnum(o, "springBootsBoxStyle", BoxStyle.class, cfg.springBootsBoxStyle);
                cfg.classColorsEnabled = bool(o, "classColorsEnabled", cfg.classColorsEnabled);
                cfg.classColorsTab = bool(o, "classColorsTab", cfg.classColorsTab);
                cfg.classColorsNametags = bool(o, "classColorsNametags", cfg.classColorsNametags);
                cfg.roomAlertsEnabled = bool(o, "roomAlertsEnabled", cfg.roomAlertsEnabled);
                cfg.roomAlertsRoomCleared = bool(o, "roomAlertsRoomCleared", cfg.roomAlertsRoomCleared);
                cfg.roomAlertsSecretsDone = bool(o, "roomAlertsSecretsDone", cfg.roomAlertsSecretsDone);
                // "roomAlertsTitle" (On-screen Text) is gone (2026-10-18): the on-screen text is the alert. The key is ignored.
                cfg.roomAlertsClearedColor = ConfigJson.getInt(o, "roomAlertsClearedColor", cfg.roomAlertsClearedColor) & 0xFFFFFF;
                cfg.roomAlertsSecretsColor = ConfigJson.getInt(o, "roomAlertsSecretsColor", cfg.roomAlertsSecretsColor) & 0xFFFFFF;
                cfg.shadowAssassinColor = ConfigJson.getInt(o, "shadowAssassinColor", cfg.shadowAssassinColor) & 0xFFFFFF;
                cfg.roomAlertsDisplaySeconds = clamp(flt(o, "roomAlertsDisplaySeconds", cfg.roomAlertsDisplaySeconds), 0.5f, 3.0f);
                // A "Dungeon Alerts as a whole" switch (2026-10-13) sat over both alerts and is gone (2026-10-15): they
                // are independent. A file saved with it OFF had both standing down, so they load OFF; saved ON (or
                // without it) they keep their own switches. The key is not written back.
                if (o.has("dungeonAlertsEnabled") && !bool(o, "dungeonAlertsEnabled", true)) {
                    cfg.shadowAssassinEnabled = false;
                    cfg.roomAlertsEnabled = false;
                }
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
            o.addProperty("secretSoundEnabled", secretSoundEnabled);
            o.addProperty("secretSoundId", secretSoundId);
            o.addProperty("secretSoundVolume", secretSoundVolume);
            o.addProperty("secretSoundPitch", secretSoundPitch);
            o.addProperty("terracottaEnabled", terracottaEnabled);
            o.addProperty("springBootsEnabled", springBootsEnabled);
            o.addProperty("springBootsBox", springBootsBox);
            o.addProperty("springBootsColor", springBootsColor);
            o.addProperty("springBootsBoxStyle", springBootsBoxStyle.name());
            o.addProperty("classColorsEnabled", classColorsEnabled);
            o.addProperty("classColorsTab", classColorsTab);
            o.addProperty("classColorsNametags", classColorsNametags);
            o.addProperty("roomAlertsEnabled", roomAlertsEnabled);
            o.addProperty("roomAlertsRoomCleared", roomAlertsRoomCleared);
            o.addProperty("roomAlertsSecretsDone", roomAlertsSecretsDone);
            o.addProperty("roomAlertsClearedColor", roomAlertsClearedColor);
            o.addProperty("roomAlertsSecretsColor", roomAlertsSecretsColor);
            o.addProperty("shadowAssassinColor", shadowAssassinColor);
            o.addProperty("roomAlertsDisplaySeconds", roomAlertsDisplaySeconds);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[DungeonAlerts] Failed to save config", e);
        }
    }

    /** Shadow Assassin Alert's switch. */
    public boolean shadowAssassinOn() {
        return shadowAssassinEnabled;
    }

    /** Room Alerts' switch. */
    public boolean roomAlertsOn() {
        return roomAlertsEnabled;
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

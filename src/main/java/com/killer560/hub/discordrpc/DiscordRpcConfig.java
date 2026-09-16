package com.killer560.hub.discordrpc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Discord Rich Presence settings (2026-09-15). Default OFF and with an EMPTY application id:
 * Rich Presence shows the NAME OF A DISCORD APPLICATION after "Playing", so it only works with an app the
 * user created themselves at discord.com/developers - there is deliberately no id baked into the mod.
 * <p>
 * Everything the presence can reveal (island/area, dungeon floor/phase, elapsed time) is individually
 * toggleable, plus a single {@link #isHideDetails() Hide Details} switch that collapses the presence down
 * to just the application name - this broadcasts to the user's Discord friends list, so it stays opt-in.
 */
public final class DiscordRpcConfig {

    public static final String DEFAULT_LARGE_IMAGE_KEY = "killer560s-mod-discord-512";
    public static final String DEFAULT_LARGE_IMAGE_TEXT = "Killer560's Mod";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-discordrpc.json");

    private static DiscordRpcConfig instance;

    private boolean enabled = false;
    /** killer560's own Discord application ("Killer560's Mod", created 2026-09-15) so the feature works out of
     *  the box. Application IDs are public - they are visible in the presence itself - not a secret. Anyone else
     *  can replace it with their own app's ID to have their own name shown after "Playing". */
    private String applicationId = "1549644031917559850";
    private boolean showArea = true;
    private boolean showDungeonInfo = true;
    private boolean showElapsed = true;
    private boolean hideDetails = false;
    private String largeImageKey = DEFAULT_LARGE_IMAGE_KEY;
    private String largeImageText = DEFAULT_LARGE_IMAGE_TEXT;
    private String smallImageKey = "";
    private String smallImageText = "";

    private DiscordRpcConfig() {
    }

    public static DiscordRpcConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    /** Re-reads the file into a fresh instance. Registered in {@code ProfileManager.reloadAllConfigs()}. */
    public static void load() {
        DiscordRpcConfig cfg = new DiscordRpcConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                // Per-key readers (ConfigJson): one bad value must not reset every other setting.
                cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
                cfg.applicationId = ConfigJson.getString(obj, "applicationId", cfg.applicationId);
                cfg.showArea = ConfigJson.getBool(obj, "showArea", cfg.showArea);
                cfg.showDungeonInfo = ConfigJson.getBool(obj, "showDungeonInfo", cfg.showDungeonInfo);
                cfg.showElapsed = ConfigJson.getBool(obj, "showElapsed", cfg.showElapsed);
                cfg.hideDetails = ConfigJson.getBool(obj, "hideDetails", cfg.hideDetails);
                cfg.largeImageKey = ConfigJson.getString(obj, "largeImageKey", cfg.largeImageKey);
                cfg.largeImageText = ConfigJson.getString(obj, "largeImageText", cfg.largeImageText);
                cfg.smallImageKey = ConfigJson.getString(obj, "smallImageKey", cfg.smallImageKey);
                cfg.smallImageText = ConfigJson.getString(obj, "smallImageText", cfg.smallImageText);
            } catch (Exception ignored) {
                // unreadable file: keep defaults for this session
            }
        }
        cfg.normalize();
        instance = cfg;
    }

    private void normalize() {
        applicationId = applicationId == null ? "" : applicationId.trim();
        largeImageKey = largeImageKey == null ? "" : largeImageKey.trim();
        largeImageText = largeImageText == null ? "" : largeImageText.trim();
        smallImageKey = smallImageKey == null ? "" : smallImageKey.trim();
        smallImageText = smallImageText == null ? "" : smallImageText.trim();
    }

    public void save() {
        try {
            normalize();
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("applicationId", applicationId);
            obj.addProperty("showArea", showArea);
            obj.addProperty("showDungeonInfo", showDungeonInfo);
            obj.addProperty("showElapsed", showElapsed);
            obj.addProperty("hideDetails", hideDetails);
            obj.addProperty("largeImageKey", largeImageKey);
            obj.addProperty("largeImageText", largeImageText);
            obj.addProperty("smallImageKey", smallImageKey);
            obj.addProperty("smallImageText", smallImageText);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** No {@code SkyblockGate} here on purpose: this is infrastructure (a Discord connection), not a
     *  Skyblock feature. The presence TEXT is what limits Skyblock details to actual Hypixel sessions. */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Discord application id (snowflake) the user created. Empty = feature stays off. */
    public String getApplicationId() {
        return applicationId == null ? "" : applicationId;
    }

    public void setApplicationId(String applicationId) {
        this.applicationId = applicationId == null ? "" : applicationId.trim();
    }

    /** @return whether the id looks like a Discord snowflake (17-20 digits). Only used for a GUI hint. */
    public boolean hasPlausibleApplicationId() {
        String id = getApplicationId();
        return id.length() >= 17 && id.length() <= 20 && id.chars().allMatch(Character::isDigit);
    }

    public boolean isShowArea() {
        return showArea;
    }

    public void setShowArea(boolean showArea) {
        this.showArea = showArea;
    }

    public boolean isShowDungeonInfo() {
        return showDungeonInfo;
    }

    public void setShowDungeonInfo(boolean showDungeonInfo) {
        this.showDungeonInfo = showDungeonInfo;
    }

    public boolean isShowElapsed() {
        return showElapsed;
    }

    public void setShowElapsed(boolean showElapsed) {
        this.showElapsed = showElapsed;
    }

    /** Privacy switch: presence shows only the app name, never where the player is. */
    public boolean isHideDetails() {
        return hideDetails;
    }

    public void setHideDetails(boolean hideDetails) {
        this.hideDetails = hideDetails;
    }

    public String getLargeImageKey() {
        return largeImageKey == null ? "" : largeImageKey;
    }

    public void setLargeImageKey(String largeImageKey) {
        this.largeImageKey = largeImageKey == null ? "" : largeImageKey.trim();
    }

    public String getLargeImageText() {
        return largeImageText == null ? "" : largeImageText;
    }

    public void setLargeImageText(String largeImageText) {
        this.largeImageText = largeImageText == null ? "" : largeImageText.trim();
    }

    public String getSmallImageKey() {
        return smallImageKey == null ? "" : smallImageKey;
    }

    public void setSmallImageKey(String smallImageKey) {
        this.smallImageKey = smallImageKey == null ? "" : smallImageKey.trim();
    }

    public String getSmallImageText() {
        return smallImageText == null ? "" : smallImageText;
    }

    public void setSmallImageText(String smallImageText) {
        this.smallImageText = smallImageText == null ? "" : smallImageText.trim();
    }
}

package com.killer560.hub.runstats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Run Stats settings ({@link RunStatsFeature}). Everything defaults to off / conservative: the
 * feature itself is off, the two options that talk to the server (auto {@code /showextrastats} and the party
 * announcement) are off, and the Hypixel lookup for per-player secrets is off until the user opts in.
 *
 * <p>Reloaded by {@code ProfileManager.reloadAllConfigs()} - add {@code RunStatsConfig.load();} there.
 */
public final class RunStatsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-runstats.json");

    public static final int MAX_DELAY_SECONDS = 15;
    public static final int DEFAULT_DELAY_SECONDS = 3;

    private static RunStatsConfig instance;

    private boolean enabled = false;
    private boolean trackRooms = true;
    private boolean fetchSecrets = false;
    private boolean showDeaths = true;
    private boolean autoShowExtraStats = false;
    private boolean announceToParty = false;
    private int delaySeconds = DEFAULT_DELAY_SECONDS;

    private RunStatsConfig() {
    }

    public static RunStatsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        RunStatsConfig cfg = new RunStatsConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
                cfg.trackRooms = ConfigJson.getBool(obj, "trackRooms", true);
                cfg.fetchSecrets = ConfigJson.getBool(obj, "fetchSecrets", false);
                cfg.showDeaths = ConfigJson.getBool(obj, "showDeaths", true);
                cfg.autoShowExtraStats = ConfigJson.getBool(obj, "autoShowExtraStats", false);
                cfg.announceToParty = ConfigJson.getBool(obj, "announceToParty", false);
                cfg.setDelaySeconds(ConfigJson.getInt(obj, "delaySeconds", DEFAULT_DELAY_SECONDS));
            } catch (Exception ignored) {
                // unreadable file - defaults
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("trackRooms", trackRooms);
            obj.addProperty("fetchSecrets", fetchSecrets);
            obj.addProperty("showDeaths", showDeaths);
            obj.addProperty("autoShowExtraStats", autoShowExtraStats);
            obj.addProperty("announceToParty", announceToParty);
            obj.addProperty("delaySeconds", delaySeconds);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    /** Raw toggle state for the settings tab (ignores the Skyblock gate). */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isTrackRooms() {
        return trackRooms;
    }

    public void setTrackRooms(boolean trackRooms) {
        this.trackRooms = trackRooms;
    }

    public boolean isFetchSecrets() {
        return fetchSecrets;
    }

    public void setFetchSecrets(boolean fetchSecrets) {
        this.fetchSecrets = fetchSecrets;
    }

    public boolean isShowDeaths() {
        return showDeaths;
    }

    public void setShowDeaths(boolean showDeaths) {
        this.showDeaths = showDeaths;
    }

    public boolean isAutoShowExtraStats() {
        return autoShowExtraStats;
    }

    public void setAutoShowExtraStats(boolean autoShowExtraStats) {
        this.autoShowExtraStats = autoShowExtraStats;
    }

    public boolean isAnnounceToParty() {
        return announceToParty;
    }

    public void setAnnounceToParty(boolean announceToParty) {
        this.announceToParty = announceToParty;
    }

    public int getDelaySeconds() {
        return delaySeconds;
    }

    public void setDelaySeconds(int seconds) {
        this.delaySeconds = Math.max(0, Math.min(MAX_DELAY_SECONDS, seconds));
    }
}

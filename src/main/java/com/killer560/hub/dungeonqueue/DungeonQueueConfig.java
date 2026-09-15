package com.killer560.hub.dungeonqueue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Auto Requeue settings ({@link DungeonQueueFeature}) - the same three options as Odin's
 *  {@code DungeonQueue.kt}: Auto Requeue (off), Requeue Delay (2s, 0-30), Disable on leave/kick (on). The Party
 *  Finder Overlay half of the Dungeon Queue tab lives in {@code partyfinder.PartyFinderOverlayConfig}. */
public final class DungeonQueueConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-dungeonqueue.json");

    public static final int MAX_DELAY_SECONDS = 30;
    public static final int DEFAULT_DELAY_SECONDS = 2; // Odin's default

    private static DungeonQueueConfig instance;

    private boolean enabled = false;
    private int delaySeconds = DEFAULT_DELAY_SECONDS;
    private boolean disableOnLeave = true;

    private DungeonQueueConfig() {
    }

    public static DungeonQueueConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        DungeonQueueConfig cfg = new DungeonQueueConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
                cfg.setDelaySeconds(ConfigJson.getInt(obj, "delaySeconds", DEFAULT_DELAY_SECONDS));
                cfg.disableOnLeave = ConfigJson.getBool(obj, "disableOnLeave", true);
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
            obj.addProperty("delaySeconds", delaySeconds);
            obj.addProperty("disableOnLeave", disableOnLeave);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    /** Raw toggle state for the settings tab (ignores the Skyblock gate). */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDelaySeconds() {
        return delaySeconds;
    }

    public void setDelaySeconds(int seconds) {
        this.delaySeconds = Math.max(0, Math.min(MAX_DELAY_SECONDS, seconds));
    }

    public boolean isDisableOnLeave() {
        return disableOnLeave;
    }

    public void setDisableOnLeave(boolean disableOnLeave) {
        this.disableOnLeave = disableOnLeave;
    }
}

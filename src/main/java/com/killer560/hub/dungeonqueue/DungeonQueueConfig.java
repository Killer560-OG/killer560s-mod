package com.killer560.hub.dungeonqueue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted "Dungeon Queue" settings ({@link DungeonQueueFeature}). Every setting loads and saves. The feature
 *  itself defaults OFF; Leader Check and Downtime Check default ON because they only ever stop a re-queue. */
public final class DungeonQueueConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-dungeonqueue.json");

    public static final int MAX_DELAY_SECONDS = 30;
    public static final int DEFAULT_DELAY_SECONDS = 5; // NoammAddons AutoRequeue's default
    public static final String DEFAULT_DT_KEYWORD = "dt";

    private static DungeonQueueConfig instance;

    private boolean enabled = false;
    private int delaySeconds = DEFAULT_DELAY_SECONDS;
    private boolean leaderCheck = true;
    private boolean downtimeCheck = true;
    private String dtKeyword = DEFAULT_DT_KEYWORD;
    private int cancelKeyCode = -1;
    private int requeueKeyCode = -1;

    private DungeonQueueConfig() {
    }

    public static DungeonQueueConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new DungeonQueueConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            DungeonQueueConfig cfg = new DungeonQueueConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.setDelaySeconds(obj.has("delaySeconds") ? obj.get("delaySeconds").getAsInt() : DEFAULT_DELAY_SECONDS);
            cfg.leaderCheck = !obj.has("leaderCheck") || obj.get("leaderCheck").getAsBoolean();
            cfg.downtimeCheck = !obj.has("downtimeCheck") || obj.get("downtimeCheck").getAsBoolean();
            cfg.setDtKeyword(obj.has("dtKeyword") ? obj.get("dtKeyword").getAsString() : DEFAULT_DT_KEYWORD);
            cfg.cancelKeyCode = obj.has("cancelKeyCode") ? obj.get("cancelKeyCode").getAsInt() : -1;
            cfg.requeueKeyCode = obj.has("requeueKeyCode") ? obj.get("requeueKeyCode").getAsInt() : -1;
            instance = cfg;
        } catch (Exception e) {
            instance = new DungeonQueueConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("delaySeconds", delaySeconds);
            obj.addProperty("leaderCheck", leaderCheck);
            obj.addProperty("downtimeCheck", downtimeCheck);
            obj.addProperty("dtKeyword", dtKeyword);
            obj.addProperty("cancelKeyCode", cancelKeyCode);
            obj.addProperty("requeueKeyCode", requeueKeyCode);
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

    public int getDelaySeconds() {
        return delaySeconds;
    }

    public void setDelaySeconds(int seconds) {
        this.delaySeconds = Math.max(0, Math.min(MAX_DELAY_SECONDS, seconds));
    }

    public boolean isLeaderCheck() {
        return leaderCheck;
    }

    public void setLeaderCheck(boolean leaderCheck) {
        this.leaderCheck = leaderCheck;
    }

    public boolean isDowntimeCheck() {
        return downtimeCheck;
    }

    public void setDowntimeCheck(boolean downtimeCheck) {
        this.downtimeCheck = downtimeCheck;
    }

    public String getDtKeyword() {
        return dtKeyword;
    }

    public void setDtKeyword(String keyword) {
        this.dtKeyword = keyword == null ? "" : keyword.trim();
    }

    public int getCancelKeyCode() {
        return cancelKeyCode;
    }

    public void setCancelKeyCode(int cancelKeyCode) {
        this.cancelKeyCode = cancelKeyCode;
    }

    public int getRequeueKeyCode() {
        return requeueKeyCode;
    }

    public void setRequeueKeyCode(int requeueKeyCode) {
        this.requeueKeyCode = requeueKeyCode;
    }
}

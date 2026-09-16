package com.killer560.hub.runsummary;

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
 * Persisted Dungeon Run Summary SETTINGS - {@code config/killer560smod-runsummary.json}, a normal
 * {@code killer560smod-*.json} settings file so profiles carry it. The run HISTORY itself deliberately
 * does not live here: see {@link RunHistoryStore} ({@code config/killer560smod-runs/history.json}).
 * <p>
 * Ships disabled by default, and the compact chat summary ships OFF on top of that so it can never
 * fight {@code splittimers/SplitTimersFeature}'s existing end-of-run chat summary.
 */
public final class RunSummaryConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-runsummary.json");

    public static final int MIN_RUNS = 5;
    public static final int MAX_RUNS = RunHistoryStore.HARD_MAX;
    public static final int DEFAULT_RUNS = 50;

    private static RunSummaryConfig instance;

    private boolean enabled = false;
    /** Compact end-of-run chat summary. OFF by default - Split Timers already prints one. */
    private boolean chatSummary = false;
    /** Skip the phase-time lines of our chat summary while Split Timers is already printing them. */
    private boolean avoidDuplicateChat = true;
    /** Announce "personal best" in chat when a run beats a stored per-floor best. */
    private boolean announcePersonalBests = true;
    /** Include device/lever/terminal completion times in the stored record and the summary view. */
    private boolean recordDeviceTimes = true;
    private int maxRuns = DEFAULT_RUNS;

    private RunSummaryConfig() {
    }

    public static RunSummaryConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new RunSummaryConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            RunSummaryConfig cfg = new RunSummaryConfig();
            // Per-key readers (ConfigJson): one malformed value only resets its own key, never the file.
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.chatSummary = ConfigJson.getBool(obj, "chatSummary", false);
            cfg.avoidDuplicateChat = ConfigJson.getBool(obj, "avoidDuplicateChat", true);
            cfg.announcePersonalBests = ConfigJson.getBool(obj, "announcePersonalBests", true);
            cfg.recordDeviceTimes = ConfigJson.getBool(obj, "recordDeviceTimes", true);
            cfg.maxRuns = clampRuns(ConfigJson.getInt(obj, "maxRuns", DEFAULT_RUNS));
            instance = cfg;
        } catch (Exception e) {
            instance = new RunSummaryConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("chatSummary", chatSummary);
            obj.addProperty("avoidDuplicateChat", avoidDuplicateChat);
            obj.addProperty("announcePersonalBests", announcePersonalBests);
            obj.addProperty("recordDeviceTimes", recordDeviceTimes);
            obj.addProperty("maxRuns", maxRuns);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static int clampRuns(int value) {
        return Math.max(MIN_RUNS, Math.min(MAX_RUNS, value));
    }

    /** Gated: off everywhere that isn't Skyblock/p3sim while "Skyblock Only" is on. */
    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    /** The raw saved value, for the settings GUI and for anything that must ignore the gate. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isChatSummary() {
        return chatSummary;
    }

    public void setChatSummary(boolean chatSummary) {
        this.chatSummary = chatSummary;
    }

    public boolean isAvoidDuplicateChat() {
        return avoidDuplicateChat;
    }

    public void setAvoidDuplicateChat(boolean avoidDuplicateChat) {
        this.avoidDuplicateChat = avoidDuplicateChat;
    }

    public boolean isAnnouncePersonalBests() {
        return announcePersonalBests;
    }

    public void setAnnouncePersonalBests(boolean announcePersonalBests) {
        this.announcePersonalBests = announcePersonalBests;
    }

    public boolean isRecordDeviceTimes() {
        return recordDeviceTimes;
    }

    public void setRecordDeviceTimes(boolean recordDeviceTimes) {
        this.recordDeviceTimes = recordDeviceTimes;
    }

    public int getMaxRuns() {
        return maxRuns;
    }

    public void setMaxRuns(int maxRuns) {
        this.maxRuns = clampRuns(maxRuns);
    }
}

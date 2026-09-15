package com.killer560.hub.croesus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted settings for Chest Profit, the Croesus Profit Logger and Auto Croesus - see
 *  {@link ChestProfitFeature}, {@link CroesusProfitLog}, {@link AutoCroesusFeature}. Everything ships OFF. */
public final class CroesusConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-croesus.json");

    public static final int MIN_DELAY_BOUND_MS = 100;
    public static final int MAX_DELAY_BOUND_MS = 2000;
    /** Min-profit slider range for Auto Croesus, in thousands of coins (0 - 20M). */
    public static final int MAX_MIN_PROFIT_K = 20_000;

    private static CroesusConfig instance;

    private boolean chestProfitEnabled = false;
    private boolean includeEssence = true;
    private boolean highlightBest = true;
    private boolean loggerEnabled = false;
    private boolean loggerChatSummary = true;
    private boolean autoCroesusEnabled = false;
    private int autoMinProfitK = 0;
    private int autoMinDelayMs = 350;
    private int autoMaxDelayMs = 700;

    private CroesusConfig() {
    }

    public static CroesusConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new CroesusConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            CroesusConfig cfg = new CroesusConfig();
            cfg.chestProfitEnabled = obj.has("chestProfitEnabled") && obj.get("chestProfitEnabled").getAsBoolean();
            cfg.includeEssence = !obj.has("includeEssence") || obj.get("includeEssence").getAsBoolean();
            cfg.highlightBest = !obj.has("highlightBest") || obj.get("highlightBest").getAsBoolean();
            cfg.loggerEnabled = obj.has("loggerEnabled") && obj.get("loggerEnabled").getAsBoolean();
            cfg.loggerChatSummary = !obj.has("loggerChatSummary") || obj.get("loggerChatSummary").getAsBoolean();
            cfg.autoCroesusEnabled = obj.has("autoCroesusEnabled") && obj.get("autoCroesusEnabled").getAsBoolean();
            if (obj.has("autoMinProfitK")) {
                cfg.autoMinProfitK = clamp(obj.get("autoMinProfitK").getAsInt(), 0, MAX_MIN_PROFIT_K);
            }
            if (obj.has("autoMinDelayMs")) {
                cfg.autoMinDelayMs = clamp(obj.get("autoMinDelayMs").getAsInt(), MIN_DELAY_BOUND_MS, MAX_DELAY_BOUND_MS);
            }
            if (obj.has("autoMaxDelayMs")) {
                cfg.autoMaxDelayMs = clamp(obj.get("autoMaxDelayMs").getAsInt(), MIN_DELAY_BOUND_MS, MAX_DELAY_BOUND_MS);
            }
            instance = cfg;
        } catch (Exception e) {
            instance = new CroesusConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("chestProfitEnabled", chestProfitEnabled);
            obj.addProperty("includeEssence", includeEssence);
            obj.addProperty("highlightBest", highlightBest);
            obj.addProperty("loggerEnabled", loggerEnabled);
            obj.addProperty("loggerChatSummary", loggerChatSummary);
            obj.addProperty("autoCroesusEnabled", autoCroesusEnabled);
            obj.addProperty("autoMinProfitK", autoMinProfitK);
            obj.addProperty("autoMinDelayMs", autoMinDelayMs);
            obj.addProperty("autoMaxDelayMs", autoMaxDelayMs);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public boolean isChestProfitEnabled() {
        return chestProfitEnabled;
    }

    public void setChestProfitEnabled(boolean v) {
        this.chestProfitEnabled = v;
    }

    public boolean isIncludeEssence() {
        return includeEssence;
    }

    public void setIncludeEssence(boolean v) {
        this.includeEssence = v;
    }

    public boolean isHighlightBest() {
        return highlightBest;
    }

    public void setHighlightBest(boolean v) {
        this.highlightBest = v;
    }

    public boolean isLoggerEnabled() {
        return loggerEnabled;
    }

    public void setLoggerEnabled(boolean v) {
        this.loggerEnabled = v;
    }

    public boolean isLoggerChatSummary() {
        return loggerChatSummary;
    }

    public void setLoggerChatSummary(boolean v) {
        this.loggerChatSummary = v;
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - real automated clicking,
     *  same gate as SecretsConfig / TerminalSolverConfig's auto features. */
    public boolean isAutoCroesusEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoCroesusEnabled;
    }

    public boolean getAutoCroesusEnabledRaw() {
        return autoCroesusEnabled;
    }

    public void setAutoCroesusEnabled(boolean v) {
        this.autoCroesusEnabled = v;
    }

    public int getAutoMinProfitK() {
        return autoMinProfitK;
    }

    public void setAutoMinProfitK(int v) {
        this.autoMinProfitK = clamp(v, 0, MAX_MIN_PROFIT_K);
    }

    public int getAutoMinDelayMs() {
        return autoMinDelayMs;
    }

    public void setAutoMinDelayMs(int v) {
        this.autoMinDelayMs = clamp(v, MIN_DELAY_BOUND_MS, MAX_DELAY_BOUND_MS);
        if (autoMaxDelayMs < autoMinDelayMs) {
            autoMaxDelayMs = autoMinDelayMs;
        }
    }

    public int getAutoMaxDelayMs() {
        return autoMaxDelayMs;
    }

    public void setAutoMaxDelayMs(int v) {
        this.autoMaxDelayMs = clamp(v, MIN_DELAY_BOUND_MS, MAX_DELAY_BOUND_MS);
        if (autoMinDelayMs > autoMaxDelayMs) {
            autoMinDelayMs = autoMaxDelayMs;
        }
    }
}

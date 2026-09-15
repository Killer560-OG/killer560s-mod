package com.killer560.hub.autopuzzles;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted "Auto Puzzles" settings - see {@link AutoPuzzlesFeature}. Real automation (clicks puzzle blocks /
 * NPCs for you), so every enable getter is gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED}
 * and everything ships OFF.
 */
public final class AutoPuzzlesConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-autopuzzles.json");

    public static final int MAX_DELAY_MS = 2000;
    public static final int DELAY_STEP_MS = 50;

    private static AutoPuzzlesConfig instance;

    private boolean autoQuizEnabled = false;
    private int quizDelayMs = 250;
    private boolean autoWeirdosEnabled = false;
    private int weirdosDelayMs = 250;
    // QUOI's ThreeWeirdos auto also right-clicks the 3 "CLICK" NPC stands so they say their lines.
    private boolean weirdosTalkToNpcs = false;

    private AutoPuzzlesConfig() {
    }

    public static AutoPuzzlesConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new AutoPuzzlesConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            AutoPuzzlesConfig cfg = new AutoPuzzlesConfig();
            cfg.autoQuizEnabled = obj.has("autoQuizEnabled") && obj.get("autoQuizEnabled").getAsBoolean();
            cfg.quizDelayMs = obj.has("quizDelayMs") ? clampDelay(obj.get("quizDelayMs").getAsInt()) : cfg.quizDelayMs;
            cfg.autoWeirdosEnabled = obj.has("autoWeirdosEnabled") && obj.get("autoWeirdosEnabled").getAsBoolean();
            cfg.weirdosDelayMs = obj.has("weirdosDelayMs") ? clampDelay(obj.get("weirdosDelayMs").getAsInt()) : cfg.weirdosDelayMs;
            cfg.weirdosTalkToNpcs = obj.has("weirdosTalkToNpcs") && obj.get("weirdosTalkToNpcs").getAsBoolean();
            instance = cfg;
        } catch (Exception e) {
            instance = new AutoPuzzlesConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("autoQuizEnabled", autoQuizEnabled);
            obj.addProperty("quizDelayMs", quizDelayMs);
            obj.addProperty("autoWeirdosEnabled", autoWeirdosEnabled);
            obj.addProperty("weirdosDelayMs", weirdosDelayMs);
            obj.addProperty("weirdosTalkToNpcs", weirdosTalkToNpcs);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static int clampDelay(int ms) {
        return Math.max(0, Math.min(MAX_DELAY_MS, ms));
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - real automation. */
    public boolean isAutoQuizEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoQuizEnabled;
    }

    public void setAutoQuizEnabled(boolean enabled) {
        this.autoQuizEnabled = enabled;
    }

    public int getQuizDelayMs() {
        return quizDelayMs;
    }

    public void setQuizDelayMs(int ms) {
        this.quizDelayMs = clampDelay(ms);
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - real automation. */
    public boolean isAutoWeirdosEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoWeirdosEnabled;
    }

    public void setAutoWeirdosEnabled(boolean enabled) {
        this.autoWeirdosEnabled = enabled;
    }

    public int getWeirdosDelayMs() {
        return weirdosDelayMs;
    }

    public void setWeirdosDelayMs(int ms) {
        this.weirdosDelayMs = clampDelay(ms);
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} and Auto Three Weirdos itself. */
    public boolean isWeirdosTalkToNpcs() {
        return isAutoWeirdosEnabled() && weirdosTalkToNpcs;
    }

    public boolean getWeirdosTalkToNpcsRaw() {
        return weirdosTalkToNpcs;
    }

    public void setWeirdosTalkToNpcs(boolean enabled) {
        this.weirdosTalkToNpcs = enabled;
    }
}

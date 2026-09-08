package com.killer560.hub.experiments;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Experimentation Table solver settings. */
public final class ExperimentsConfig {

    public static final int MIN_DELAY_MS = 0;
    public static final int MAX_DELAY_MS = 1000;
    public static final int MIN_AUTO_RENEW_COUNT = 0;
    public static final int MAX_AUTO_RENEW_COUNT = 3;
    public static final double MIN_TITANIC_MAX_PRICE = 0;
    public static final double MAX_TITANIC_MAX_PRICE = 3_000_000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-experiments.json");

    private static ExperimentsConfig instance;

    private boolean enabled = true;
    private boolean superpairsEnabled = true;
    /** false (default) = pair every revealed match; true = skip the plain "Experience" reward tiles
     *  (always a dye-family item - see {@link ExperimentSolver#isValuablePair}) and pair everything
     *  else - useful since Superpairs clicks are a limited resource (base amount plus whatever bonus
     *  Chronomatron/Ultrasequencer earned), so killer560 may want to spend them on the non-plain-XP
     *  pairs instead. */
    private boolean superpairsValuableOnly = false;
    private int delayMs = 200;
    /** false = solver only (killer560 navigates into a game manually, the mod just plays it);
     *  true = autonomous (the mod also opens Chronomatron/Ultrasequencer and picks the highest
     *  tier itself, so the whole thing can be left running AFK). */
    private boolean autonomousMode = false;
    /** Extra one-time wait before the first click of EACH newly-revealed round inside Chronomatron/
     *  Ultrasequencer specifically (see {@link ExperimentSolver}) - every other click, and every
     *  menu-navigation click, is unaffected by this setting. */
    private int firstClickDelayMs = 1000;
    private ExperimentStopStrategy stopStrategy = ExperimentStopStrategy.MAX_CLICKS;
    /** Only takes effect while autonomousMode is also on - blocks killer560's own mouse/keyboard input
     *  to container screens while autonomous mode is running, so an accidental click/keypress can't
     *  interfere with it. Never affects the mod's own synthetic clicks, which go straight through
     *  MultiPlayerGameMode.handleContainerInput(...) rather than the screen's input handlers. */
    private boolean blockInputEnabled = false;
    /** How many "Renew Experiments" charge-purchases to make per day, 0-3, matching Hypixel's own
     *  real "You've renewed N/3 charges today!" cap - 0 (default) means never auto-buy. */
    private int autoRenewCount = 0;
    /** Max Bazaar buy price (coins) autonomous mode will pay to instantly buy a Titanic Experience
     *  Bottle when the table's own "Missing experience?" prompt shows up - 0 (default) means never
     *  auto-buy. No practical minimum beyond 0; capped at 3,000,000 per killer560's own limit. */
    private double titanicMaxPriceCoins = 0;
    /** Random extra delay (0-1000ms) added on top of whatever fixed delay already decided it was
     *  time to click - applies to every click the mod performs (solver clicks, menu navigation,
     *  claiming rewards, buying charges/bottles), per killer560's explicit "added onto any click it
     *  performs." 0 (default) disables it entirely - every click fires immediately, same as before. */
    private int randomDelayMaxMs = 0;
    /** Autonomous mode only: before starting/resuming a run, back out to {@code /pets} and equip the
     *  best owned Guardian (highest rarity, then highest level), then reopen the table - once per
     *  run. Off by default since it touches a screen outside the table entirely. */
    private boolean autoSwapGuardianPet = false;
    /** GLFW key code that immediately cancels a running autonomous session (see
     *  {@link com.killer560.hub.experiments.ExperimentsFeature#emergencyCancel()}), or -1 if unbound.
     *  Polled directly against raw keyboard state every tick (same mechanism the HUD editor's own
     *  keybind already uses), NOT through any container screen's key-event handling - per killer560's
     *  explicit "this key should work even on the prevent keypress option," since that setting only
     *  ever blocks input routed through a container screen's own listeners. */
    private int emergencyCancelKeyCode = -1;

    private ExperimentsConfig() {
    }

    public static ExperimentsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new ExperimentsConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            ExperimentsConfig cfg = new ExperimentsConfig();
            cfg.enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
            cfg.superpairsEnabled = !obj.has("superpairsEnabled") || obj.get("superpairsEnabled").getAsBoolean();
            cfg.superpairsValuableOnly = obj.has("superpairsValuableOnly") && obj.get("superpairsValuableOnly").getAsBoolean();
            cfg.delayMs = obj.has("delayMs")
                    ? Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, obj.get("delayMs").getAsInt())) : 200;
            cfg.autonomousMode = obj.has("autonomousMode") && obj.get("autonomousMode").getAsBoolean();
            cfg.firstClickDelayMs = obj.has("firstClickDelayMs")
                    ? Math.max(0, obj.get("firstClickDelayMs").getAsInt()) : 1000;
            if (obj.has("stopStrategy")) {
                try {
                    cfg.stopStrategy = ExperimentStopStrategy.valueOf(obj.get("stopStrategy").getAsString());
                } catch (IllegalArgumentException ignored) {
                }
            }
            cfg.blockInputEnabled = obj.has("blockInputEnabled") && obj.get("blockInputEnabled").getAsBoolean();
            cfg.autoRenewCount = obj.has("autoRenewCount")
                    ? Math.max(MIN_AUTO_RENEW_COUNT, Math.min(MAX_AUTO_RENEW_COUNT, obj.get("autoRenewCount").getAsInt())) : 0;
            cfg.titanicMaxPriceCoins = obj.has("titanicMaxPriceCoins")
                    ? Math.max(MIN_TITANIC_MAX_PRICE, Math.min(MAX_TITANIC_MAX_PRICE, obj.get("titanicMaxPriceCoins").getAsDouble())) : 0;
            cfg.randomDelayMaxMs = obj.has("randomDelayMaxMs")
                    ? Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, obj.get("randomDelayMaxMs").getAsInt())) : 0;
            cfg.autoSwapGuardianPet = obj.has("autoSwapGuardianPet") && obj.get("autoSwapGuardianPet").getAsBoolean();
            cfg.emergencyCancelKeyCode = obj.has("emergencyCancelKeyCode") ? obj.get("emergencyCancelKeyCode").getAsInt() : -1;
            instance = cfg;
        } catch (Exception e) {
            instance = new ExperimentsConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("superpairsEnabled", superpairsEnabled);
            obj.addProperty("superpairsValuableOnly", superpairsValuableOnly);
            obj.addProperty("delayMs", delayMs);
            obj.addProperty("autonomousMode", autonomousMode);
            obj.addProperty("firstClickDelayMs", firstClickDelayMs);
            obj.addProperty("stopStrategy", stopStrategy.name());
            obj.addProperty("blockInputEnabled", blockInputEnabled);
            obj.addProperty("autoRenewCount", autoRenewCount);
            obj.addProperty("titanicMaxPriceCoins", titanicMaxPriceCoins);
            obj.addProperty("randomDelayMaxMs", randomDelayMaxMs);
            obj.addProperty("autoSwapGuardianPet", autoSwapGuardianPet);
            obj.addProperty("emergencyCancelKeyCode", emergencyCancelKeyCode);
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

    public boolean isSuperpairsEnabled() {
        return superpairsEnabled;
    }

    public void setSuperpairsEnabled(boolean superpairsEnabled) {
        this.superpairsEnabled = superpairsEnabled;
    }

    public boolean isSuperpairsValuableOnly() {
        return superpairsValuableOnly;
    }

    public void setSuperpairsValuableOnly(boolean superpairsValuableOnly) {
        this.superpairsValuableOnly = superpairsValuableOnly;
    }

    public int getDelayMs() {
        return delayMs;
    }

    public void setDelayMs(int delayMs) {
        this.delayMs = Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, delayMs));
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - per killer560's explicit
     *  split (2026-09-08) between a "legit" public build (everything except Autonomous auto-clicking,
     *  which is a real macro against Hypixel's rules) and a "cheat" build (everything, including it).
     *  Deliberately gated HERE, the single real source every autonomous code path already checks
     *  through {@code isAutonomousMode()}, rather than at each individual call site - means the legit
     *  jar can never run Autonomous mode even if a saved config.json has {@code autonomousMode: true}
     *  in it (e.g. copied from a cheat-build install), since the raw field is never what gets read. */
    public boolean isAutonomousMode() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autonomousMode;
    }

    public void setAutonomousMode(boolean autonomousMode) {
        this.autonomousMode = autonomousMode;
    }

    public int getFirstClickDelayMs() {
        return firstClickDelayMs;
    }

    public void setFirstClickDelayMs(int firstClickDelayMs) {
        this.firstClickDelayMs = Math.max(0, firstClickDelayMs);
    }

    public ExperimentStopStrategy getStopStrategy() {
        return stopStrategy;
    }

    public void setStopStrategy(ExperimentStopStrategy stopStrategy) {
        this.stopStrategy = stopStrategy;
    }

    public boolean isBlockInputEnabled() {
        return blockInputEnabled;
    }

    public void setBlockInputEnabled(boolean blockInputEnabled) {
        this.blockInputEnabled = blockInputEnabled;
    }

    public int getAutoRenewCount() {
        return autoRenewCount;
    }

    public void setAutoRenewCount(int autoRenewCount) {
        this.autoRenewCount = Math.max(MIN_AUTO_RENEW_COUNT, Math.min(MAX_AUTO_RENEW_COUNT, autoRenewCount));
    }

    public double getTitanicMaxPriceCoins() {
        return titanicMaxPriceCoins;
    }

    public void setTitanicMaxPriceCoins(double titanicMaxPriceCoins) {
        this.titanicMaxPriceCoins = Math.max(MIN_TITANIC_MAX_PRICE, Math.min(MAX_TITANIC_MAX_PRICE, titanicMaxPriceCoins));
    }

    public int getRandomDelayMaxMs() {
        return randomDelayMaxMs;
    }

    public void setRandomDelayMaxMs(int randomDelayMaxMs) {
        this.randomDelayMaxMs = Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, randomDelayMaxMs));
    }

    public boolean isAutoSwapGuardianPet() {
        return autoSwapGuardianPet;
    }

    public void setAutoSwapGuardianPet(boolean autoSwapGuardianPet) {
        this.autoSwapGuardianPet = autoSwapGuardianPet;
    }

    public int getEmergencyCancelKeyCode() {
        return emergencyCancelKeyCode;
    }

    public void setEmergencyCancelKeyCode(int emergencyCancelKeyCode) {
        this.emergencyCancelKeyCode = emergencyCancelKeyCode;
    }
}

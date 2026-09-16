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
    public static final int MIN_SUPERPAIRS_TIMEOUT_MARGIN_MS = 0;
    public static final int MAX_SUPERPAIRS_TIMEOUT_MARGIN_MS = 1000;
    public static final int DEFAULT_SUPERPAIRS_TIMEOUT_MARGIN_MS = 250;

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
    /** Solver Only mode only: while on (default), a manual click on the wrong Chronomatron/
     *  Ultrasequencer slot is swallowed instead of reaching the game, per
     *  {@link ExperimentsFeature#shouldBlockManualMisclick}. Holding Shift always bypasses the block
     *  regardless of this setting; this toggle only controls whether the protection exists at all for
     *  a plain (non-Shift) click. Previously always-on with no way to disable - promoted to a real
     *  setting per killer560's request (2026-09-08). */
    private boolean clickProtectionEnabled = true;
    /** Solver Only mode only: sends a real client-side chat message once the max-clicks threshold
     *  (the same "Chain of N:"/"Series of N:" lore auto-detection Autonomous mode's MAX_CLICKS stop
     *  strategy uses) is reached, since Solver Only never stops or announces anything on its own.
     *  Per killer560's request (2026-09-08). Extended (2026-09-15 roadmap) to fire in BOTH Solver Only
     *  and Autonomous mode, and to Superpairs when its "Remaining Clicks" counter hits 0. Still purely
     *  a local chat message + UI sound, hence still default ON. */
    private boolean notifyMaxClicksReached = true;
    /** Experimentation Table profit tracker (see {@link ExperimentsProfitTracker}) - purely
     *  observational logging of claimed rewards/XP/Bits, independent of the solver toggle. Off by
     *  default (roadmap item, 2026-09-15). */
    private boolean profitTrackerEnabled = false;
    /** Superpairs confirm-timeout scales with measured round-trip latency (see
     *  {@code ExperimentSolver#superpairsConfirmTimeoutMs}) instead of the flat 1000ms. Default OFF: at
     *  low ping the adaptive value drops BELOW the old flat 1000ms, so it is not strictly safer. */
    private boolean superpairsAdaptiveTimeout = false;
    /** Fixed margin added on top of the latency-scaled part of the adaptive Superpairs timeout. */
    private int superpairsTimeoutMarginMs = DEFAULT_SUPERPAIRS_TIMEOUT_MARGIN_MS;

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
            cfg.emergencyCancelKeyCode = obj.has("emergencyCancelKeyCode") ? com.killer560.hub.util.KeyUtil.sanitize(obj.get("emergencyCancelKeyCode").getAsInt()) : -1;
            cfg.clickProtectionEnabled = !obj.has("clickProtectionEnabled") || obj.get("clickProtectionEnabled").getAsBoolean();
            cfg.notifyMaxClicksReached = !obj.has("notifyMaxClicksReached") || obj.get("notifyMaxClicksReached").getAsBoolean();
            cfg.profitTrackerEnabled = obj.has("profitTrackerEnabled") && obj.get("profitTrackerEnabled").getAsBoolean();
            cfg.superpairsAdaptiveTimeout = obj.has("superpairsAdaptiveTimeout") && obj.get("superpairsAdaptiveTimeout").getAsBoolean();
            cfg.superpairsTimeoutMarginMs = obj.has("superpairsTimeoutMarginMs")
                    ? clampMargin(obj.get("superpairsTimeoutMarginMs").getAsInt()) : DEFAULT_SUPERPAIRS_TIMEOUT_MARGIN_MS;
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
            obj.addProperty("clickProtectionEnabled", clickProtectionEnabled);
            obj.addProperty("notifyMaxClicksReached", notifyMaxClicksReached);
            obj.addProperty("profitTrackerEnabled", profitTrackerEnabled);
            obj.addProperty("superpairsAdaptiveTimeout", superpairsAdaptiveTimeout);
            obj.addProperty("superpairsTimeoutMarginMs", superpairsTimeoutMarginMs);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
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

    public boolean isClickProtectionEnabled() {
        return clickProtectionEnabled;
    }

    public void setClickProtectionEnabled(boolean clickProtectionEnabled) {
        this.clickProtectionEnabled = clickProtectionEnabled;
    }

    public boolean isNotifyMaxClicksReached() {
        return notifyMaxClicksReached;
    }

    public void setNotifyMaxClicksReached(boolean notifyMaxClicksReached) {
        this.notifyMaxClicksReached = notifyMaxClicksReached;
    }

    public boolean isProfitTrackerEnabled() {
        return profitTrackerEnabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setProfitTrackerEnabled(boolean profitTrackerEnabled) {
        this.profitTrackerEnabled = profitTrackerEnabled;
    }

    /** Gated like {@link #isAutonomousMode()} - the confirm-timeout only ever applies to Autonomous
     *  Superpairs auto-clicking, which the legit build can never run. */
    public boolean isSuperpairsAdaptiveTimeout() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && superpairsAdaptiveTimeout;
    }

    public void setSuperpairsAdaptiveTimeout(boolean superpairsAdaptiveTimeout) {
        this.superpairsAdaptiveTimeout = superpairsAdaptiveTimeout;
    }

    public int getSuperpairsTimeoutMarginMs() {
        return superpairsTimeoutMarginMs;
    }

    public void setSuperpairsTimeoutMarginMs(int superpairsTimeoutMarginMs) {
        this.superpairsTimeoutMarginMs = clampMargin(superpairsTimeoutMarginMs);
    }

    private static int clampMargin(int ms) {
        return Math.max(MIN_SUPERPAIRS_TIMEOUT_MARGIN_MS, Math.min(MAX_SUPERPAIRS_TIMEOUT_MARGIN_MS, ms));
    }
}

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

    /** Bumped when a saved file needs rewriting under new meaning. 1 = the 2026-09-30 split of the solver
     *  from the automation (see {@link #enabled} and {@link #isAutonomousMode()}). Written by every
     *  {@link #save()}; a file without it is pre-split and gets the one-time fix-up in {@link #load()}.
     *  No setting's KEY changed in that split - only which of two now-independent toggles owns it. */
    private static final int CURRENT_CONFIG_VERSION = 1;

    public static final int MIN_DELAY_MS = 0;
    public static final int MAX_DELAY_MS = 1000;
    public static final int MIN_AUTO_RENEW_COUNT = 0;
    public static final int MAX_AUTO_RENEW_COUNT = 3;
    public static final double MIN_TITANIC_MAX_PRICE = 0;
    public static final double MAX_TITANIC_MAX_PRICE = 3_000_000;
    /** First Click Delay had no ceiling while it was a typed box; the slider that replaced it (2026-10-04)
     *  needs one. 3s is three times the default, and a saved value above it loads clamped. */
    public static final int MAX_FIRST_CLICK_DELAY_MS = 3000;
    /** Every delay slider in the Auto E-Table section moves in steps of this many ms. */
    public static final int DELAY_STEP_MS = 50;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-experiments.json");

    private static ExperimentsConfig instance;

    /** The SOLVER: slot highlighting, the solver's own game-state observation, click protection. Since
     *  the 2026-09-30 split (killer560: "The solver should be its own setting") this is no longer a master
     *  switch over the automation as well - {@link #isAutonomousMode()} is now independent of it, so the
     *  solver can be run with no automation (the old "Solver Only" mode), the automation with no
     *  highlights, or both. Same {@code "enabled"} JSON key and same default as before the split. */
    private boolean enabled = true;
    private boolean superpairsEnabled = true;
    /** false (default) = pair every revealed match; true = skip the plain "Experience" reward tiles
     *  (always a dye-family item - see {@link ExperimentSolver#isValuablePair}) and pair everything
     *  else - useful since Superpairs clicks are a limited resource (base amount plus whatever bonus
     *  Chronomatron/Ultrasequencer earned), so killer560 may want to spend them on the non-plain-XP
     *  pairs instead. */
    private boolean superpairsValuableOnly = false;
    private int delayMs = 200;
    /** The AUTOMATION ("Auto E-Table"): the mod opens Chronomatron/Ultrasequencer, picks the highest tier
     *  itself, clicks the puzzles, claims rewards and buys renews, so the whole thing can be left running
     *  AFK. Until 2026-09-30 this was one half of a two-way Mode switch under {@link #enabled} - the other
     *  half being "Solver Only" - which made the solver unreachable as a setting of its own. It is now an
     *  independent toggle: same {@code "autonomousMode"} JSON key, same default (off), but no longer
     *  requires {@code enabled}. */
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
    /** Superpairs' flat 1000ms confirm-timeout is extended by however long the server stalls while a
     *  click waits (see {@code ExperimentSolver#superpairsConfirmTimeoutMs} and {@link ServerLagSensor}).
     *  With no lag it changes nothing. The fixed "Timeout Margin" it used to carry was removed on
     *  2026-10-04 (killer560: "that shouldn't have a margin"); an old file's {@code superpairsTimeoutMarginMs}
     *  key is simply no longer read. */
    private boolean superpairsAdaptiveTimeout = false;

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
                    ? Math.max(0, Math.min(MAX_FIRST_CLICK_DELAY_MS, obj.get("firstClickDelayMs").getAsInt())) : 1000;
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
            // One-time fix-up for a file written before the 2026-09-30 solver/automation split. Back then
            // "enabled: false" meant the WHOLE Experimentation Table feature was off and "autonomousMode"
            // was a dead leftover underneath it; now the two are independent, so that file would otherwise
            // come back with the automation newly live. Keyed off the version so it can only ever happen
            // once - applying it on every load would permanently couple the two toggles back together and
            // silently undo "solver off, automation on".
            int version = com.killer560.hub.util.ConfigJson.getInt(obj, "configVersion", 0);
            if (version < 1 && !cfg.enabled) {
                cfg.autonomousMode = false;
            }
            instance = cfg;
        } catch (Exception e) {
            instance = new ExperimentsConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("configVersion", CURRENT_CONFIG_VERSION);
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
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** @return whether the SOLVER (highlights, observation, click protection) is on. Since 2026-09-30 this
     *  no longer gates the automation - see {@link #isAutonomousMode()}. */
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
     *  in it (e.g. copied from a cheat-build install), since the raw field is never what gets read.
     *  <p>
     *  The Skyblock gate moved INTO this method on 2026-09-30, when the automation stopped hanging off
     *  {@link #isEnabled()}: it used to inherit that gate for free because every autonomous code path went
     *  through {@code isEnabled()} first, and an independent toggle would otherwise have been able to run
     *  off Skyblock entirely. Same reason it is gated here and not at each call site. */
    public boolean isAutonomousMode() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autonomousMode
                && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setAutonomousMode(boolean autonomousMode) {
        this.autonomousMode = autonomousMode;
    }

    public int getFirstClickDelayMs() {
        return firstClickDelayMs;
    }

    public void setFirstClickDelayMs(int firstClickDelayMs) {
        this.firstClickDelayMs = Math.max(0, Math.min(MAX_FIRST_CLICK_DELAY_MS, firstClickDelayMs));
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
}

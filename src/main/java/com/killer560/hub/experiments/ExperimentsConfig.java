package com.killer560.hub.experiments;

import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Experimentation Table solver settings. */
public final class ExperimentsConfig {

    /** Bumped when a saved file needs rewriting under new meaning. 1 = the 2026-09-30 split of the solver
     *  from the automation (see {@link #enabled} and {@link #isAutonomousMode()}). Written by every
     *  {@link #save()}; a file without it is pre-split and gets the one-time fix-up in {@link #load()}.
     *  No setting's KEY changed in that split - only which of two now-independent toggles owns it. */
    private static final int CURRENT_CONFIG_VERSION = 2;

    /** Floor of Random Delay (max): 0 turns the jitter off. */
    public static final int MIN_DELAY_MS = 0;
    public static final int MAX_DELAY_MS = 1000;
    /** Floor of Click Delay and First Click Delay, enforced by the setters, the loader and the sliders alike
     *  (2026-10-15). Until then both went down to 0, which clicks every client tick (20 a second); nothing in the
     *  click path itself stops a config from asking for that. 150 ms is three ticks, and the testkit's own fast
     *  Auto E-Table runs have always used it. */
    public static final int MIN_CLICK_DELAY_MS = 150;

    // killer560's own settings (2026-10-15: "make sure these are the default settings for anyone when they use auto
    // etable"). A fresh install, a missing key and a profile reset all give these; see load() for older files. Except
    // (killer560, same day): the three Superpairs skips and the Titanic purchase stay OFF by default, and an older file
    // keeps whatever it says for them.
    public static final boolean DEFAULT_SUPERPAIRS_VALUABLE_ONLY = false;
    public static final boolean DEFAULT_SKIP_GRAND_EXP_BOTTLES = false;
    public static final boolean DEFAULT_SKIP_GUARDIAN_PETS = false;
    public static final boolean DEFAULT_SKIP_METAPHYSICAL_SERUMS = false;
    public static final int DEFAULT_DELAY_MS = 400;
    public static final int DEFAULT_FIRST_CLICK_DELAY_MS = 600;
    public static final int DEFAULT_RANDOM_DELAY_MAX_MS = 50;
    public static final double DEFAULT_TITANIC_MAX_PRICE = 0;
    public static final int DEFAULT_AUTO_RENEW_COUNT = 0;
    public static final int MIN_AUTO_RENEW_COUNT = 0;
    public static final int MAX_AUTO_RENEW_COUNT = 3;
    public static final double MIN_TITANIC_MAX_PRICE = 0;
    public static final double MAX_TITANIC_MAX_PRICE = 3_000_000;
    // killer560, 2026-10-19: Explore Before Claiming, its value threshold, and T7 Drops First. All off by default,
    // so a default install plays Superpairs exactly as before.
    public static final boolean DEFAULT_SUPERPAIRS_EXPLORE_FIRST = false;
    public static final boolean DEFAULT_SUPERPAIRS_T7_FIRST = false;
    public static final double MIN_SUPERPAIRS_SKIP_VALUE = 0;
    public static final double MAX_SUPERPAIRS_SKIP_VALUE = 3_000_000;
    public static final double SUPERPAIRS_SKIP_VALUE_STEP = 100_000;
    public static final double DEFAULT_SUPERPAIRS_SKIP_VALUE = 1_000_000;
    /** First Click Delay had no ceiling while it was a typed box; the slider that replaced it (2026-10-04)
     *  needs one. 3s is three times the default, and a saved value above it loads clamped. */
    public static final int MAX_FIRST_CLICK_DELAY_MS = 3000;
    /** Every delay slider in the Auto E-Table section moves in steps of this many ms. */
    public static final int DELAY_STEP_MS = 50;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-experiments.json");

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
    private boolean superpairsValuableOnly = DEFAULT_SUPERPAIRS_VALUABLE_ONLY;
    /** Never pair a Grand Experience Bottle (Titanics still paired) - killer560, 2026-10-05. */
    private boolean skipGrandExpBottles = DEFAULT_SKIP_GRAND_EXP_BOTTLES;
    /** Never pair a Guardian pet, any rarity - killer560, 2026-10-05. */
    private boolean skipGuardianPets = DEFAULT_SKIP_GUARDIAN_PETS;
    /** Never pair a Metaphysical Serum - killer560, 2026-10-18 ("dodge just like it does for the other dodge settings"). */
    private boolean skipMetaphysicalSerums = DEFAULT_SKIP_METAPHYSICAL_SERUMS;
    /** Superpairs leaves every reward worth no more than {@link #superpairsSkipValueCoins} until the board is known
     *  (the same point the skipped kinds wait for), then goes back for them - killer560, 2026-10-19. */
    private boolean superpairsExploreFirst = DEFAULT_SUPERPAIRS_EXPLORE_FIRST;
    /** With {@link #superpairsExploreFirst}: a reward worth more than this is claimed at once. 0-3,000,000 in steps
     *  of 100,000. */
    private double superpairsSkipValueCoins = DEFAULT_SUPERPAIRS_SKIP_VALUE;
    /** A T7 book (an enchant at its true max of VII, Enchant Colours' Perfect tier) or the Nadeshiko Dye is claimed
     *  before anything else, and nothing else is claimed until it is - killer560, 2026-10-19. */
    private boolean superpairsT7First = DEFAULT_SUPERPAIRS_T7_FIRST;
    private int delayMs = DEFAULT_DELAY_MS;
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
    private int firstClickDelayMs = DEFAULT_FIRST_CLICK_DELAY_MS;
    private ExperimentStopStrategy stopStrategy = ExperimentStopStrategy.MAX_CLICKS;
    /** Only takes effect while autonomousMode is also on - blocks killer560's own mouse/keyboard input
     *  to container screens while autonomous mode is running, so an accidental click/keypress can't
     *  interfere with it. Never affects the mod's own synthetic clicks, which go straight through
     *  MultiPlayerGameMode.handleContainerInput(...) rather than the screen's input handlers. */
    private boolean blockInputEnabled = false;
    /** How many "Renew Experiments" charge-purchases to make per day, 0-3, matching Hypixel's own
     *  real "You've renewed N/3 charges today!" cap - 0 (default) means never auto-buy. */
    private int autoRenewCount = DEFAULT_AUTO_RENEW_COUNT;
    /** Max Bazaar buy price (coins) autonomous mode will pay to instantly buy a Titanic Experience
     *  Bottle when the table's own "Missing experience?" prompt shows up - 0 means never auto-buy
     *  (the default; killer560, 2026-10-15: off for everyone). No practical minimum beyond 0; capped at
     *  3,000,000 per killer560's own limit. */
    private double titanicMaxPriceCoins = DEFAULT_TITANIC_MAX_PRICE;
    /** Random extra delay (0-1000ms) added on top of whatever fixed delay already decided it was
     *  time to click - applies to every click the mod performs (solver clicks, menu navigation,
     *  claiming rewards, buying charges/bottles), per killer560's explicit "added onto any click it
     *  performs." 0 disables it; 50 ms by default since 2026-10-15. It only ever ADDS time: the gap
     *  between two clicks is at least Click Delay whatever the jitter draws (see ExperimentsFeature). */
    private int randomDelayMaxMs = DEFAULT_RANDOM_DELAY_MAX_MS;
    /** Autonomous mode only: before starting/resuming a run, back out to {@code /pets} and equip the
     *  best owned Guardian (highest rarity, then highest level), then reopen the table - once per
     *  run. ON by default since 2026-10-04 (killer560: "For auto etable have auto swap to guardian pet on by
     *  default"); a saved file keeps whatever it already says. */
    private boolean autoSwapGuardianPet = true;
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
     *  observational logging of claimed rewards/XP/Bits, independent of the solver toggle. ON by
     *  default since 2026-10-04 (killer560: "Make all profit trackers on by default"). */
    private boolean profitTrackerEnabled = true;
    /** Superpairs' flat 1000ms confirm-timeout is extended by however long the server stalls while a
     *  click waits (see {@code ExperimentSolver#superpairsConfirmTimeoutMs} and {@link com.killer560.hub.util.ServerLag}).
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

    /** Loads, then tells FeatureBits a gated setting may have changed (profile apply reloads every config). */
    public static void load() {
        load0();
        com.killer560.hub.util.FeatureBits.changed();
    }

    /** The stored switch alone (no Skyblock Only gate): a FeatureBits gate. */
    public boolean isProfitTrackerEnabledRaw() {
        return profitTrackerEnabled;
    }

    private static void load0() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new ExperimentsConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            int version = ConfigJson.getInt(obj, "configVersion", 0);
            // Each key is read on its own and falls back to its default when it is missing or not a value of the
            // right kind. Until 2026-10-15 one bad value (a hand-edited "delayMs": "fast") threw out of the whole
            // read and every setting in the file was lost to the defaults.
            ExperimentsConfig cfg = new ExperimentsConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", true);
            cfg.superpairsEnabled = ConfigJson.getBool(obj, "superpairsEnabled", true);
            cfg.superpairsValuableOnly = ConfigJson.getBool(obj, "superpairsValuableOnly", DEFAULT_SUPERPAIRS_VALUABLE_ONLY);
            cfg.skipGrandExpBottles = ConfigJson.getBool(obj, "skipGrandExpBottles", DEFAULT_SKIP_GRAND_EXP_BOTTLES);
            cfg.skipGuardianPets = ConfigJson.getBool(obj, "skipGuardianPets", DEFAULT_SKIP_GUARDIAN_PETS);
            cfg.skipMetaphysicalSerums = ConfigJson.getBool(obj, "skipMetaphysicalSerums", DEFAULT_SKIP_METAPHYSICAL_SERUMS);
            cfg.superpairsExploreFirst = ConfigJson.getBool(obj, "superpairsExploreFirst", DEFAULT_SUPERPAIRS_EXPLORE_FIRST);
            cfg.superpairsSkipValueCoins = snapSkipValue(
                    ConfigJson.getDouble(obj, "superpairsSkipValueCoins", DEFAULT_SUPERPAIRS_SKIP_VALUE));
            cfg.superpairsT7First = ConfigJson.getBool(obj, "superpairsT7First", DEFAULT_SUPERPAIRS_T7_FIRST);
            cfg.delayMs = clampInt(obj, "delayMs", DEFAULT_DELAY_MS, MIN_CLICK_DELAY_MS, MAX_DELAY_MS);
            cfg.autonomousMode = ConfigJson.getBool(obj, "autonomousMode", false);
            cfg.firstClickDelayMs = clampInt(obj, "firstClickDelayMs", DEFAULT_FIRST_CLICK_DELAY_MS,
                    MIN_CLICK_DELAY_MS, MAX_FIRST_CLICK_DELAY_MS);
            cfg.stopStrategy = ConfigJson.getEnum(obj, "stopStrategy", ExperimentStopStrategy.class, ExperimentStopStrategy.MAX_CLICKS);
            cfg.blockInputEnabled = ConfigJson.getBool(obj, "blockInputEnabled", false);
            cfg.autoRenewCount = clampInt(obj, "autoRenewCount", DEFAULT_AUTO_RENEW_COUNT, MIN_AUTO_RENEW_COUNT, MAX_AUTO_RENEW_COUNT);
            double titanic = ConfigJson.getDouble(obj, "titanicMaxPriceCoins", DEFAULT_TITANIC_MAX_PRICE);
            cfg.titanicMaxPriceCoins = Math.max(MIN_TITANIC_MAX_PRICE, Math.min(MAX_TITANIC_MAX_PRICE, titanic));
            cfg.randomDelayMaxMs = clampInt(obj, "randomDelayMaxMs", DEFAULT_RANDOM_DELAY_MAX_MS, MIN_DELAY_MS, MAX_DELAY_MS);
            cfg.autoSwapGuardianPet = ConfigJson.getBool(obj, "autoSwapGuardianPet", true);
            cfg.clickProtectionEnabled = ConfigJson.getBool(obj, "clickProtectionEnabled", true);
            cfg.notifyMaxClicksReached = ConfigJson.getBool(obj, "notifyMaxClicksReached", true);
            cfg.profitTrackerEnabled = ConfigJson.getBool(obj, "profitTrackerEnabled", true);
            cfg.superpairsAdaptiveTimeout = ConfigJson.getBool(obj, "superpairsAdaptiveTimeout", false);
            // One-time fix-up for a file written before the 2026-09-30 solver/automation split. Back then
            // "enabled: false" meant the WHOLE Experimentation Table feature was off and "autonomousMode"
            // was a dead leftover underneath it; now the two are independent, so that file would otherwise
            // come back with the automation newly live. Keyed off the version so it can only ever happen
            // once - applying it on every load would permanently couple the two toggles back together and
            // silently undo "solver off, automation on".
            if (version < 1 && !cfg.enabled) {
                cfg.autonomousMode = false;
            }
            // Version 2 (2026-10-15): killer560's delays became the defaults. A file written before then gets the
            // new default for every delay still at the OLD default (it was never chosen), and keeps every one its
            // owner changed. The skips and the Titanic price are not migrated: their defaults did not change. The
            // delay floor needs no step here: clampInt already raised anything below MIN_CLICK_DELAY_MS.
            if (version < 2) {
                if (clampInt(obj, "delayMs", OLD_DEFAULT_DELAY_MS, 0, MAX_DELAY_MS) == OLD_DEFAULT_DELAY_MS) {
                    cfg.delayMs = DEFAULT_DELAY_MS;
                }
                if (clampInt(obj, "firstClickDelayMs", OLD_DEFAULT_FIRST_CLICK_DELAY_MS, 0, MAX_FIRST_CLICK_DELAY_MS)
                        == OLD_DEFAULT_FIRST_CLICK_DELAY_MS) {
                    cfg.firstClickDelayMs = DEFAULT_FIRST_CLICK_DELAY_MS;
                }
                if (clampInt(obj, "randomDelayMaxMs", 0, MIN_DELAY_MS, MAX_DELAY_MS) == 0) {
                    cfg.randomDelayMaxMs = DEFAULT_RANDOM_DELAY_MAX_MS;
                }
            }
            instance = cfg;
        } catch (Exception e) {
            instance = new ExperimentsConfig();
        }
    }

    /** The defaults before 2026-10-15, for {@link #load()}'s version-2 step only. */
    private static final int OLD_DEFAULT_DELAY_MS = 200;
    private static final int OLD_DEFAULT_FIRST_CLICK_DELAY_MS = 1000;

    /** An int setting clamped to {@code [min, max]}; {@code def} when missing, not a number, or NaN. Read as a
     *  double so an out-of-range number clamps instead of overflowing an int. */
    private static int clampInt(JsonObject obj, String key, int def, int min, int max) {
        double v = ConfigJson.getDouble(obj, key, def);
        return (int) Math.max(min, Math.min(max, Math.round(v)));
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("configVersion", CURRENT_CONFIG_VERSION);
            obj.addProperty("enabled", enabled);
            obj.addProperty("superpairsEnabled", superpairsEnabled);
            obj.addProperty("superpairsValuableOnly", superpairsValuableOnly);
            obj.addProperty("skipGrandExpBottles", skipGrandExpBottles);
            obj.addProperty("skipGuardianPets", skipGuardianPets);
            obj.addProperty("skipMetaphysicalSerums", skipMetaphysicalSerums);
            obj.addProperty("superpairsExploreFirst", superpairsExploreFirst);
            obj.addProperty("superpairsSkipValueCoins", superpairsSkipValueCoins);
            obj.addProperty("superpairsT7First", superpairsT7First);
            obj.addProperty("delayMs", delayMs);
            obj.addProperty("autonomousMode", autonomousMode);
            obj.addProperty("firstClickDelayMs", firstClickDelayMs);
            obj.addProperty("stopStrategy", stopStrategy.name());
            obj.addProperty("blockInputEnabled", blockInputEnabled);
            obj.addProperty("autoRenewCount", autoRenewCount);
            obj.addProperty("titanicMaxPriceCoins", titanicMaxPriceCoins);
            obj.addProperty("randomDelayMaxMs", randomDelayMaxMs);
            obj.addProperty("autoSwapGuardianPet", autoSwapGuardianPet);
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

    public boolean isSkipGrandExpBottles() {
        return skipGrandExpBottles;
    }

    public void setSkipGrandExpBottles(boolean v) {
        this.skipGrandExpBottles = v;
    }

    public boolean isSkipGuardianPets() {
        return skipGuardianPets;
    }

    public void setSkipGuardianPets(boolean v) {
        this.skipGuardianPets = v;
    }

    public boolean isSkipMetaphysicalSerums() {
        return skipMetaphysicalSerums;
    }

    public void setSkipMetaphysicalSerums(boolean v) {
        this.skipMetaphysicalSerums = v;
    }

    public boolean isSuperpairsExploreFirst() {
        return superpairsExploreFirst;
    }

    public void setSuperpairsExploreFirst(boolean v) {
        this.superpairsExploreFirst = v;
    }

    public double getSuperpairsSkipValueCoins() {
        return superpairsSkipValueCoins;
    }

    /** Clamped to 0..3,000,000 and snapped to the nearest 100,000. */
    public void setSuperpairsSkipValueCoins(double v) {
        this.superpairsSkipValueCoins = snapSkipValue(v);
    }

    /** {@code v} clamped to 0..3,000,000 and snapped to a multiple of 100,000; NaN gives the default. */
    public static double snapSkipValue(double v) {
        if (Double.isNaN(v)) {
            return DEFAULT_SUPERPAIRS_SKIP_VALUE;
        }
        double snapped = Math.round(v / SUPERPAIRS_SKIP_VALUE_STEP) * SUPERPAIRS_SKIP_VALUE_STEP;
        return Math.max(MIN_SUPERPAIRS_SKIP_VALUE, Math.min(MAX_SUPERPAIRS_SKIP_VALUE, snapped));
    }

    public boolean isSuperpairsT7First() {
        return superpairsT7First;
    }

    public void setSuperpairsT7First(boolean v) {
        this.superpairsT7First = v;
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
        this.delayMs = Math.max(MIN_CLICK_DELAY_MS, Math.min(MAX_DELAY_MS, delayMs));
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
        this.firstClickDelayMs = Math.max(MIN_CLICK_DELAY_MS, Math.min(MAX_FIRST_CLICK_DELAY_MS, firstClickDelayMs));
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
        this.profitTrackerEnabled = profitTrackerEnabled; com.killer560.hub.util.FeatureBits.changed();
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

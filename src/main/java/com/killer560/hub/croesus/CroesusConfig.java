package com.killer560.hub.croesus;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted settings for Chest Profit, the Croesus Profit Logger and Auto Croesus - see
 *  {@link ChestProfitFeature}, {@link CroesusProfitLog}, {@link AutoCroesusFeature}. Everything ships OFF. */
public final class CroesusConfig {

    /** What decides whether Auto Croesus spends a real Dungeon Chest Key on a second chest - killer560
     *  (2026-09-27): "have an option for it to use based off of key insta buy price, buy offer price, or
     *  by set profit." {@link #SET_PROFIT} is the original, sole behavior ({@link #autoKeyMinProfitK});
     *  the other two swap that fixed number for the key's own live Bazaar price (see
     *  {@link AutoCroesusFeature#keyModeThreshold}) - falling back to {@link #autoKeyMinProfitK} if that
     *  price hasn't loaded yet, same as every other "prices not ready" case in Croesus. */
    public enum ChestKeyMode {
        SET_PROFIT("Set Profit"), KEY_INSTA_BUY("Key Insta-Buy"), KEY_BUY_OFFER("Key Buy-Offer");

        public final String label;

        ChestKeyMode(String label) {
            this.label = label;
        }

        public ChestKeyMode next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public ChestKeyMode previous() {
            return values()[(values().length + ordinal() - 1) % values().length];
        }
    }

    /**
     * What a Bazaar item in a dungeon chest is worth (killer560, 2026-10-11: "croesus should have a profit option by insta
     * sell or sell offer"). Hypixel's quick_status names them backward (docs/LESSONS.md): {@code sellPrice} is what an
     * instant sell gets, {@code buyPrice} is the cheapest sell offer, i.e. what a sell order of yours has to undercut.
     * Auction-house items stay on lowest BIN in both modes.
     */
    public enum PriceMode {
        INSTA_SELL("Insta-Sell"), SELL_OFFER("Sell Offer");

        public final String label;

        PriceMode(String label) {
            this.label = label;
        }

        public boolean sellOffer() {
            return this == SELL_OFFER;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-croesus.json");

    public static final int MIN_DELAY_BOUND_MS = 100;
    public static final int MAX_DELAY_BOUND_MS = 2000;
    /** Min-profit slider range for Auto Croesus, in thousands of coins (0 - 20M). */
    public static final int MAX_MIN_PROFIT_K = 20_000;
    /** Chest Key Min Profit runs 0 to 2,000,000 in 20k steps (killer560, 2026-10-13). */
    public static final int MAX_KEY_MIN_PROFIT_K = 2_000;
    public static final int KEY_MIN_PROFIT_STEP_K = 20;

    private static CroesusConfig instance;

    // Both profit trackers ON by default since 2026-10-04 (killer560: "Make all profit trackers on by default").
    private boolean chestProfitEnabled = true;
    private boolean includeEssence = true;
    /** In a run's chest view: the most profitable chest at its normal cost, green - the free claim (killer560,
     *  2026-10-13). Was "Highlight Best" (key highlightBest), carried over on load. */
    private boolean highlightFreeChest = true;
    /** In a run's chest view: the one chest worth coming back for with a Dungeon Chest Key, orange - the same rule that
     *  makes a run "needs a key" in the run list. Replaces "Second Best With Key" (highlightSecondWithKey, default off),
     *  which is not carried over: he asked for this one on (2026-10-13). */
    private boolean highlightKeyChest = true;
    public static final int DEFAULT_CHEST_FREE_COLOR = 0x3FD94A;
    public static final int DEFAULT_CHEST_KEY_COLOR = 0xFF9A1F;
    private int chestFreeColor = DEFAULT_CHEST_FREE_COLOR;
    private int chestKeyColor = DEFAULT_CHEST_KEY_COLOR;
    /** Color the run heads in the Croesus menu by whether anything has been claimed from them yet. */
    private boolean highlightRuns = false;
    /** Highlight Runs' colors (killer560, 2026-10-12: "the highlight runs portion of croesus just kind of looks like
     *  poop"): a light tint and a thin inset border on a run nothing was claimed from, a dark tint on one already
     *  claimed. RGB; the alphas are fixed in ChestProfitFeature. */
    public static final int DEFAULT_RUN_UNCLAIMED_COLOR = 0xFF8C1A;
    public static final int DEFAULT_RUN_CLAIMED_COLOR = 0x202020;
    /** killer560, 2026-10-12: "if i need to go back and use a chest key it should also have a different highlight" -
     *  a claimed run whose second chest is worth a key (Auto Croesus's key rules); never hidden. Magenta since
     *  2026-10-12 ("these colors are hard to actually tell if they are different": the old gold read as the unclaimed
     *  orange over Hypixel's green and red heads), with a thicker border and a key mark; a solid
     *  purple-magenta fill since 2026-10-13 (0xCC0096 sat 28.6 deltaE from the key-used gray). */
    public static final int DEFAULT_RUN_KEY_COLOR = 0x9B00B2;
    /** Earlier needs-a-key defaults (gold, then 0xCC0096): a config still on one moves to the new one. */
    static final int[] LEGACY_RUN_KEY_COLORS = {0xFFD000, 0xCC0096};
    /** A claimed run whose chests were not looked at yet: a solid 2 px outline in a muted teal, no fill, no mark (a
     *  full outline since 2026-10-13, his ask; muted so the filled action states still stand out) - nothing to do until
     *  he looks. */
    public static final int DEFAULT_RUN_UNKNOWN_COLOR = 0x728F8D;
    /** Earlier not-looked-at default (bright cyan, which as a full outline outshone the action states - 2026-10-13):
     *  a config still on it moves to the muted one. */
    static final int[] LEGACY_RUN_UNKNOWN_COLORS = {0x7DF9FF};
    private int runUnknownColor = DEFAULT_RUN_UNKNOWN_COLOR;
    /** A one-line key to the run colors in the Croesus info box. */
    private boolean showRunLegend = true;
    /** "If no chest key then it should be different": claimed, no key used, nothing left worth a key. A muted gray-blue
     *  2 px outline (2026-10-13), so it reads apart from the dark key-used tint and, for a protanope, from the deep magenta
     *  needs-a-key (2026-10-12; 0x2F6FD0 was too close to it). */
    public static final int DEFAULT_RUN_OPENED_COLOR = 0x798FAD;
    /** Earlier opened defaults (0x3A4A66, 0x2F6FD0, then 0x578DD9 until the full outlines of 2026-10-13 needed a muted
     *  blue): a config still on one moves to the new one. */
    static final int[] LEGACY_RUN_OPENED_COLORS = {0x3A4A66, 0x2F6FD0, 0x578DD9};
    private int runKeyColor = DEFAULT_RUN_KEY_COLOR;
    private int runOpenedColor = DEFAULT_RUN_OPENED_COLOR;
    private int runUnclaimedColor = DEFAULT_RUN_UNCLAIMED_COLOR;
    private int runClaimedColor = DEFAULT_RUN_CLAIMED_COLOR;
    /** killer560, 2026-10-12: "make an option to hide opened chests" - runs already claimed from draw as empty
     *  slots, show no tooltip and take no click (Auto Croesus still reads and clicks the real slots). */
    private boolean hideOpenedRuns = false;
    /** See {@link PriceMode}. Before it existed Croesus followed the RNG Meter's "Use Instant-Buy Price", so a config
     *  without the key starts where that setting left it (on = buyPrice = Sell Offer). */
    private PriceMode priceMode = PriceMode.INSTA_SELL;
    private boolean loggerEnabled = true;
    private boolean loggerChatSummary = true;
    private boolean autoCroesusEnabled = false;
    private int autoMinProfitK = 0;
    private int autoMinDelayMs = 350;
    private int autoMaxDelayMs = 700;
    /** Spend a Dungeon Chest Key on a further chest in the same run when it clears {@link #autoKeyMinProfitK}. */
    private boolean autoUseChestKeys = false;
    private int autoKeyMinProfitK = 1_000;
    /** Key Buy-Offer by default since 2026-10-13 (killer560: "Use Key Based On defaults to Key Buy Offer"). */
    private ChestKeyMode chestKeyMode = ChestKeyMode.KEY_BUY_OFFER;
    /** One-time: a file saved before the default changed always carries the old default (Set Profit), which is moved to
     *  Key Buy-Offer once; a Set Profit picked after that stays. */
    private boolean chestKeyModeDefaulted = true;
    /** Spend a Kismet Feather on a Bedrock chest whose profit is below {@link #autoRerollBelowK}. */
    private boolean autoUseKismets = false;
    private int autoRerollBelowK = 1_000;
    /** Reroll Protection (NoammAddons ChestProfit.kt "Reroll Value"): his click on a reward chest's Kismet "Reroll Chest"
     *  button is dropped while the chest's profit is above {@link #rerollProtectAboveK}. Ctrl lets it through. */
    private boolean rerollProtection = false;
    /** NoammAddons' default, 5M. */
    private int rerollProtectAboveK = 5_000;
    /** The Kismet Feathers he carries, drawn as the reroll button's stack number (SkyHanni "Kismet Amount"). */
    private boolean kismetCount = false;
    /** Croesus Chests HUD: Hypixel's tab line " Unclaimed chests: N" as "N/60", and his Kismet Feathers, outside the menu. */
    private boolean chestCountHud = false;
    /** A chat warning once the unclaimed count reaches this (Odin Croesus.kt "Chest Warning Threshold", 55). */
    private int chestWarnAt = 55;
    public static final int MAX_CHEST_WARN_AT = 60;

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
            instance.priceMode = legacyPriceMode();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            CroesusConfig cfg = new CroesusConfig();
            cfg.chestProfitEnabled = ConfigJson.getBool(obj, "chestProfitEnabled", cfg.chestProfitEnabled);
            cfg.includeEssence = ConfigJson.getBool(obj, "includeEssence", cfg.includeEssence);
            cfg.highlightFreeChest = ConfigJson.getBool(obj, "highlightFreeChest",
                    ConfigJson.getBool(obj, "highlightBest", cfg.highlightFreeChest));
            cfg.highlightKeyChest = ConfigJson.getBool(obj, "highlightKeyChest", cfg.highlightKeyChest);
            cfg.chestFreeColor = ConfigJson.getInt(obj, "chestFreeColor", cfg.chestFreeColor) & 0xFFFFFF;
            cfg.chestKeyColor = ConfigJson.getInt(obj, "chestKeyColor", cfg.chestKeyColor) & 0xFFFFFF;
            cfg.highlightRuns = ConfigJson.getBool(obj, "highlightRuns", cfg.highlightRuns);
            cfg.runUnclaimedColor = ConfigJson.getInt(obj, "runUnclaimedColor", cfg.runUnclaimedColor) & 0xFFFFFF;
            cfg.runClaimedColor = ConfigJson.getInt(obj, "runClaimedColor", cfg.runClaimedColor) & 0xFFFFFF;
            cfg.runKeyColor = ConfigJson.getInt(obj, "runKeyColor", cfg.runKeyColor) & 0xFFFFFF;
            if (java.util.Arrays.stream(LEGACY_RUN_KEY_COLORS).anyMatch(c -> c == cfg.runKeyColor)) {
                cfg.runKeyColor = DEFAULT_RUN_KEY_COLOR; // still on the old default: the new palette; a picked color stays
            }
            cfg.runUnknownColor = ConfigJson.getInt(obj, "runUnknownColor", cfg.runUnknownColor) & 0xFFFFFF;
            for (int legacy : LEGACY_RUN_UNKNOWN_COLORS) {
                if (cfg.runUnknownColor == legacy) {
                    cfg.runUnknownColor = DEFAULT_RUN_UNKNOWN_COLOR;
                }
            }
            cfg.showRunLegend = ConfigJson.getBool(obj, "showRunLegend", cfg.showRunLegend);
            cfg.runOpenedColor = ConfigJson.getInt(obj, "runOpenedColor", cfg.runOpenedColor) & 0xFFFFFF;
            for (int legacy : LEGACY_RUN_OPENED_COLORS) {
                if (cfg.runOpenedColor == legacy) {
                    cfg.runOpenedColor = DEFAULT_RUN_OPENED_COLOR;
                }
            }
            cfg.hideOpenedRuns = ConfigJson.getBool(obj, "hideOpenedRuns", cfg.hideOpenedRuns);
            cfg.priceMode = obj.has("priceMode")
                    ? ConfigJson.getEnum(obj, "priceMode", PriceMode.class, PriceMode.INSTA_SELL) : legacyPriceMode();
            cfg.loggerEnabled = ConfigJson.getBool(obj, "loggerEnabled", cfg.loggerEnabled);
            cfg.loggerChatSummary = ConfigJson.getBool(obj, "loggerChatSummary", cfg.loggerChatSummary);
            cfg.autoCroesusEnabled = ConfigJson.getBool(obj, "autoCroesusEnabled", cfg.autoCroesusEnabled);
            cfg.autoMinProfitK = clamp(ConfigJson.getInt(obj, "autoMinProfitK", cfg.autoMinProfitK), 0, MAX_MIN_PROFIT_K);
            cfg.autoMinDelayMs = clamp(ConfigJson.getInt(obj, "autoMinDelayMs", cfg.autoMinDelayMs), MIN_DELAY_BOUND_MS, MAX_DELAY_BOUND_MS);
            cfg.autoMaxDelayMs = clamp(ConfigJson.getInt(obj, "autoMaxDelayMs", cfg.autoMaxDelayMs), MIN_DELAY_BOUND_MS, MAX_DELAY_BOUND_MS);
            cfg.autoUseChestKeys = ConfigJson.getBool(obj, "autoUseChestKeys", cfg.autoUseChestKeys);
            cfg.autoKeyMinProfitK = keyStep(ConfigJson.getInt(obj, "autoKeyMinProfitK", cfg.autoKeyMinProfitK));
            cfg.chestKeyMode = ConfigJson.getEnum(obj, "chestKeyMode", ChestKeyMode.class, cfg.chestKeyMode);
            // decided from the OLD file's keys: no marker = written before the default moved
            if (!obj.has("chestKeyModeDefaulted") && cfg.chestKeyMode == ChestKeyMode.SET_PROFIT) {
                cfg.chestKeyMode = ChestKeyMode.KEY_BUY_OFFER;
            }
            cfg.autoUseKismets = ConfigJson.getBool(obj, "autoUseKismets", cfg.autoUseKismets);
            cfg.autoRerollBelowK = clamp(ConfigJson.getInt(obj, "autoRerollBelowK", cfg.autoRerollBelowK), 0, MAX_MIN_PROFIT_K);
            cfg.rerollProtection = ConfigJson.getBool(obj, "rerollProtection", cfg.rerollProtection);
            cfg.rerollProtectAboveK = clamp(ConfigJson.getInt(obj, "rerollProtectAboveK", cfg.rerollProtectAboveK), 0, MAX_MIN_PROFIT_K);
            cfg.kismetCount = ConfigJson.getBool(obj, "kismetCount", cfg.kismetCount);
            cfg.chestCountHud = ConfigJson.getBool(obj, "chestCountHud", cfg.chestCountHud);
            cfg.chestWarnAt = clamp(ConfigJson.getInt(obj, "chestWarnAt", cfg.chestWarnAt), 1, MAX_CHEST_WARN_AT);
            // Same min <= max invariant the setters enforce (a hand-edit could otherwise load min > max).
            if (cfg.autoMaxDelayMs < cfg.autoMinDelayMs) {
                cfg.autoMaxDelayMs = cfg.autoMinDelayMs;
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
            obj.addProperty("highlightFreeChest", highlightFreeChest);
            obj.addProperty("highlightKeyChest", highlightKeyChest);
            obj.addProperty("chestFreeColor", chestFreeColor);
            obj.addProperty("chestKeyColor", chestKeyColor);
            obj.addProperty("highlightRuns", highlightRuns);
            obj.addProperty("runUnclaimedColor", runUnclaimedColor);
            obj.addProperty("runClaimedColor", runClaimedColor);
            obj.addProperty("runKeyColor", runKeyColor);
            obj.addProperty("runUnknownColor", runUnknownColor);
            obj.addProperty("showRunLegend", showRunLegend);
            obj.addProperty("runOpenedColor", runOpenedColor);
            obj.addProperty("hideOpenedRuns", hideOpenedRuns);
            obj.addProperty("priceMode", priceMode.name());
            obj.addProperty("loggerEnabled", loggerEnabled);
            obj.addProperty("loggerChatSummary", loggerChatSummary);
            obj.addProperty("autoCroesusEnabled", autoCroesusEnabled);
            obj.addProperty("autoMinProfitK", autoMinProfitK);
            obj.addProperty("autoMinDelayMs", autoMinDelayMs);
            obj.addProperty("autoMaxDelayMs", autoMaxDelayMs);
            obj.addProperty("autoUseChestKeys", autoUseChestKeys);
            obj.addProperty("autoKeyMinProfitK", autoKeyMinProfitK);
            obj.addProperty("chestKeyMode", chestKeyMode.name());
            obj.addProperty("chestKeyModeDefaulted", chestKeyModeDefaulted);
            obj.addProperty("autoUseKismets", autoUseKismets);
            obj.addProperty("autoRerollBelowK", autoRerollBelowK);
            obj.addProperty("rerollProtection", rerollProtection);
            obj.addProperty("rerollProtectAboveK", rerollProtectAboveK);
            obj.addProperty("kismetCount", kismetCount);
            obj.addProperty("chestCountHud", chestCountHud);
            obj.addProperty("chestWarnAt", chestWarnAt);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** A Chest Key Min Profit in k, clamped to 0..{@link #MAX_KEY_MIN_PROFIT_K} and snapped to the 20k step. */
    public static int keyStep(int k) {
        int c = clamp(k, 0, MAX_KEY_MIN_PROFIT_K);
        return Math.min(MAX_KEY_MIN_PROFIT_K, Math.round(c / (float) KEY_MIN_PROFIT_STEP_K) * KEY_MIN_PROFIT_STEP_K);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public boolean isChestProfitEnabled() {
        return chestProfitEnabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setChestProfitEnabled(boolean v) {
        this.chestProfitEnabled = v;
    }

    private static PriceMode legacyPriceMode() {
        try {
            return com.killer560.hub.rngmeter.RngMeterConfig.getInstance().isUseInstantBuyPrice()
                    ? PriceMode.SELL_OFFER : PriceMode.INSTA_SELL;
        } catch (Exception e) {
            return PriceMode.INSTA_SELL;
        }
    }

    public PriceMode getPriceMode() {
        return priceMode;
    }

    public void setPriceMode(PriceMode mode) {
        priceMode = mode == null ? PriceMode.INSTA_SELL : mode;
    }

    public boolean isIncludeEssence() {
        return includeEssence;
    }

    public void setIncludeEssence(boolean v) {
        this.includeEssence = v;
    }

    public boolean isHighlightFreeChest() {
        return highlightFreeChest;
    }

    public void setHighlightFreeChest(boolean v) {
        this.highlightFreeChest = v;
    }

    public boolean isHighlightKeyChest() {
        return highlightKeyChest;
    }

    public void setHighlightKeyChest(boolean v) {
        this.highlightKeyChest = v;
    }

    public int getChestFreeColor() {
        return chestFreeColor;
    }

    public void setChestFreeColor(int rgb) {
        this.chestFreeColor = rgb & 0xFFFFFF;
    }

    public int getChestKeyColor() {
        return chestKeyColor;
    }

    public void setChestKeyColor(int rgb) {
        this.chestKeyColor = rgb & 0xFFFFFF;
    }

    public boolean isHighlightRuns() {
        return highlightRuns;
    }

    public void setHighlightRuns(boolean v) {
        this.highlightRuns = v;
    }

    public int getRunUnclaimedColor() {
        return runUnclaimedColor;
    }

    public void setRunUnclaimedColor(int rgb) {
        this.runUnclaimedColor = rgb & 0xFFFFFF;
    }

    public int getRunClaimedColor() {
        return runClaimedColor;
    }

    public void setRunClaimedColor(int rgb) {
        this.runClaimedColor = rgb & 0xFFFFFF;
    }

    public int getRunKeyColor() {
        return runKeyColor;
    }

    public void setRunKeyColor(int rgb) {
        this.runKeyColor = rgb & 0xFFFFFF;
    }

    public int getRunUnknownColor() {
        return runUnknownColor;
    }

    public void setRunUnknownColor(int rgb) {
        this.runUnknownColor = rgb & 0xFFFFFF;
    }

    public boolean isShowRunLegend() {
        return showRunLegend;
    }

    public void setShowRunLegend(boolean v) {
        this.showRunLegend = v;
    }

    public int getRunOpenedColor() {
        return runOpenedColor;
    }

    public void setRunOpenedColor(int rgb) {
        this.runOpenedColor = rgb & 0xFFFFFF;
    }

    public boolean isHideOpenedRuns() {
        return hideOpenedRuns;
    }

    public void setHideOpenedRuns(boolean v) {
        this.hideOpenedRuns = v;
    }


    public boolean isLoggerEnabled() {
        return loggerEnabled && com.killer560.hub.util.SkyblockGate.allows();
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
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoCroesusEnabled && com.killer560.hub.util.SkyblockGate.allows();
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

    public boolean isAutoUseChestKeys() {
        return autoUseChestKeys;
    }

    public void setAutoUseChestKeys(boolean v) {
        this.autoUseChestKeys = v;
    }

    public int getAutoKeyMinProfitK() {
        return autoKeyMinProfitK;
    }

    public void setAutoKeyMinProfitK(int v) {
        this.autoKeyMinProfitK = keyStep(v);
    }

    public ChestKeyMode getChestKeyMode() {
        return chestKeyMode;
    }

    public void setChestKeyMode(ChestKeyMode v) {
        this.chestKeyMode = v == null ? ChestKeyMode.SET_PROFIT : v;
    }

    public boolean isAutoUseKismets() {
        return autoUseKismets;
    }

    public void setAutoUseKismets(boolean v) {
        this.autoUseKismets = v;
    }

    public int getAutoRerollBelowK() {
        return autoRerollBelowK;
    }

    public void setAutoRerollBelowK(int v) {
        this.autoRerollBelowK = clamp(v, 0, MAX_MIN_PROFIT_K);
    }

    public boolean isRerollProtection() {
        return rerollProtection && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isRerollProtectionRaw() {
        return rerollProtection;
    }

    public void setRerollProtection(boolean v) {
        this.rerollProtection = v;
    }

    public int getRerollProtectAboveK() {
        return rerollProtectAboveK;
    }

    public void setRerollProtectAboveK(int v) {
        this.rerollProtectAboveK = clamp(v, 0, MAX_MIN_PROFIT_K);
    }

    public boolean isKismetCount() {
        return kismetCount && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isKismetCountRaw() {
        return kismetCount;
    }

    public void setKismetCount(boolean v) {
        this.kismetCount = v;
    }

    public boolean isChestCountHud() {
        return chestCountHud && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isChestCountHudRaw() {
        return chestCountHud;
    }

    public void setChestCountHud(boolean v) {
        this.chestCountHud = v;
    }

    public int getChestWarnAt() {
        return chestWarnAt;
    }

    public void setChestWarnAt(int v) {
        this.chestWarnAt = clamp(v, 1, MAX_CHEST_WARN_AT);
    }
}

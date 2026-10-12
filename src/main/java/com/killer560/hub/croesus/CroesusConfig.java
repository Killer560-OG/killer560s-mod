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
     * sell or sell offer"). Hypixel's quick_status names them backwards (docs/LESSONS.md): {@code sellPrice} is what an
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

    private static CroesusConfig instance;

    // Both profit trackers ON by default since 2026-10-04 (killer560: "Make all profit trackers on by default").
    private boolean chestProfitEnabled = true;
    private boolean includeEssence = true;
    private boolean highlightBest = true;
    /** Colour the run heads in the Croesus menu by whether anything has been claimed from them yet. */
    private boolean highlightRuns = false;
    /** Highlight Runs' colours (killer560, 2026-10-12: "the highlight runs portion of croesus just kind of looks like
     *  poop"): a light tint and a thin inset border on a run nothing was claimed from, a dark tint on one already
     *  claimed. RGB; the alphas are fixed in ChestProfitFeature. */
    public static final int DEFAULT_RUN_UNCLAIMED_COLOR = 0xFF8C1A;
    public static final int DEFAULT_RUN_CLAIMED_COLOR = 0x202020;
    /** killer560, 2026-10-12: "if i need to go back and use a chest key it should also have a different highlight" -
     *  a claimed run whose second chest is worth a key (Auto Croesus's key rules); never hidden. Magenta since
     *  2026-10-12 ("these colors are hard to actually tell if they are different": the old gold read as the unclaimed
     *  orange over Hypixel's green and red heads), with a thicker border and a key mark. */
    public static final int DEFAULT_RUN_KEY_COLOR = 0xCC0096;
    /** The needs-a-key default before the redesign: a config still on it moves to the new one. */
    static final int LEGACY_RUN_KEY_COLOR = 0xFFD000;
    /** A claimed run whose chests were not looked at yet: cyan corner marks and a "?" mark, no fill. */
    public static final int DEFAULT_RUN_UNKNOWN_COLOR = 0x7DF9FF;
    private int runUnknownColor = DEFAULT_RUN_UNKNOWN_COLOR;
    /** A one-line key to the run colours in the Croesus info box. */
    private boolean showRunLegend = true;
    /** "If no chest key then it should be different": claimed, no key used, nothing left worth a key. A muted mid blue
     *  with a solid border, so it reads apart from the dark key-used tint and, for a protanope, from the deep magenta
     *  needs-a-key (2026-10-12; 0x2F6FD0 was too close to it). */
    public static final int DEFAULT_RUN_OPENED_COLOR = 0x578DD9;
    /** Earlier opened defaults (0x3A4A66, then 0x2F6FD0): a config still on one moves to the new one. */
    static final int[] LEGACY_RUN_OPENED_COLORS = {0x3A4A66, 0x2F6FD0};
    private int runKeyColor = DEFAULT_RUN_KEY_COLOR;
    private int runOpenedColor = DEFAULT_RUN_OPENED_COLOR;
    private int runUnclaimedColor = DEFAULT_RUN_UNCLAIMED_COLOR;
    private int runClaimedColor = DEFAULT_RUN_CLAIMED_COLOR;
    /** killer560, 2026-10-12: "make an option to hide opened chests" - runs already claimed from draw as empty
     *  slots, show no tooltip and take no click (Auto Croesus still reads and clicks the real slots). */
    private boolean hideOpenedRuns = false;
    /** Also highlight the second-best chest when it still profits after paying for a Dungeon Chest Key. */
    private boolean highlightSecondWithKey = false;
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
    private ChestKeyMode chestKeyMode = ChestKeyMode.SET_PROFIT;
    /** Spend a Kismet Feather on a Bedrock chest whose profit is below {@link #autoRerollBelowK}. */
    private boolean autoUseKismets = false;
    private int autoRerollBelowK = 1_000;

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
            cfg.highlightBest = ConfigJson.getBool(obj, "highlightBest", cfg.highlightBest);
            cfg.highlightRuns = ConfigJson.getBool(obj, "highlightRuns", cfg.highlightRuns);
            cfg.runUnclaimedColor = ConfigJson.getInt(obj, "runUnclaimedColor", cfg.runUnclaimedColor) & 0xFFFFFF;
            cfg.runClaimedColor = ConfigJson.getInt(obj, "runClaimedColor", cfg.runClaimedColor) & 0xFFFFFF;
            cfg.runKeyColor = ConfigJson.getInt(obj, "runKeyColor", cfg.runKeyColor) & 0xFFFFFF;
            if (cfg.runKeyColor == LEGACY_RUN_KEY_COLOR) {
                cfg.runKeyColor = DEFAULT_RUN_KEY_COLOR; // still on the old default: the new palette; a picked colour stays
            }
            cfg.runUnknownColor = ConfigJson.getInt(obj, "runUnknownColor", cfg.runUnknownColor) & 0xFFFFFF;
            cfg.showRunLegend = ConfigJson.getBool(obj, "showRunLegend", cfg.showRunLegend);
            cfg.runOpenedColor = ConfigJson.getInt(obj, "runOpenedColor", cfg.runOpenedColor) & 0xFFFFFF;
            for (int legacy : LEGACY_RUN_OPENED_COLORS) {
                if (cfg.runOpenedColor == legacy) {
                    cfg.runOpenedColor = DEFAULT_RUN_OPENED_COLOR;
                }
            }
            cfg.hideOpenedRuns = ConfigJson.getBool(obj, "hideOpenedRuns", cfg.hideOpenedRuns);
            cfg.highlightSecondWithKey = ConfigJson.getBool(obj, "highlightSecondWithKey", cfg.highlightSecondWithKey);
            cfg.priceMode = obj.has("priceMode")
                    ? ConfigJson.getEnum(obj, "priceMode", PriceMode.class, PriceMode.INSTA_SELL) : legacyPriceMode();
            cfg.loggerEnabled = ConfigJson.getBool(obj, "loggerEnabled", cfg.loggerEnabled);
            cfg.loggerChatSummary = ConfigJson.getBool(obj, "loggerChatSummary", cfg.loggerChatSummary);
            cfg.autoCroesusEnabled = ConfigJson.getBool(obj, "autoCroesusEnabled", cfg.autoCroesusEnabled);
            cfg.autoMinProfitK = clamp(ConfigJson.getInt(obj, "autoMinProfitK", cfg.autoMinProfitK), 0, MAX_MIN_PROFIT_K);
            cfg.autoMinDelayMs = clamp(ConfigJson.getInt(obj, "autoMinDelayMs", cfg.autoMinDelayMs), MIN_DELAY_BOUND_MS, MAX_DELAY_BOUND_MS);
            cfg.autoMaxDelayMs = clamp(ConfigJson.getInt(obj, "autoMaxDelayMs", cfg.autoMaxDelayMs), MIN_DELAY_BOUND_MS, MAX_DELAY_BOUND_MS);
            cfg.autoUseChestKeys = ConfigJson.getBool(obj, "autoUseChestKeys", cfg.autoUseChestKeys);
            cfg.autoKeyMinProfitK = clamp(ConfigJson.getInt(obj, "autoKeyMinProfitK", cfg.autoKeyMinProfitK), 0, MAX_MIN_PROFIT_K);
            cfg.chestKeyMode = ConfigJson.getEnum(obj, "chestKeyMode", ChestKeyMode.class, cfg.chestKeyMode);
            cfg.autoUseKismets = ConfigJson.getBool(obj, "autoUseKismets", cfg.autoUseKismets);
            cfg.autoRerollBelowK = clamp(ConfigJson.getInt(obj, "autoRerollBelowK", cfg.autoRerollBelowK), 0, MAX_MIN_PROFIT_K);
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
            obj.addProperty("highlightBest", highlightBest);
            obj.addProperty("highlightRuns", highlightRuns);
            obj.addProperty("runUnclaimedColor", runUnclaimedColor);
            obj.addProperty("runClaimedColor", runClaimedColor);
            obj.addProperty("runKeyColor", runKeyColor);
            obj.addProperty("runUnknownColor", runUnknownColor);
            obj.addProperty("showRunLegend", showRunLegend);
            obj.addProperty("runOpenedColor", runOpenedColor);
            obj.addProperty("hideOpenedRuns", hideOpenedRuns);
            obj.addProperty("highlightSecondWithKey", highlightSecondWithKey);
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
            obj.addProperty("autoUseKismets", autoUseKismets);
            obj.addProperty("autoRerollBelowK", autoRerollBelowK);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
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

    public boolean isHighlightBest() {
        return highlightBest;
    }

    public void setHighlightBest(boolean v) {
        this.highlightBest = v;
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

    public boolean isHighlightSecondWithKey() {
        return highlightSecondWithKey;
    }

    public void setHighlightSecondWithKey(boolean v) {
        this.highlightSecondWithKey = v;
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
        this.autoKeyMinProfitK = clamp(v, 0, MAX_MIN_PROFIT_K);
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
}

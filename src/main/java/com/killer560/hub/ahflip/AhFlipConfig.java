package com.killer560.hub.ahflip;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Settings of the AH Flipper and the AH Price Copier (killer560, 2026-11-01, to-do Doc "AH FLIPPER": "It should have two
 * options. Background running or main running ... I should set a profit percent a percent undercut a min sell price and
 * stuff like that ... The buy manually one shouldnt be a cheat. Also make an ah price copier").
 *
 * <p>Both ship OFF. The flipper's Main mode (the buying macro) is cheat-only: {@link #getRunMode()} reads Background in a
 * legit build whatever the file says, and {@link #isMacroActive()} is folded away there. Every field is loaded, saved and
 * has a getter and a setter.
 */
public final class AhFlipConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-ahflip.json");

    /** How the flipper runs. */
    public enum RunMode {
        /** Legit: watches new listings while you play and puts each flip in chat; clicking it opens the auction. */
        BACKGROUND("Background (chat)"),
        /** Cheat build: the macro buys each flip itself. */
        MAIN("Main (macro)");

        public final String display;

        RunMode(String display) {
            this.display = display;
        }
    }

    /** Stops the Min Profit % row steps through. */
    public static final int[] PROFIT_PERCENT_STEPS = {2, 3, 5, 7, 10, 15, 20, 25, 30, 40, 50, 75, 100};
    /** Undercut stops, in tenths of a percent (0.0% to 10%). */
    public static final int[] UNDERCUT_TENTHS_STEPS = {0, 1, 5, 10, 20, 30, 50, 75, 100};
    /** Coin stops of Min Sell Price, Min Profit and Max Buy Price (0 = off where it applies). */
    public static final long[] COIN_STEPS = {0L, 50_000L, 100_000L, 250_000L, 500_000L, 1_000_000L, 2_500_000L, 5_000_000L,
            10_000_000L, 25_000_000L, 50_000_000L, 100_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L};
    /** Min Listings stops: how many other BINs an item needs before its lowest is trusted as a sale price. */
    public static final int[] MIN_LISTINGS_STEPS = {1, 2, 3, 5, 10, 20};
    /** Max Buys Per Session stops (the macro). */
    public static final int[] MAX_BUYS_STEPS = {1, 2, 3, 5, 10, 20, 50};

    private static AhFlipConfig instance;

    // ---- AH Price Copier ----
    private boolean copierEnabled = false;
    /** Coins under the price that is copied (1 = the lowest BIN minus one coin, like SkyHanni's Copy Underbid Price). */
    private long copierUndercut = 1L;
    /** Prefer the price you listed the same item at a moment ago over the lowest BIN (the Doc's second half). */
    private boolean copierOwnPrice = true;

    // ---- AH Flipper ----
    private boolean flipperEnabled = false;
    private RunMode runMode = RunMode.BACKGROUND;
    private int minProfitPercent = 10;
    /** Tenths of a percent the relist undercuts the cheapest other BIN by (10 = 1.0%). */
    private int undercutTenths = 10;
    private long minSellPrice = 1_000_000L;
    private long minProfit = 250_000L;
    /** 0 = no cap beyond the purse. */
    private long maxBuyPrice = 0L;
    private int minListings = 3;
    private int macroMaxBuys = 3;

    private AhFlipConfig() {
    }

    public static AhFlipConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        AhFlipConfig cfg = new AhFlipConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.copierEnabled = ConfigJson.getBool(o, "copierEnabled", cfg.copierEnabled);
                cfg.copierUndercut = Math.max(0, Math.min(1_000_000L, ConfigJson.getLong(o, "copierUndercut", cfg.copierUndercut)));
                cfg.copierOwnPrice = ConfigJson.getBool(o, "copierOwnPrice", cfg.copierOwnPrice);
                cfg.flipperEnabled = ConfigJson.getBool(o, "flipperEnabled", cfg.flipperEnabled);
                cfg.runMode = ConfigJson.getEnum(o, "runMode", RunMode.class, cfg.runMode);
                cfg.minProfitPercent = clamp(ConfigJson.getInt(o, "minProfitPercent", cfg.minProfitPercent), 1, 1000);
                cfg.undercutTenths = clamp(ConfigJson.getInt(o, "undercutTenths", cfg.undercutTenths), 0, 500);
                cfg.minSellPrice = clampCoins(ConfigJson.getLong(o, "minSellPrice", cfg.minSellPrice));
                cfg.minProfit = clampCoins(ConfigJson.getLong(o, "minProfit", cfg.minProfit));
                cfg.maxBuyPrice = clampCoins(ConfigJson.getLong(o, "maxBuyPrice", cfg.maxBuyPrice));
                cfg.minListings = clamp(ConfigJson.getInt(o, "minListings", cfg.minListings), 1, 1000);
                cfg.macroMaxBuys = clamp(ConfigJson.getInt(o, "macroMaxBuys", cfg.macroMaxBuys), 1, 1000);
            } catch (Exception e) {
                cfg = new AhFlipConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("copierEnabled", copierEnabled);
            o.addProperty("copierUndercut", copierUndercut);
            o.addProperty("copierOwnPrice", copierOwnPrice);
            o.addProperty("flipperEnabled", flipperEnabled);
            o.addProperty("runMode", runMode.name());
            o.addProperty("minProfitPercent", minProfitPercent);
            o.addProperty("undercutTenths", undercutTenths);
            o.addProperty("minSellPrice", minSellPrice);
            o.addProperty("minProfit", minProfit);
            o.addProperty("maxBuyPrice", maxBuyPrice);
            o.addProperty("minListings", minListings);
            o.addProperty("macroMaxBuys", macroMaxBuys);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static long clampCoins(long v) {
        return Math.max(0L, Math.min(100_000_000_000L, v));
    }

    /** The next stop after (or before, {@code dir} -1) the one at or under {@code value}, wrapping. */
    static long stepLong(long[] stops, long value, int dir) {
        int at = 0;
        for (int i = 0; i < stops.length; i++) {
            if (stops[i] <= value) {
                at = i;
            }
        }
        return stops[Math.floorMod(at + dir, stops.length)];
    }

    static int stepInt(int[] stops, int value, int dir) {
        int at = 0;
        for (int i = 0; i < stops.length; i++) {
            if (stops[i] <= value) {
                at = i;
            }
        }
        return stops[Math.floorMod(at + dir, stops.length)];
    }

    // ---- AH Price Copier ----

    /** Gated on "Skyblock Only" like every feature's master getter. */
    public boolean isCopierEnabled() {
        return copierEnabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isCopierEnabledRaw() {
        return copierEnabled;
    }

    public void setCopierEnabled(boolean on) {
        this.copierEnabled = on;
    }

    public long getCopierUndercut() {
        return copierUndercut;
    }

    public void setCopierUndercut(long coins) {
        this.copierUndercut = Math.max(0, Math.min(1_000_000L, coins));
    }

    /** Copier Undercut stops: 0, 1, 1k, 10k, 100k coins. */
    public static final long[] COPIER_UNDERCUT_STEPS = {0L, 1L, 1_000L, 10_000L, 100_000L};

    public void stepCopierUndercut(int dir) {
        copierUndercut = stepLong(COPIER_UNDERCUT_STEPS, copierUndercut, dir);
    }

    public boolean isCopierOwnPrice() {
        return copierOwnPrice;
    }

    public void setCopierOwnPrice(boolean on) {
        this.copierOwnPrice = on;
    }

    // ---- AH Flipper ----

    public boolean isFlipperEnabled() {
        return flipperEnabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isFlipperEnabledRaw() {
        return flipperEnabled;
    }

    public void setFlipperEnabled(boolean on) {
        this.flipperEnabled = on;
    }

    /** Background in a legit build whatever the file says: Main is the macro, cheat build only. */
    public RunMode getRunMode() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED ? runMode : RunMode.BACKGROUND;
    }

    public void setRunMode(RunMode mode) {
        this.runMode = mode == null ? RunMode.BACKGROUND : mode;
    }

    public void stepRunMode(int dir) {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            runMode = RunMode.BACKGROUND;
            return;
        }
        RunMode[] all = RunMode.values();
        runMode = all[Math.floorMod(runMode.ordinal() + dir, all.length)];
    }

    /** The macro may act: cheat build, flipper on, Main mode. */
    public boolean isMacroActive() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && isFlipperEnabled() && runMode == RunMode.MAIN;
    }

    public int getMinProfitPercent() {
        return minProfitPercent;
    }

    public void setMinProfitPercent(int p) {
        this.minProfitPercent = clamp(p, 1, 1000);
    }

    public void stepMinProfitPercent(int dir) {
        minProfitPercent = stepInt(PROFIT_PERCENT_STEPS, minProfitPercent, dir);
    }

    public int getUndercutTenths() {
        return undercutTenths;
    }

    public void setUndercutTenths(int tenths) {
        this.undercutTenths = clamp(tenths, 0, 500);
    }

    public void stepUndercut(int dir) {
        undercutTenths = stepInt(UNDERCUT_TENTHS_STEPS, undercutTenths, dir);
    }

    public long getMinSellPrice() {
        return minSellPrice;
    }

    public void setMinSellPrice(long coins) {
        this.minSellPrice = clampCoins(coins);
    }

    public void stepMinSellPrice(int dir) {
        minSellPrice = stepLong(COIN_STEPS, minSellPrice, dir);
    }

    public long getMinProfit() {
        return minProfit;
    }

    public void setMinProfit(long coins) {
        this.minProfit = clampCoins(coins);
    }

    public void stepMinProfit(int dir) {
        minProfit = stepLong(COIN_STEPS, minProfit, dir);
    }

    public long getMaxBuyPrice() {
        return maxBuyPrice;
    }

    public void setMaxBuyPrice(long coins) {
        this.maxBuyPrice = clampCoins(coins);
    }

    public void stepMaxBuyPrice(int dir) {
        maxBuyPrice = stepLong(COIN_STEPS, maxBuyPrice, dir);
    }

    public int getMinListings() {
        return minListings;
    }

    public void setMinListings(int n) {
        this.minListings = clamp(n, 1, 1000);
    }

    public void stepMinListings(int dir) {
        minListings = stepInt(MIN_LISTINGS_STEPS, minListings, dir);
    }

    public int getMacroMaxBuys() {
        return macroMaxBuys;
    }

    public void setMacroMaxBuys(int n) {
        this.macroMaxBuys = clamp(n, 1, 1000);
    }

    public void stepMacroMaxBuys(int dir) {
        macroMaxBuys = stepInt(MAX_BUYS_STEPS, macroMaxBuys, dir);
    }
}

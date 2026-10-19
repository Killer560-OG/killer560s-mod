package com.killer560.hub.bazaarflip;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.KeyUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Bazaar-to-NPC Flipper settings (killer560, 2026-09-29: "a bazaar flipper... a setting that needs
 * a keybind to start it and any key stops it").
 *
 * <p>Ships OFF and unbound, like every other automation in this mod, and {@link #isEnabled()} is hard-gated
 * on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} so the whole feature folds out of the
 * legit jar. Every field here is loaded, saved, and has a getter and a setter - the project rule.
 */
public final class BazaarFlipConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-bazaarflip.json");

    /** How the scanner picks between candidates. killer560 asked for exactly these two and dropped a third
     *  ("profit per coin invested"), because that is arithmetically the same ordering as
     *  {@link #PERCENTAGE_MARGIN}. */
    public enum RankingMode {
        /** Realised profit as a percentage of what was actually spent. */
        PERCENTAGE_MARGIN("Percentage Margin"),
        /** Raw coins of profit for one buy-and-sell cycle. */
        TOTAL_PROFIT_PER_RUN("Total Profit Per Run");

        public final String display;

        RankingMode(String display) {
            this.display = display;
        }
    }

    /** Lowest and highest speed-slider positions. 1 is the most cautious, 10 the fastest. */
    public static final int MIN_SPEED = 1;
    public static final int MAX_SPEED = 10;

    private static BazaarFlipConfig instance;

    private boolean enabled = false;
    private int startKeyCode = KeyUtil.NONE;
    private RankingMode rankingMode = RankingMode.TOTAL_PROFIT_PER_RUN;
    // minProfitPerRun was removed on 2026-10-16 (killer560: "just remove the min profit slider. I want it to always
    // choose the best"): the best candidate by Rank By is taken whenever its net profit is above 0. An old file's
    // "minProfitPerRun" is ignored on load and dropped on the next save.
    /** Stop once the purse reaches this. 0 means no target. Typed into a box since 2026-10-16 ({@link #parseCoins}). */
    private long targetPurse = 0L;
    /** Menu speed (the key stays "speed" in the file, from before the sell slider existed). */
    private int speed = 5;
    /** Sell speed, same 1-10 scale as the menu speed but much faster at every step (killer560, 2026-10-19). */
    private int sellSpeed = 5;
    /** Run the autopilot (buy orders, sell offers, relists, collecting; decided from the Bazaar Advisor's answers)
     *  instead of the Insta Buy to /trades cycle. Off by default (2026-10-19). */
    private boolean autopilot = false;
    /** Coins the autopilot never spends below, and stops under. 0 = a tenth of the purse it started with. */
    private long autopilotReserve = 0L;
    /** New orders and Insta Buys one autopilot session may place (relists do not count). */
    private int autopilotMaxOrders = 20;
    /** The order caps the tab steps through. */
    public static final int[] AUTOPILOT_ORDER_CAPS = {5, 10, 20, 50, 100};

    private BazaarFlipConfig() {
    }

    public static BazaarFlipConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new BazaarFlipConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            BazaarFlipConfig cfg = new BazaarFlipConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
            // sanitizeBind, not sanitize: a mouse button is a legal bind here and is stored as a negative
            // code (see KeyUtil.MOUSE_CODE_BASE); plain sanitize would throw it away on every load.
            cfg.startKeyCode = KeyUtil.sanitizeBind(ConfigJson.getInt(obj, "startKeyCode", cfg.startKeyCode));
            cfg.rankingMode = ConfigJson.getEnum(obj, "rankingMode", RankingMode.class, cfg.rankingMode);
            cfg.targetPurse = clampPurse(ConfigJson.getLong(obj, "targetPurse", cfg.targetPurse));
            cfg.speed = clampSpeed(ConfigJson.getInt(obj, "speed", cfg.speed));
            cfg.sellSpeed = clampSpeed(ConfigJson.getInt(obj, "sellSpeed", cfg.sellSpeed));
            cfg.autopilot = ConfigJson.getBool(obj, "autopilot", cfg.autopilot);
            cfg.autopilotReserve = clampPurse(ConfigJson.getLong(obj, "autopilotReserve", cfg.autopilotReserve));
            cfg.autopilotMaxOrders = clampOrderCap(ConfigJson.getInt(obj, "autopilotMaxOrders", cfg.autopilotMaxOrders));
            instance = cfg;
        } catch (Exception e) {
            instance = new BazaarFlipConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("startKeyCode", startKeyCode);
            obj.addProperty("rankingMode", rankingMode.name());
            obj.addProperty("targetPurse", targetPurse);
            obj.addProperty("speed", speed);
            obj.addProperty("sellSpeed", sellSpeed);
            obj.addProperty("autopilot", autopilot);
            obj.addProperty("autopilotReserve", autopilotReserve);
            obj.addProperty("autopilotMaxOrders", autopilotMaxOrders);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static int clampSpeed(int v) {
        return Math.max(MIN_SPEED, Math.min(MAX_SPEED, v));
    }

    // ---- enabled ----

    /** Gated on the cheat build AND "Skyblock Only", same as every other automation's master getter. */
    public boolean isEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && enabled
                && com.killer560.hub.util.SkyblockGate.allows();
    }

    /** The real saved value, for the settings GUI - never gated, so the tab shows what is actually set. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // ---- start keybind ----

    public int getStartKeyCode() {
        return startKeyCode;
    }

    public void setStartKeyCode(int code) {
        this.startKeyCode = KeyUtil.sanitizeBind(code);
    }

    // ---- ranking ----

    public RankingMode getRankingMode() {
        return rankingMode;
    }

    public void setRankingMode(RankingMode mode) {
        this.rankingMode = mode == null ? RankingMode.TOTAL_PROFIT_PER_RUN : mode;
    }

    /** Cycles to the next mode, for a one-button GUI row. */
    public void cycleRankingMode() {
        cycleRankingMode(1);
    }

    public void cycleRankingMode(int dir) {
        RankingMode[] all = RankingMode.values();
        rankingMode = all[Math.floorMod(rankingMode.ordinal() + dir, all.length)];
    }

    // ---- floors and targets ----

    public long getTargetPurse() {
        return targetPurse;
    }

    public void setTargetPurse(long coins) {
        this.targetPurse = clampPurse(coins);
    }

    /** Highest target the box accepts: a trillion coins, far past any purse. Anything bigger is clamped to it. */
    public static final long MAX_TARGET_PURSE = 1_000_000_000_000L;

    private static long clampPurse(long v) {
        return Math.max(0L, Math.min(MAX_TARGET_PURSE, v));
    }

    private static final java.util.regex.Pattern COINS_TEXT =
            java.util.regex.Pattern.compile("^(\\d{1,15}(?:\\.\\d{1,6})?)([kmb]?)$");

    /**
     * The Target Purse box's text as coins: plain digits with or without commas ("5,000,000"), or a number with a
     * k / m / b suffix ("750k", "5m", "2.5m", "1b"), any case, spaces ignored. Empty or 0 is 0, "no target". A value
     * above {@link #MAX_TARGET_PURSE} is clamped to it; a fraction of a coin is dropped.
     *
     * @return the coins, or null when the text is not a number of that shape (the box then leaves the setting alone)
     */
    public static Long parseCoins(String text) {
        if (text == null) {
            return null;
        }
        String t = text.replace(",", "").replace(" ", "").replace("_", "").toLowerCase(java.util.Locale.ROOT);
        if (t.isEmpty()) {
            return 0L;
        }
        java.util.regex.Matcher m = COINS_TEXT.matcher(t);
        if (!m.matches()) {
            return null;
        }
        java.math.BigDecimal v = new java.math.BigDecimal(m.group(1));
        switch (m.group(2)) {
            case "k" -> v = v.movePointRight(3);
            case "m" -> v = v.movePointRight(6);
            case "b" -> v = v.movePointRight(9);
            default -> {
            }
        }
        if (v.compareTo(java.math.BigDecimal.valueOf(MAX_TARGET_PURSE)) > 0) {
            return MAX_TARGET_PURSE;
        }
        return v.longValue();
    }

    // ---- speed ----

    public int getSpeed() {
        return speed;
    }

    public void setSpeed(int speed) {
        this.speed = clampSpeed(speed);
    }

    /**
     * Shortest wait, in milliseconds, the runner leaves between two consecutive automated actions - a
     * command send, a menu click, a menu hop. Linear in the slider: 700 ms at speed 1 down to 160 ms at
     * speed 10.
     *
     * <p>This is the slider's whole job. It does NOT change how many items are bought, does not change any
     * timeout (a timeout is a safety limit, not a pace), and cannot beat {@code ActionGate}'s floor of one
     * automated interaction per client tick.
     */
    public int getActionDelayMinMs() {
        return 700 - 60 * (clampSpeed(speed) - 1);
    }

    /** Longest wait between two consecutive automated actions: 1300 ms at speed 1 down to 310 ms at speed
     *  10. Each wait is drawn uniformly between this and {@link #getActionDelayMinMs()} so the pacing is
     *  never a metronome. */
    public int getActionDelayMaxMs() {
        return 1300 - 110 * (clampSpeed(speed) - 1);
    }

    public int getSellSpeed() {
        return sellSpeed;
    }

    public void setSellSpeed(int sellSpeed) {
        this.sellSpeed = clampSpeed(sellSpeed);
    }

    /** Shortest wait between two sell shift-clicks: 150 ms at sell speed 1 down to 51 ms at 10 (about one client tick, the
     *  ActionGate floor, at the top). The menu speed never touches it. */
    public int getSellDelayMinMs() {
        return 150 - 11 * (clampSpeed(sellSpeed) - 1);
    }

    /** Longest wait between two sell shift-clicks: 220 ms at sell speed 1 down to 58 ms at 10. */
    public int getSellDelayMaxMs() {
        return 220 - 18 * (clampSpeed(sellSpeed) - 1);
    }

    // ---- autopilot ----

    /** Whether the start key runs the autopilot rather than the Insta Buy to /trades cycle. Cheat build only, like the
     *  whole feature: {@link #isEnabled()} already folds everything away in a legit jar. */
    public boolean isAutopilot() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autopilot;
    }

    public void setAutopilot(boolean autopilot) {
        this.autopilot = autopilot;
    }

    public long getAutopilotReserve() {
        return autopilotReserve;
    }

    public void setAutopilotReserve(long coins) {
        this.autopilotReserve = clampPurse(coins);
    }

    public int getAutopilotMaxOrders() {
        return autopilotMaxOrders;
    }

    public void setAutopilotMaxOrders(int n) {
        this.autopilotMaxOrders = clampOrderCap(n);
    }

    /** Steps the order cap through {@link #AUTOPILOT_ORDER_CAPS}, wrapping (left click forward, right click back). */
    public void stepAutopilotMaxOrders(int dir) {
        int at = 0;
        for (int i = 0; i < AUTOPILOT_ORDER_CAPS.length; i++) {
            if (AUTOPILOT_ORDER_CAPS[i] == autopilotMaxOrders) {
                at = i;
            }
        }
        autopilotMaxOrders = AUTOPILOT_ORDER_CAPS[Math.floorMod(at + dir, AUTOPILOT_ORDER_CAPS.length)];
    }

    private static int clampOrderCap(int v) {
        return Math.max(1, Math.min(1000, v));
    }
}

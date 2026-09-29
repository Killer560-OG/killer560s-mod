package com.killer560.hub.bazaarflip;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.KeyUtil;
import net.fabricmc.loader.api.FabricLoader;

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
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-bazaarflip.json");

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
    /**
     * Coins of profit a single buy-and-sell cycle must clear before the bot will touch it.
     * <p>
     * This is not optional polish, it is what makes percentage ranking usable at all: the top of the
     * percentage list on 2026-09-29 was {@code CORRUPTED_FRAGMENT} at 900% (0.1 coins to buy, 1 coin from the
     * NPC), which nets about 2,074 coins for a whole 2,304-unit inventory cycle. Applies to BOTH ranking
     * modes. Default 250,000 sits below the smallest of the genuinely worthwhile picks measured that day
     * ({@code ENCHANTED_REDSTONE_BLOCK}, ~873k a run) and far above the noise.
     */
    private long minProfitPerRun = 250_000L;
    /** Stop once the purse reaches this. 0 means no target. */
    private long targetPurse = 0L;
    private int speed = 5;

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
            cfg.minProfitPerRun = Math.max(0L, ConfigJson.getLong(obj, "minProfitPerRun", cfg.minProfitPerRun));
            cfg.targetPurse = Math.max(0L, ConfigJson.getLong(obj, "targetPurse", cfg.targetPurse));
            cfg.speed = clampSpeed(ConfigJson.getInt(obj, "speed", cfg.speed));
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
            obj.addProperty("minProfitPerRun", minProfitPerRun);
            obj.addProperty("targetPurse", targetPurse);
            obj.addProperty("speed", speed);
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
        RankingMode[] all = RankingMode.values();
        rankingMode = all[(rankingMode.ordinal() + 1) % all.length];
    }

    // ---- floors and targets ----

    public long getMinProfitPerRun() {
        return minProfitPerRun;
    }

    public void setMinProfitPerRun(long coins) {
        this.minProfitPerRun = Math.max(0L, coins);
    }

    public long getTargetPurse() {
        return targetPurse;
    }

    public void setTargetPurse(long coins) {
        this.targetPurse = Math.max(0L, coins);
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
}

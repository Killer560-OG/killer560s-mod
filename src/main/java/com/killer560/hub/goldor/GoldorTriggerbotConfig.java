package com.killer560.hub.goldor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Goldor Triggerbot settings - see {@link GoldorTriggerbotFeature}. Cheat build only; ships disabled.
 * <p>
 * The rate is a <b>CPS range</b>, not a delay, because that is what killer560 asked for ("goldore triggerbot with
 * cps range") and because the mod already has exactly that control for i4 ({@code I4SensorsConfig} 1-15 CPS on a
 * two-handled {@code RangeSliderWidget}). Arrow Align's cps-derived millisecond defaults were the other candidate,
 * but they express one value per slider; a genuine min/max band reads better as a single range slider, and it is
 * the band - not a fixed delay - that keeps the click spacing off a metronome. Every interval is rolled fresh
 * between the two ends (see {@link GoldorTriggerbotFeature}).
 * <p>
 * {@link #MAX_CPS} is 20 on purpose: {@code ActionGate} allows one automated interaction per client tick plus
 * 0-20 ms of jitter, so anything above roughly 20 CPS is a number the gate would quietly refuse to honour.
 */
public final class GoldorTriggerbotConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-goldortriggerbot.json");

    public static final int MIN_CPS = 1;
    /** The gate's one-tick floor caps any automation here - see this class's doc. */
    public static final int MAX_CPS = 20;
    /** Same 15-17 band killer560 asked Arrow Align's aura for on 2026-09-21. */
    public static final int DEFAULT_CPS_MIN = 15;
    public static final int DEFAULT_CPS_MAX = 17;

    public static final double MIN_RANGE = 3.0;
    public static final double MAX_RANGE = 50.0;
    public static final int MAX_AIM_DELAY_MS = 500;

    /** What a click actually is. Melee is an attack; a Terminator / Juju shot is a use. */
    public enum ClickType {
        ATTACK("Attack (Left Click)"), USE("Use (Right Click)");

        public final String label;

        ClickType(String label) {
            this.label = label;
        }
    }

    private static GoldorTriggerbotConfig instance;

    private boolean enabled = false;
    private int cpsMin = DEFAULT_CPS_MIN;
    private int cpsMax = DEFAULT_CPS_MAX;
    private ClickType clickType = ClickType.ATTACK;
    private double range = 25.0;
    private int aimDelayMs = 0;

    private GoldorTriggerbotConfig() {
    }

    public static GoldorTriggerbotConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        GoldorTriggerbotConfig cfg = new GoldorTriggerbotConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
                cfg.setCpsRange(ConfigJson.getInt(obj, "cpsMin", DEFAULT_CPS_MIN),
                        ConfigJson.getInt(obj, "cpsMax", DEFAULT_CPS_MAX));
                cfg.clickType = ConfigJson.getEnum(obj, "clickType", ClickType.class, ClickType.ATTACK);
                cfg.setRange(ConfigJson.getDouble(obj, "range", 25.0));
                cfg.setAimDelayMs(ConfigJson.getInt(obj, "aimDelayMs", 0));
            } catch (Exception ignored) {
                // Unreadable file: keep the defaults rather than half-applying a broken config.
                cfg = new GoldorTriggerbotConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("cpsMin", cpsMin);
            obj.addProperty("cpsMax", cpsMax);
            obj.addProperty("clickType", clickType.name());
            obj.addProperty("range", range);
            obj.addProperty("aimDelayMs", aimDelayMs);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** The gate the feature checks - false on the legit jar and outside Skyblock, always. */
    public boolean isEnabled() {
        return enabled && BuildVariant.CHEAT_FEATURES_ENABLED && SkyblockGate.allows();
    }

    /** The raw stored value, for the toggle's own ON/OFF label. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getCpsMin() {
        return cpsMin;
    }

    public int getCpsMax() {
        return cpsMax;
    }

    /** Clamped into {@link #MIN_CPS}..{@link #MAX_CPS}, with min &lt;= max always holding. */
    public void setCpsRange(int min, int max) {
        int lo = Math.max(MIN_CPS, Math.min(MAX_CPS, min));
        int hi = Math.max(MIN_CPS, Math.min(MAX_CPS, max));
        this.cpsMin = Math.min(lo, hi);
        this.cpsMax = Math.max(lo, hi);
    }

    public ClickType getClickType() {
        return clickType;
    }

    public void cycleClickType() {
        ClickType[] all = ClickType.values();
        clickType = all[(clickType.ordinal() + 1) % all.length];
    }

    /** How far down the crosshair the boss's hitbox may be and still count as "aimed at", in blocks. */
    public double getRange() {
        return range;
    }

    public void setRange(double range) {
        double clamped = Math.max(MIN_RANGE, Math.min(MAX_RANGE, range));
        this.range = Math.round(clamped * 10.0) / 10.0;
    }

    /** How long the crosshair has to have been on him before the first click, like the other triggerbots' Delay. */
    public int getAimDelayMs() {
        return aimDelayMs;
    }

    public void setAimDelayMs(int ms) {
        this.aimDelayMs = Math.max(0, Math.min(MAX_AIM_DELAY_MS, ms));
    }
}

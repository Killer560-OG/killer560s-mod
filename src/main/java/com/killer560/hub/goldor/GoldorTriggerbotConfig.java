package com.killer560.hub.goldor;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Wither Boss Triggerbot (Goldor Triggerbot until 2026-11-01) settings - see {@link GoldorTriggerbotFeature}. Cheat build only; ships disabled.
 * <p>
 * The rate is a <b>CPS range</b>, not a delay, because that is what killer560 asked for ("goldore triggerbot with
 * cps range") and because the mod already has exactly that control for i4 ({@code I4SensorsConfig} 1-15 CPS on a
 * two-handled {@code RangeSliderWidget}). Arrow Align's cps-derived millisecond defaults were the other candidate,
 * but they express one value per slider; a genuine min/max band reads better as a single range slider, and it is
 * the band - not a fixed delay - that keeps the click spacing off a metronome. Every interval is rolled fresh
 * between the two ends (see {@link GoldorTriggerbotFeature}).
 * <p>
 * {@link #MAX_CPS} is 20 on purpose: {@code ActionGate} allows one automated interaction per client tick plus
 * 0-20 ms of jitter, so anything above roughly 20 CPS is a number the gate would quietly refuse to honor.
 */
public final class GoldorTriggerbotConfig {

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-goldortriggerbot.json");

    public static final int MIN_CPS = 1;
    /** The gate's one-tick floor caps any automation here - see this class's doc. */
    public static final int MAX_CPS = 20;
    /** Same 15-17 band killer560 asked Arrow Align's aura for on 2026-09-21. */
    public static final int DEFAULT_CPS_MIN = 15;
    public static final int DEFAULT_CPS_MAX = 17;

    /** How far down the crosshair a boss's hitbox is looked for. Not a setting since 2026-11-01 (killer560: "It
     *  shouldnt have a range ... It should work from any range since i could be holding a bow or my mage beam"): far
     *  past any render distance a boss can be drawn at, so in practice there is no range. */
    public static final double AIM_DISTANCE = 256.0;
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
    private int aimDelayMs = 0;
    /** Every real wither boss of the F7/M7 fight (Maxor, Storm, Goldor, Necron), not just Goldor in P3. */
    private boolean allWithers = false;
    /** The dungeon classes it works on (your class from the tab list); all five by default. */
    private final java.util.EnumSet<com.killer560.hub.dungeonclass.DungeonClass> classes =
            java.util.EnumSet.allOf(com.killer560.hub.dungeonclass.DungeonClass.class);

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
                cfg.setAimDelayMs(ConfigJson.getInt(obj, "aimDelayMs", 0));
                cfg.allWithers = ConfigJson.getBool(obj, "allWithers", false);
                if (obj.has("classes")) {
                    cfg.classes.clear();
                    for (String n : obj.get("classes").getAsString().split(",")) {
                        cfg.setClassEnabled(com.killer560.hub.dungeonclass.DungeonClass.byName(n.trim()), true);
                    }
                }
            } catch (Exception ignored) {
                // Unreadable file: keep the defaults rather than half-applying a broken config.
                cfg = new GoldorTriggerbotConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("cpsMin", cpsMin);
        obj.addProperty("cpsMax", cpsMax);
        obj.addProperty("clickType", clickType.name());
        obj.addProperty("aimDelayMs", aimDelayMs);
        obj.addProperty("allWithers", allWithers);
        obj.addProperty("classes", String.join(",", classes.stream().map(Enum::name).toList()));
        ConfigJson.write(CONFIG_PATH, obj);
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

    public void cycleClickType(int dir) {
        ClickType[] all = ClickType.values();
        clickType = all[Math.floorMod(clickType.ordinal() + dir, all.length)];
    }

    public boolean isAllWithers() {
        return allWithers;
    }

    public void setAllWithers(boolean v) {
        allWithers = v;
    }

    public boolean isClassEnabled(com.killer560.hub.dungeonclass.DungeonClass c) {
        return c != null && classes.contains(c);
    }

    public void setClassEnabled(com.killer560.hub.dungeonclass.DungeonClass c, boolean on) {
        if (c == null) {
            return;
        }
        if (on ? classes.add(c) : classes.remove(c)) {
            return;
        }
    }

    /** How long the crosshair has to have been on him before the first click, like the other triggerbots' Delay. */
    public int getAimDelayMs() {
        return aimDelayMs;
    }

    public void setAimDelayMs(int ms) {
        this.aimDelayMs = Math.max(0, Math.min(MAX_AIM_DELAY_MS, ms));
    }
}

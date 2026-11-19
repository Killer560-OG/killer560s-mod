package com.killer560.hub.goldor;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.RateMs;
import com.killer560.hub.util.SkyblockGate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Wither Boss Triggerbot (Goldor Triggerbot until 2026-11-01) settings - see {@link GoldorTriggerbotFeature}. Cheat build only; ships disabled.
 * <p>
 * The rate is a <b>delay range in ms</b> (sliderunits-1118; it was a CPS range): the band, not a fixed delay, keeps the
 * click spacing off a metronome. Every interval is rolled fresh between the two ends (see {@link GoldorTriggerbotFeature}).
 * {@link RateMs#MIN} is 50 on purpose: {@code ActionGate} allows one automated interaction per client tick plus
 * 0-20 ms of jitter, so anything faster is a number the gate would quietly refuse to honor.
 */
public final class GoldorTriggerbotConfig {

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-goldortriggerbot.json");

    /** The old 15-17 CPS band as a gap (1000/17 and 1000/15). */
    public static final int DEFAULT_DELAY_MIN = 59;
    public static final int DEFAULT_DELAY_MAX = 67;

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
    private int delayMin = DEFAULT_DELAY_MIN;
    private int delayMax = DEFAULT_DELAY_MAX;
    private ClickType clickType = ClickType.ATTACK;
    private int aimDelayMs = 0;
    /** The wither bosses the triggerbot clicks (index as in BOSSES): Goldor only by default, as it always was. */
    private final boolean[] hitBoss = {false, false, true, false};
    /** The dungeon classes it works on (your class from the tab list); all five by default. */
    private final java.util.EnumSet<com.killer560.hub.dungeonclass.DungeonClass> classes =
            java.util.EnumSet.allOf(com.killer560.hub.dungeonclass.DungeonClass.class);

    /** Wither Aimbot (witheraim-1111): turns your view onto the boss wither of the phases picked below. Its own switch,
     *  independent of the triggerbot's. */
    private boolean aimbot = false;
    /** The aimbot also clicks (the CPS band and Click Type above) while the crosshair is on the boss. */
    private boolean aimAutoHit = true;
    /** The aimbot turns only while you hold your attack key. */
    private boolean aimHoldLeftClick = false;
    /** Per boss, in phase order: Maxor (P1), Storm (P2), Goldor (P3), Necron (P4). */
    public static final String[] BOSSES = {"Maxor", "Storm", "Goldor", "Necron"};
    private final boolean[] aimBoss = {true, true, true, true};

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
                // Old cpsMin/cpsMax keys: more clicks a second is a shorter gap.
                cfg.setDelayRange(RateMs.read(obj, "delayMin", "cpsMax", DEFAULT_DELAY_MIN),
                        RateMs.read(obj, "delayMax", "cpsMin", DEFAULT_DELAY_MAX));
                cfg.clickType = ConfigJson.getEnum(obj, "clickType", ClickType.class, ClickType.ATTACK);
                cfg.setAimDelayMs(ConfigJson.getInt(obj, "aimDelayMs", 0));
                // Old file: "All Wither Bosses" on meant every boss.
                boolean all = ConfigJson.getBool(obj, "allWithers", false);
                for (int i = 0; i < BOSSES.length; i++) {
                    cfg.hitBoss[i] = ConfigJson.getBool(obj, "hit" + BOSSES[i], all || i == 2);
                }
                cfg.aimbot = ConfigJson.getBool(obj, "aimbot", false);
                cfg.aimAutoHit = ConfigJson.getBool(obj, "aimAutoHit", true);
                cfg.aimHoldLeftClick = ConfigJson.getBool(obj, "aimHoldLeftClick", false);
                for (int i = 0; i < BOSSES.length; i++) {
                    cfg.aimBoss[i] = ConfigJson.getBool(obj, "aim" + BOSSES[i], true);
                }
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
        obj.addProperty("delayMin", delayMin);
        obj.addProperty("delayMax", delayMax);
        obj.addProperty("clickType", clickType.name());
        obj.addProperty("aimDelayMs", aimDelayMs);
        for (int i = 0; i < BOSSES.length; i++) {
            obj.addProperty("hit" + BOSSES[i], hitBoss[i]);
        }
        obj.addProperty("aimbot", aimbot);
        obj.addProperty("aimAutoHit", aimAutoHit);
        obj.addProperty("aimHoldLeftClick", aimHoldLeftClick);
        for (int i = 0; i < BOSSES.length; i++) {
            obj.addProperty("aim" + BOSSES[i], aimBoss[i]);
        }
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

    public int getDelayMin() {
        return delayMin;
    }

    public int getDelayMax() {
        return delayMax;
    }

    /** Clamped into {@link RateMs#MIN}..{@link RateMs#MAX}, with min &lt;= max always holding. */
    public void setDelayRange(int min, int max) {
        int lo = RateMs.clamp(min);
        int hi = RateMs.clamp(max);
        this.delayMin = Math.min(lo, hi);
        this.delayMax = Math.max(lo, hi);
    }

    public ClickType getClickType() {
        return clickType;
    }

    public void cycleClickType(int dir) {
        ClickType[] all = ClickType.values();
        clickType = all[Math.floorMod(clickType.ordinal() + dir, all.length)];
    }

    /** Whether the triggerbot clicks boss {@code i} of {@link #BOSSES}. */
    public boolean isHitAt(int i) {
        return i >= 0 && i < hitBoss.length && hitBoss[i];
    }

    public void setHitAt(int i, boolean v) {
        if (i >= 0 && i < hitBoss.length) {
            hitBoss[i] = v;
        }
    }

    /** The aimbot's gate: its switch, on the cheat jar, inside Skyblock. */
    public boolean isAimbotOn() {
        return aimbot && BuildVariant.CHEAT_FEATURES_ENABLED && SkyblockGate.allows();
    }

    public boolean isAimbot() {
        return aimbot;
    }

    public void setAimbot(boolean v) {
        aimbot = v;
    }

    public boolean isAimAutoHit() {
        return aimAutoHit;
    }

    public void setAimAutoHit(boolean v) {
        aimAutoHit = v;
    }

    public boolean isAimHoldLeftClick() {
        return aimHoldLeftClick;
    }

    public void setAimHoldLeftClick(boolean v) {
        aimHoldLeftClick = v;
    }

    /** Whether the aimbot works on boss {@code i} of {@link #BOSSES} (0 Maxor .. 3 Necron). */
    public boolean isAimAt(int i) {
        return i >= 0 && i < aimBoss.length && aimBoss[i];
    }

    public void setAimAt(int i, boolean v) {
        if (i >= 0 && i < aimBoss.length) {
            aimBoss[i] = v;
        }
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

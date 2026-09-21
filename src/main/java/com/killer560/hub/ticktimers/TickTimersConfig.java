package com.killer560.hub.ticktimers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Tick Timers settings - see {@link TickTimersFeature}. Ships disabled by default.
 *  <p>
 *  killer560, 2026-09-20: "fold Goldor Frenzy into Tick Timers" - the separate Goldor Frenzy Timer tab/config
 *  duplicated this file's own repeating 60-tick Goldor countdown. Its one genuinely new option ("Show Total")
 *  is folded in as {@link #goldorShowTotal}; its "Pre-Goldor" option is the same thing this file already calls
 *  {@link #goldorStartTimer} (just a different name/tick count for the same real gap), so it is migrated
 *  directly onto that field. {@link #migrateFromGoldorFrenzy} runs once (guarded by {@link #goldorFrenzyMigrated})
 *  so nothing killer560 had set on the old tab is lost; see that method for exactly what is and isn't copied.
 *  <p>
 *  killer560, 2026-09-21: "Move [the pad timer and the purple-pad crush timer] to Tick Timers. One home per
 *  timer." Both used to live on the F7 Spots tab ({@code f7spots.CrushTimer}, now {@link CrushTimer} in this
 *  package). {@link #padCycleTimer}/{@link #crushTimer}/{@link #crushTitle}/{@link #crushPadHighlight}/
 *  {@link #crushAllPads}/{@link #crushPadColor}/{@link #crushIntervalSeconds}/{@link #crushWarnSeconds}/
 *  {@link #crushExtraTrigger} are the migrated settings; {@link #migrateFromF7SpotsCrush} runs once (guarded by
 *  {@link #f7spotsCrushMigrated}) so nothing he had set on the old tab is lost - see that method for exactly
 *  what is and isn't copied, including why {@link #padCycleTimer} is a brand new, independently-toggleable
 *  field instead of staying folded into {@link #stormTimer}. */
public final class TickTimersConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-ticktimers");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-ticktimers.json");
    private static final Path OLD_GOLDOR_FRENZY_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-goldorfrenzy.json");
    private static final Path OLD_F7SPOTS_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-f7spots.json");

    /** Storm's purple pad, matching the mod's existing P2 pad box (QUOI {@code AutoLeap.kt} via FastLeapFeature) -
     *  same default F7 Spots' {@code CrushTimer} shipped before the 2026-09-21 move. */
    public static final int DEFAULT_PAD_COLOR = 0xFFAA00AA;
    public static final float MAX_CRUSH_INTERVAL = 120f;
    public static final float MAX_CRUSH_WARN = 15f;

    private static TickTimersConfig instance;

    private boolean enabled = false;
    private boolean displayInTicks = false;
    private boolean showSymbol = true;
    private boolean showPrefix = true;
    private boolean necronTimer = true;
    private boolean goldorTimer = true;
    // Odin TickTimers.kt's own "Start timer" setting (default false): shows Goldor's 104-tick "Start:"
    // countdown after Storm dies. Off = the Goldor line only shows the repeating "Tick:" timer, like Odin.
    private boolean goldorStartTimer = false;
    private boolean stormTimer = true;
    // Devonian GoldorFrenzyTimer's "showTotal" (2026-09-20 merge): replaces the repeating "Tick:" countdown
    // with how long P3 has been running since Goldor's arrival line, once the Goldor Frenzy tab folded in here.
    private boolean goldorShowTotal = false;
    // killer560, 2026-09-20: "the 1s death tick during clear" (NoammAddons TickTimers.kt's clear-section pulse,
    // ported as a generic 20-server-tick countdown - see TickTimersFeature's class doc for why it uses the
    // shared ServerTickClock instead of NoammAddons' own world-time-packet trick). New feature, ships OFF.
    private boolean clearDeathTick = false;
    // The "option to turn it off after the run starts" killer560 asked for. OFF (default): the tick keeps
    // running once the boss fight starts too. ON: it stops as soon as DungeonState.isBossPhaseActive().
    private boolean deathTickStopsAtBoss = false;
    // One-off migration guard - see migrateFromGoldorFrenzy().
    private boolean goldorFrenzyMigrated = false;

    // ---- crush timer (F7/M7 P2 Storm), moved from f7spots.F7SpotsConfig 2026-09-21 ----
    // NoammAddons' "Storm Pad Timer" - the repeating 20-server-tick pad cycle. Defaults true: before this move
    // TickTimersFeature's OWN padTickTime counted whenever isStormTimer() was true (default true), so a fresh
    // install must keep showing it rather than silently losing it just because it's now its own toggle.
    private boolean padCycleTimer = true;
    private boolean crushTimer = false;
    private boolean crushTitle = false;
    private boolean crushPadHighlight = false;
    private boolean crushAllPads = false;
    private int crushPadColor = DEFAULT_PAD_COLOR;
    /** 0 = unknown/off: the HUD then only counts UP since the last crush. See {@link CrushTimer}. */
    private float crushIntervalSeconds = 0f;
    private float crushWarnSeconds = 3f;
    /** Extra chat line (substring, case-insensitive) that also restarts the countdown. Blank = built-ins only. */
    private String crushExtraTrigger = "";
    // One-off migration guard - see migrateFromF7SpotsCrush().
    private boolean f7spotsCrushMigrated = false;

    private TickTimersConfig() {
    }

    public static TickTimersConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        TickTimersConfig cfg;
        if (!Files.exists(CONFIG_PATH)) {
            cfg = new TickTimersConfig();
        } else {
            TickTimersConfig parsed;
            try {
                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                parsed = new TickTimersConfig();
                parsed.enabled = getBool(obj, "enabled", false);
                parsed.displayInTicks = getBool(obj, "displayInTicks", false);
                parsed.showSymbol = getBool(obj, "showSymbol", true);
                parsed.showPrefix = getBool(obj, "showPrefix", true);
                parsed.necronTimer = getBool(obj, "necronTimer", true);
                parsed.goldorTimer = getBool(obj, "goldorTimer", true);
                parsed.goldorStartTimer = getBool(obj, "goldorStartTimer", false);
                parsed.stormTimer = getBool(obj, "stormTimer", true);
                parsed.goldorShowTotal = getBool(obj, "goldorShowTotal", false);
                parsed.clearDeathTick = getBool(obj, "clearDeathTick", false);
                parsed.deathTickStopsAtBoss = getBool(obj, "deathTickStopsAtBoss", false);
                parsed.goldorFrenzyMigrated = getBool(obj, "goldorFrenzyMigrated", false);
                parsed.padCycleTimer = getBool(obj, "padCycleTimer", true);
                parsed.crushTimer = getBool(obj, "crushTimer", false);
                parsed.crushTitle = getBool(obj, "crushTitle", false);
                parsed.crushPadHighlight = getBool(obj, "crushPadHighlight", false);
                parsed.crushAllPads = getBool(obj, "crushAllPads", false);
                parsed.crushPadColor = ConfigJson.getInt(obj, "crushPadColor", DEFAULT_PAD_COLOR);
                parsed.setCrushIntervalSeconds(ConfigJson.getFloat(obj, "crushIntervalSeconds", 0f));
                parsed.setCrushWarnSeconds(ConfigJson.getFloat(obj, "crushWarnSeconds", 3f));
                parsed.crushExtraTrigger = ConfigJson.getString(obj, "crushExtraTrigger", "");
                parsed.f7spotsCrushMigrated = getBool(obj, "f7spotsCrushMigrated", false);
            } catch (Exception e) {
                parsed = new TickTimersConfig();
            }
            cfg = parsed;
        }
        if (!cfg.goldorFrenzyMigrated) {
            boolean migrated = cfg.migrateFromGoldorFrenzy();
            cfg.goldorFrenzyMigrated = true;
            if (migrated) {
                // Write straight away, so this really is a ONE-off - otherwise it would re-run every launch
                // until the user happens to touch this tab (killer560's rag axe migration does the same).
                cfg.save();
            }
        }
        if (!cfg.f7spotsCrushMigrated) {
            boolean migrated = cfg.migrateFromF7SpotsCrush();
            cfg.f7spotsCrushMigrated = true;
            if (migrated) {
                cfg.save();
            }
        }
        instance = cfg;
    }

    /** One-off: pulls the old Goldor Frenzy Timer tab's settings into this file, then the caller marks
     *  {@link #goldorFrenzyMigrated} so this never runs again (the old file is left on disk, unread, same as
     *  {@code RagAxeConfig}'s migration from Dungeon Alerts).
     *  <p>
     *  What's copied and why:
     *  <ul>
     *  <li>{@code enabled} (the old tab's own master switch) OR's into THIS file's {@link #enabled} and
     *      {@link #goldorTimer} - never turned off, only on - so a killer560 who had the old Goldor Frenzy line
     *      showing doesn't have it silently vanish just because he'd never touched the master Tick Timers
     *      switch. It can't be a direct copy: the old "enabled" only ever meant "show the Goldor line", while
     *      this file's "enabled" also gates Necron and Storm, and stomping it off would break started
     *      Tick Timers.
     *  <li>{@code showTotal} -&gt; {@link #goldorShowTotal} directly - brand new field here, no prior value to
     *      preserve.
     *  <li>{@code showPreGoldor} -&gt; {@link #goldorStartTimer} directly - same real ~5s Storm-death-to-Goldor
     *      gap as this file's own Goldor Start option, just a different name and tick count (100 vs 104) on
     *      the losing side. Copied as-is per "nothing he has set is lost", even though it means a killer560 who
     *      ever opened the old tab (its default was ON) may see Goldor Start switch on that was previously OFF
     *      here - flagged in the implementation notes for him to double check.
     *  <li>{@code displayInTicks} / {@code showPrefix} deliberately NOT copied - those already exist here as
     *      shared formatting for every line (Necron/Goldor/Storm), and the old tab's copies only ever applied
     *      to its own single line. Importing them would silently change how the OTHER lines already display.
     *  </ul>
     *  @return true if the old file existed and was read (the caller then persists the result immediately). */
    private boolean migrateFromGoldorFrenzy() {
        JsonObject o = read(OLD_GOLDOR_FRENZY_PATH);
        if (o == null) {
            return false;
        }
        boolean oldEnabled = ConfigJson.getBool(o, "enabled", false);
        enabled = enabled || oldEnabled;
        goldorTimer = goldorTimer || oldEnabled;
        goldorStartTimer = ConfigJson.getBool(o, "showPreGoldor", goldorStartTimer);
        goldorShowTotal = ConfigJson.getBool(o, "showTotal", goldorShowTotal);
        LOGGER.info("[TickTimers] Migrated Goldor Frenzy Timer settings (oldEnabled={}, showTotal={}, showPreGoldor={})",
                oldEnabled, goldorShowTotal, goldorStartTimer);
        return true;
    }

    /** One-off: pulls the old F7 Spots tab's crush-timer settings into this file (killer560, 2026-09-21:
     *  "Move [the pad timer and the purple-pad crush timer] to Tick Timers"), then the caller marks
     *  {@link #f7spotsCrushMigrated} so this never runs again. The old {@code killer560smod-f7spots.json} file
     *  is left on disk, unread from here on - its walk-waypoint and aim-spot settings still live there and are
     *  still read by {@code F7SpotsConfig} itself, only the crush-timer keys below are now dead weight in it.
     *  <p>
     *  What's copied and why:
     *  <ul>
     *  <li>{@code crushTimer}/{@code crushTitle}/{@code crushPadHighlight}/{@code crushAllPads}/
     *      {@code crushPadColor}/{@code crushIntervalSeconds}/{@code crushWarnSeconds}/{@code crushExtraTrigger}
     *      copied DIRECTLY - brand new fields here, nothing pre-existing to preserve, same as Goldor Frenzy's
     *      {@code showTotal}.
     *  <li>{@code padCycleTimer} -&gt; {@link #padCycleTimer} OR'd in (never turned off, only on): this file
     *      already ran the exact same 20-tick pad cycle bundled under {@link #stormTimer} (default true), so a
     *      killer560 who never touched the old F7 Spots toggle (it defaulted OFF there) must still see the pad
     *      line he already had. One case this can't perfectly preserve, flagged for him: if he'd turned OFF the
     *      "Storm" bundle here specifically to silence the pad line (its only OFF switch before this move), the
     *      new independent Pad Cycle Timer toggle still starts ON and he'll need to flip it off once.
     *  </ul>
     *  @return true if the old file existed and was read (the caller then persists the result immediately). */
    private boolean migrateFromF7SpotsCrush() {
        JsonObject o = read(OLD_F7SPOTS_PATH);
        if (o == null) {
            return false;
        }
        crushTimer = ConfigJson.getBool(o, "crushTimer", crushTimer);
        crushTitle = ConfigJson.getBool(o, "crushTitle", crushTitle);
        crushPadHighlight = ConfigJson.getBool(o, "crushPadHighlight", crushPadHighlight);
        crushAllPads = ConfigJson.getBool(o, "crushAllPads", crushAllPads);
        crushPadColor = ConfigJson.getInt(o, "crushPadColor", crushPadColor);
        setCrushIntervalSeconds(ConfigJson.getFloat(o, "crushIntervalSeconds", crushIntervalSeconds));
        setCrushWarnSeconds(ConfigJson.getFloat(o, "crushWarnSeconds", crushWarnSeconds));
        crushExtraTrigger = ConfigJson.getString(o, "crushExtraTrigger", crushExtraTrigger);
        boolean oldPadCycle = ConfigJson.getBool(o, "padCycleTimer", false);
        padCycleTimer = padCycleTimer || oldPadCycle;
        LOGGER.info("[TickTimers] Migrated F7 Spots crush-timer settings (crushTimer={}, crushTitle={}, "
                        + "padCycleTimer={})", crushTimer, crushTitle, padCycleTimer);
        return true;
    }

    private static JsonObject read(Path path) {
        try {
            if (!Files.exists(path)) {
                return null;
            }
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            LOGGER.warn("[TickTimers] Failed to read {}, skipping migration", path.getFileName(), e);
            return null;
        }
    }

    private static boolean getBool(JsonObject obj, String key, boolean def) {
        return ConfigJson.getBool(obj, key, def);
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("displayInTicks", displayInTicks);
            obj.addProperty("showSymbol", showSymbol);
            obj.addProperty("showPrefix", showPrefix);
            obj.addProperty("necronTimer", necronTimer);
            obj.addProperty("goldorTimer", goldorTimer);
            obj.addProperty("goldorStartTimer", goldorStartTimer);
            obj.addProperty("stormTimer", stormTimer);
            obj.addProperty("goldorShowTotal", goldorShowTotal);
            obj.addProperty("clearDeathTick", clearDeathTick);
            obj.addProperty("deathTickStopsAtBoss", deathTickStopsAtBoss);
            obj.addProperty("goldorFrenzyMigrated", goldorFrenzyMigrated);
            obj.addProperty("padCycleTimer", padCycleTimer);
            obj.addProperty("crushTimer", crushTimer);
            obj.addProperty("crushTitle", crushTitle);
            obj.addProperty("crushPadHighlight", crushPadHighlight);
            obj.addProperty("crushAllPads", crushAllPads);
            obj.addProperty("crushPadColor", crushPadColor);
            obj.addProperty("crushIntervalSeconds", crushIntervalSeconds);
            obj.addProperty("crushWarnSeconds", crushWarnSeconds);
            obj.addProperty("crushExtraTrigger", crushExtraTrigger == null ? "" : crushExtraTrigger);
            obj.addProperty("f7spotsCrushMigrated", f7spotsCrushMigrated);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDisplayInTicks() {
        return displayInTicks;
    }

    public void setDisplayInTicks(boolean displayInTicks) {
        this.displayInTicks = displayInTicks;
    }

    public boolean isShowSymbol() {
        return showSymbol;
    }

    public void setShowSymbol(boolean showSymbol) {
        this.showSymbol = showSymbol;
    }

    public boolean isShowPrefix() {
        return showPrefix;
    }

    public void setShowPrefix(boolean showPrefix) {
        this.showPrefix = showPrefix;
    }

    public boolean isNecronTimer() {
        return necronTimer;
    }

    public void setNecronTimer(boolean necronTimer) {
        this.necronTimer = necronTimer;
    }

    public boolean isGoldorTimer() {
        return goldorTimer;
    }

    public void setGoldorTimer(boolean goldorTimer) {
        this.goldorTimer = goldorTimer;
    }

    public boolean isGoldorStartTimer() {
        return goldorStartTimer;
    }

    public void setGoldorStartTimer(boolean goldorStartTimer) {
        this.goldorStartTimer = goldorStartTimer;
    }

    public boolean isStormTimer() {
        return stormTimer;
    }

    public void setStormTimer(boolean stormTimer) {
        this.stormTimer = stormTimer;
    }

    public boolean isGoldorShowTotal() {
        return goldorShowTotal;
    }

    public void setGoldorShowTotal(boolean goldorShowTotal) {
        this.goldorShowTotal = goldorShowTotal;
    }

    public boolean isClearDeathTick() {
        return clearDeathTick;
    }

    public void setClearDeathTick(boolean clearDeathTick) {
        this.clearDeathTick = clearDeathTick;
    }

    public boolean isDeathTickStopsAtBoss() {
        return deathTickStopsAtBoss;
    }

    public void setDeathTickStopsAtBoss(boolean deathTickStopsAtBoss) {
        this.deathTickStopsAtBoss = deathTickStopsAtBoss;
    }

    // ---- crush timer (F7/M7 P2 Storm) ----
    /** NoammAddons' repeating 20-server-tick Storm pad cycle - split out of {@link #stormTimer} 2026-09-21 so
     *  it means the same specific thing his old F7 Spots toggle did. */
    public boolean isPadCycleTimer() {
        return padCycleTimer;
    }

    public void setPadCycleTimer(boolean padCycleTimer) {
        this.padCycleTimer = padCycleTimer;
    }

    public boolean isCrushTimerEnabled() {
        return crushTimer;
    }

    public void setCrushTimer(boolean crushTimer) {
        this.crushTimer = crushTimer;
    }

    public boolean isCrushTitleEnabled() {
        return crushTitle;
    }

    public void setCrushTitle(boolean crushTitle) {
        this.crushTitle = crushTitle;
    }

    public boolean isCrushPadHighlightEnabled() {
        return crushPadHighlight;
    }

    public void setCrushPadHighlight(boolean crushPadHighlight) {
        this.crushPadHighlight = crushPadHighlight;
    }

    public boolean isCrushAllPads() {
        return crushAllPads;
    }

    public void setCrushAllPads(boolean crushAllPads) {
        this.crushAllPads = crushAllPads;
    }

    public int getCrushPadColor() {
        return crushPadColor;
    }

    public void setCrushPadColor(int crushPadColor) {
        this.crushPadColor = crushPadColor;
    }

    public float getCrushIntervalSeconds() {
        return crushIntervalSeconds;
    }

    public void setCrushIntervalSeconds(float v) {
        crushIntervalSeconds = Math.max(0f, Math.min(MAX_CRUSH_INTERVAL, Math.round(v * 2f) / 2f));
    }

    public float getCrushWarnSeconds() {
        return crushWarnSeconds;
    }

    public void setCrushWarnSeconds(float v) {
        crushWarnSeconds = Math.max(0f, Math.min(MAX_CRUSH_WARN, Math.round(v * 2f) / 2f));
    }

    public String getCrushExtraTrigger() {
        return crushExtraTrigger == null ? "" : crushExtraTrigger;
    }

    public void setCrushExtraTrigger(String v) {
        crushExtraTrigger = v == null ? "" : v.trim();
    }
}

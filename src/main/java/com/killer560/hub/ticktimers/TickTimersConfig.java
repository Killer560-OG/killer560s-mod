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
 *  so nothing killer560 had set on the old tab is lost; see that method for exactly what is and isn't copied. */
public final class TickTimersConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-ticktimers");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-ticktimers.json");
    private static final Path OLD_GOLDOR_FRENZY_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-goldorfrenzy.json");

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
}

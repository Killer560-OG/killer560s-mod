package com.killer560.hub.splittimers;

import com.killer560.hub.util.BoolFields;
import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Split Timers settings - see {@link SplitTimersFeature}. Ships disabled by default. */
public final class SplitTimersConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-splittimers.json");

    private static SplitTimersConfig instance;

    private boolean enabled = false;
    private boolean announceInChat = true;
    // Killer560's own explicit request (2026-09-14): "for others it should go based off of section open.
    // So use the split timer to figure out when p2 started, then how long it took for their chat
    // message... (od has this feature.)" - real reference: Odin's own confirmed TerminalTimes.kt, which
    // rewrites the real "X completed a device!"/"X activated a lever!" chat line in place to append how
    // long the current split segment has been running. Defaults on, matching that being a definite ask.
    // M7 Phase 5 lines (2026-09-15, killer560's M7 request "split timers lines to the right ... relic stuff"):
    // per-dragon spawn->kill and relic spawn/placed rows from P5Splits. New, so default OFF.
    private boolean p5DragonLines = false;
    private boolean p5RelicLines = false;
    /** true = a column to the right of the normal split rows, false = under them. */
    private boolean p5LinesRight = true;
    /** Devonian {@code WatcherSplits} "Watcher Move" - entity-movement driven, not a chat line. Default OFF. */
    private boolean watcherMoveSplit = false;
    // Core entry times (2026-09-16, killer560: "add a time to enter core after terms finish timer with an
    // option to send slowest to chat") - see CoreEntryTimes.
    private boolean coreEntryTimes = false;
    /** Prints the slowest player into the core as a client-side Mod Chat line. Default OFF. */
    private boolean coreEntrySlowestChat = false;
    /** Sends that same line to PARTY chat (everyone sees it). Separate toggle, default OFF, once per run. */
    private boolean coreEntrySlowestParty = false;
    /** Shows the same slowest-into-core result at the very bottom of the Split Timers HUD, independent of the
     *  chat/party announce toggles above - killer560, 2026-09-20: "at the very bottom of the split timers show
     *  the slowest person into core and their time." Default OFF like every new HUD line here. */
    private boolean coreEntrySlowestHud = false;
    /** killer560, 2026-11-01: "split timers should have an option to show recore stuff even if split timers are off" -
     *  Core Entry times (and their HUD rows) run with the Split Timers switch off. Default OFF. */
    private boolean coreEntryWithoutSplits = false;

    /** Lagless time (see SplitLagClock) in "(...)" after every time the splits show. ON by default since the
     *  2026-10-08 layout, whose every row reads "real (lagless)". The 2026-09-20 layout toggles (divider, Boss Entry,
     *  Boss, Total With/Without Lag, Lag Lost) and Clear Splits are gone: that layout is now fixed, and their old keys
     *  in a saved file are ignored. */
    private boolean laglessTimes = true;
    /** killer560, 2026-11-01: "have an option for it to count every .01s or only .05 seconds (a tick counting option).
     *  It would just round to the nearest .05 for that one." Cheat build only. Default OFF (every 0.01 s). */
    private boolean tickCounting = false;

    private SplitTimersConfig() {
    }

    public static SplitTimersConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    /** The plain on/off settings: key, default, and how to read and write the field. */
    private static final BoolFields<SplitTimersConfig> BOOLS = new BoolFields<SplitTimersConfig>()
            .add("enabled", false, c -> c.enabled, (c, v) -> c.enabled = v)
            .add("announceInChat", true, c -> c.announceInChat, (c, v) -> c.announceInChat = v)
            .add("p5DragonLines", false, c -> c.p5DragonLines, (c, v) -> c.p5DragonLines = v)
            .add("p5RelicLines", false, c -> c.p5RelicLines, (c, v) -> c.p5RelicLines = v)
            .add("p5LinesRight", true, c -> c.p5LinesRight, (c, v) -> c.p5LinesRight = v)
            .add("watcherMoveSplit", false, c -> c.watcherMoveSplit, (c, v) -> c.watcherMoveSplit = v)
            .add("coreEntryTimes", false, c -> c.coreEntryTimes, (c, v) -> c.coreEntryTimes = v)
            .add("coreEntrySlowestChat", false, c -> c.coreEntrySlowestChat, (c, v) -> c.coreEntrySlowestChat = v)
            .add("coreEntrySlowestParty", false, c -> c.coreEntrySlowestParty, (c, v) -> c.coreEntrySlowestParty = v)
            .add("coreEntrySlowestHud", false, c -> c.coreEntrySlowestHud, (c, v) -> c.coreEntrySlowestHud = v)
            .add("coreEntryWithoutSplits", false, c -> c.coreEntryWithoutSplits, (c, v) -> c.coreEntryWithoutSplits = v)
            .add("laglessTimes", true, c -> c.laglessTimes, (c, v) -> c.laglessTimes = v)
            .add("tickCounting", false, c -> c.tickCounting, (c, v) -> c.tickCounting = v);

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new SplitTimersConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            SplitTimersConfig cfg = new SplitTimersConfig();
            BOOLS.load(cfg, obj);
            instance = cfg;
        } catch (Exception e) {
            instance = new SplitTimersConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            BOOLS.save(this, obj);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() { return enabled && com.killer560.hub.util.SkyblockGate.allows(); }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isAnnounceInChat() { return announceInChat; }

    public void setAnnounceInChat(boolean announceInChat) { this.announceInChat = announceInChat; }


    public boolean isP5DragonLines() { return p5DragonLines; }

    public void setP5DragonLines(boolean p5DragonLines) { this.p5DragonLines = p5DragonLines; }

    public boolean isP5RelicLines() { return p5RelicLines; }

    public void setP5RelicLines(boolean p5RelicLines) { this.p5RelicLines = p5RelicLines; }

    public boolean isP5LinesRight() { return p5LinesRight; }

    public void setP5LinesRight(boolean p5LinesRight) { this.p5LinesRight = p5LinesRight; }


    public boolean isWatcherMoveSplit() { return watcherMoveSplit; }

    public void setWatcherMoveSplit(boolean watcherMoveSplit) { this.watcherMoveSplit = watcherMoveSplit; }

    public boolean isCoreEntryWithoutSplits() { return coreEntryWithoutSplits; }

    public void setCoreEntryWithoutSplits(boolean v) { coreEntryWithoutSplits = v; }

    /** Core Entry times are being kept: the Core Entry switch, and Split Timers on or Show With Splits Off set. */
    public boolean isCoreEntryActive() {
        return coreEntryTimes && com.killer560.hub.util.SkyblockGate.allows() && (enabled || coreEntryWithoutSplits);
    }

    /** Core Entry runs on its own: Split Timers is off but Show With Splits Off keeps the core times going. */
    public boolean isCoreEntryAlone() { return isCoreEntryActive() && !isEnabled(); }

    public boolean isCoreEntryTimes() { return coreEntryTimes; }

    public void setCoreEntryTimes(boolean coreEntryTimes) { this.coreEntryTimes = coreEntryTimes; }

    public boolean isCoreEntrySlowestChat() { return coreEntrySlowestChat; }

    public void setCoreEntrySlowestChat(boolean coreEntrySlowestChat) { this.coreEntrySlowestChat = coreEntrySlowestChat; }

    public boolean isCoreEntrySlowestParty() { return coreEntrySlowestParty; }

    public void setCoreEntrySlowestParty(boolean coreEntrySlowestParty) { this.coreEntrySlowestParty = coreEntrySlowestParty; }

    public boolean isCoreEntrySlowestHud() { return coreEntrySlowestHud; }

    public void setCoreEntrySlowestHud(boolean coreEntrySlowestHud) { this.coreEntrySlowestHud = coreEntrySlowestHud; }




    /** The stored Tick Counting switch. */
    public boolean isTickCountingRaw() { return tickCounting; }

    /** Tick Counting in effect (legit and cheat builds alike since 2026-11-02). */
    public boolean isTickCounting() { return tickCounting; }

    public void setTickCounting(boolean v) { tickCounting = v; }

    /** A split time as the timers show it: unchanged, or rounded to the nearest 0.05 s (one tick) with Tick Counting. */
    public static long shown(long ms) { return ms <= 0L || !getInstance().isTickCounting() ? ms : Math.round(ms / 50.0) * 50L; }

    public boolean isLaglessTimes() { return laglessTimes; }

    public void setLaglessTimes(boolean laglessTimes) { this.laglessTimes = laglessTimes; }



}

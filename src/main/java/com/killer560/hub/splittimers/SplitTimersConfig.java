package com.killer560.hub.splittimers;

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
    private boolean announceDeviceTimes = true;
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

    /** Lagless time (see SplitLagClock) in "(...)" after every time the splits show. ON by default since the
     *  2026-10-08 layout, whose every row reads "real (lagless)". The 2026-09-20 layout toggles (divider, Boss Entry,
     *  Boss, Total With/Without Lag, Lag Lost) and Clear Splits are gone: that layout is now fixed, and their old keys
     *  in a saved file are ignored. */
    private boolean laglessTimes = true;

    private SplitTimersConfig() {
    }

    public static SplitTimersConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new SplitTimersConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            SplitTimersConfig cfg = new SplitTimersConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.announceInChat = ConfigJson.getBool(obj, "announceInChat", true);
            cfg.announceDeviceTimes = ConfigJson.getBool(obj, "announceDeviceTimes", true);
            cfg.p5DragonLines = ConfigJson.getBool(obj, "p5DragonLines", false);
            cfg.p5RelicLines = ConfigJson.getBool(obj, "p5RelicLines", false);
            cfg.p5LinesRight = ConfigJson.getBool(obj, "p5LinesRight", true);
            cfg.watcherMoveSplit = ConfigJson.getBool(obj, "watcherMoveSplit", false);
            cfg.coreEntryTimes = ConfigJson.getBool(obj, "coreEntryTimes", false);
            cfg.coreEntrySlowestChat = ConfigJson.getBool(obj, "coreEntrySlowestChat", false);
            cfg.coreEntrySlowestParty = ConfigJson.getBool(obj, "coreEntrySlowestParty", false);
            cfg.coreEntrySlowestHud = ConfigJson.getBool(obj, "coreEntrySlowestHud", false);
            cfg.laglessTimes = ConfigJson.getBool(obj, "laglessTimes", true);
            instance = cfg;
        } catch (Exception e) {
            instance = new SplitTimersConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("announceInChat", announceInChat);
            obj.addProperty("announceDeviceTimes", announceDeviceTimes);
            obj.addProperty("p5DragonLines", p5DragonLines);
            obj.addProperty("p5RelicLines", p5RelicLines);
            obj.addProperty("p5LinesRight", p5LinesRight);
            obj.addProperty("watcherMoveSplit", watcherMoveSplit);
            obj.addProperty("coreEntryTimes", coreEntryTimes);
            obj.addProperty("coreEntrySlowestChat", coreEntrySlowestChat);
            obj.addProperty("coreEntrySlowestParty", coreEntrySlowestParty);
            obj.addProperty("coreEntrySlowestHud", coreEntrySlowestHud);
            obj.addProperty("laglessTimes", laglessTimes);
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

    public boolean isAnnounceInChat() {
        return announceInChat;
    }

    public void setAnnounceInChat(boolean announceInChat) {
        this.announceInChat = announceInChat;
    }

    public boolean isAnnounceDeviceTimes() {
        return announceDeviceTimes;
    }

    public void setAnnounceDeviceTimes(boolean announceDeviceTimes) {
        this.announceDeviceTimes = announceDeviceTimes;
    }

    public boolean isP5DragonLines() {
        return p5DragonLines;
    }

    public void setP5DragonLines(boolean p5DragonLines) {
        this.p5DragonLines = p5DragonLines;
    }

    public boolean isP5RelicLines() {
        return p5RelicLines;
    }

    public void setP5RelicLines(boolean p5RelicLines) {
        this.p5RelicLines = p5RelicLines;
    }

    public boolean isP5LinesRight() {
        return p5LinesRight;
    }

    public void setP5LinesRight(boolean p5LinesRight) {
        this.p5LinesRight = p5LinesRight;
    }


    public boolean isWatcherMoveSplit() {
        return watcherMoveSplit;
    }

    public void setWatcherMoveSplit(boolean watcherMoveSplit) {
        this.watcherMoveSplit = watcherMoveSplit;
    }

    public boolean isCoreEntryTimes() {
        return coreEntryTimes;
    }

    public void setCoreEntryTimes(boolean coreEntryTimes) {
        this.coreEntryTimes = coreEntryTimes;
    }

    public boolean isCoreEntrySlowestChat() {
        return coreEntrySlowestChat;
    }

    public void setCoreEntrySlowestChat(boolean coreEntrySlowestChat) {
        this.coreEntrySlowestChat = coreEntrySlowestChat;
    }

    public boolean isCoreEntrySlowestParty() {
        return coreEntrySlowestParty;
    }

    public void setCoreEntrySlowestParty(boolean coreEntrySlowestParty) {
        this.coreEntrySlowestParty = coreEntrySlowestParty;
    }

    public boolean isCoreEntrySlowestHud() {
        return coreEntrySlowestHud;
    }

    public void setCoreEntrySlowestHud(boolean coreEntrySlowestHud) {
        this.coreEntrySlowestHud = coreEntrySlowestHud;
    }




    public boolean isLaglessTimes() {
        return laglessTimes;
    }

    public void setLaglessTimes(boolean laglessTimes) {
        this.laglessTimes = laglessTimes;
    }



}
